package com.household.manager.systemlog;

import com.household.manager.repository.SystemJobStatusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Jobs mit {@code @Scheduled} und {@code @Transactional} (z. B. PowerHistoryAggregationJob): der Aspekt muss
 * die Transaktion umschliessen. Sonst saehe er einen gescheiterten Commit als OK, und ein DB-Fehler des
 * Monitors liefe in der Job-Transaktion und machte sie rollback-only.
 */
class ScheduledJobTransactionContextTest {

    /** Transaktionsmanager ohne Ressource, der Commit/Rollback mitschreibt und den Commit scheitern lassen kann. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            calls.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            calls.add("commit");
            if (failCommit) {
                throw new TransactionSystemException("Commit gescheitert");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            calls.add("rollback");
        }
    }

    static class TxJob {
        private boolean ranInTransaction;

        /** Ueber eine Methode, nicht das Feld: das Feld des CGLIB-Proxys bliebe leer. */
        public boolean ranInTransaction() {
            return ranInTransaction;
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        @Transactional
        public void aggregate() {
            ranInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    static final SystemJobStatusRepository REPOSITORY = mock(SystemJobStatusRepository.class);
    static final List<Boolean> REPOSITORY_SAW_TRANSACTION = new CopyOnWriteArrayList<>();

    @Configuration
    @EnableScheduling
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        SystemJobMonitor monitor() {
            return new SystemJobMonitor(REPOSITORY, mock(SystemLogWriter.class), Clock.systemDefaultZone());
        }

        @Bean
        ScheduledJobAspect aspect(SystemJobMonitor monitor) {
            return new ScheduledJobAspect(monitor);
        }

        @Bean
        TxJob job() {
            return new TxJob();
        }
    }

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
    private final TxJob job = context.getBean(TxJob.class);
    private final SystemJobMonitor monitor = context.getBean(SystemJobMonitor.class);
    private final RecordingTransactionManager transactions = context.getBean(RecordingTransactionManager.class);

    @AfterEach
    void close() {
        context.close();
        REPOSITORY_SAW_TRANSACTION.clear();
    }

    @Test
    void commitFailure_isRecordedAsFailing() throws Exception {
        transactions.failCommit = true;

        assertThatThrownBy(job::aggregate).isInstanceOf(TransactionSystemException.class);
        monitor.awaitIdleForTest();

        assertThat(job.ranInTransaction()).isTrue();
        assertThat(monitor.statuses()).singleElement().satisfies(view -> {
            assertThat(view.jobKey()).isEqualTo("TxJob.aggregate");
            assertThat(view.state()).isEqualTo(JobState.FAILING);
            assertThat(view.lastMessage()).contains("Commit gescheitert");
        });
    }

    @Test
    void monitorRepositoryFailure_doesNotTouchJobTransaction() throws Exception {
        when(REPOSITORY.findAll()).thenAnswer(inv -> {
            REPOSITORY_SAW_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("db down");
        });
        when(REPOSITORY.save(any())).thenAnswer(inv -> {
            REPOSITORY_SAW_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("db down");
        });

        job.aggregate();
        monitor.awaitIdleForTest();

        assertThat(transactions.calls).containsExactly("begin", "commit");
        assertThat(REPOSITORY_SAW_TRANSACTION).isNotEmpty().containsOnly(false);
        assertThat(monitor.statuses()).singleElement()
                .satisfies(view -> assertThat(view.state()).isEqualTo(JobState.OK));
    }
}
