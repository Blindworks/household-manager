package com.household.manager.systemlog;

import com.household.manager.repository.SystemJobStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ScheduledJobAspectTest {

    private final SystemJobMonitor monitor = new SystemJobMonitor(mock(SystemJobStatusRepository.class),
            mock(SystemLogWriter.class),
            Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin")));

    static class TestJob {
        SystemJobMonitor monitor;

        @Scheduled(fixedDelay = 1000)
        public void boom() {
            throw new IllegalStateException("Cloud weg");
        }

        @Scheduled(fixedDelay = 1000)
        public void failSoftly() {
            monitor.reportInactive("erst inaktiv");
            monitor.reportFailure("leise kaputt");
        }

        @Scheduled(fixedDelay = 1000)
        public void idle() {
            monitor.reportInactive("nicht konfiguriert");
        }

        @Scheduled(fixedDelay = 1000)
        public String fine() {
            return "ergebnis";
        }

        public void notScheduled() {
        }
    }

    /** Name wie ein von Spring erzeugter CGLIB-Proxy; ueber ein Interface (JDK-Proxy) gewickelt. */
    static class CglibJob$$SpringCGLIB$$0 implements Runnable {
        @Override
        @Scheduled(fixedDelay = 1000)
        public void run() {
        }
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ScheduledJobAspect(monitor));
        return factory.getProxy();
    }

    private TestJob job() {
        TestJob target = new TestJob();
        target.monitor = monitor;
        return proxy(target);
    }

    private SystemJobMonitor.JobStatusView viewOf(String key) {
        return monitor.statuses().stream().filter(v -> v.jobKey().equals(key)).findFirst().orElse(null);
    }

    @Test
    void exception_recordsFailing_andRethrows() {
        TestJob job = job();

        assertThatThrownBy(job::boom).isInstanceOf(IllegalStateException.class).hasMessage("Cloud weg");

        assertThat(viewOf("TestJob.boom").state()).isEqualTo(JobState.FAILING);
        assertThat(viewOf("TestJob.boom").lastMessage()).contains("Cloud weg");
    }

    @Test
    void reportFailureInside_recordsFailing_notOk() {
        job().failSoftly();

        assertThat(viewOf("TestJob.failSoftly").state()).isEqualTo(JobState.FAILING);
        assertThat(viewOf("TestJob.failSoftly").lastMessage()).isEqualTo("leise kaputt");
    }

    @Test
    void reportInactiveInside_recordsInactive() {
        job().idle();

        assertThat(viewOf("TestJob.idle").state()).isEqualTo(JobState.INACTIVE);
    }

    @Test
    void normalReturn_recordsOk() {
        assertThat(job().fine()).isEqualTo("ergebnis");

        assertThat(viewOf("TestJob.fine").state()).isEqualTo(JobState.OK);
    }

    @Test
    void notScheduledMethod_isNotMonitored() {
        job().notScheduled();

        assertThat(monitor.statuses()).isEmpty();
    }

    @Test
    void threadLocalIsCleared_afterRun() {
        job().failSoftly();

        monitor.reportFailure("nach dem Lauf");
        job().fine();

        assertThat(viewOf("TestJob.fine").state()).isEqualTo(JobState.OK);
    }

    @Test
    void jobKey_isClassDotMethod_withoutCglibSuffix() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new CglibJob$$SpringCGLIB$$0());
        factory.addAspect(new ScheduledJobAspect(monitor));
        Runnable job = factory.getProxy();

        job.run();

        assertThat(viewOf("CglibJob.run").state()).isEqualTo(JobState.OK);
    }
}
