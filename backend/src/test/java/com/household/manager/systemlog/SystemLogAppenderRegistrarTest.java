package com.household.manager.systemlog;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemLogAppenderRegistrarTest {

    private final SystemLogAppenderRegistrar registrar =
            new SystemLogAppenderRegistrar(mock(SystemLogWriter.class), Clock.systemDefaultZone());

    @AfterEach
    void tearDown() {
        registrar.detach();
    }

    private static long count() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        long n = 0;
        var it = root.iteratorForAppenders();
        while (it.hasNext()) {
            if (it.next() instanceof SystemLogAppender) {
                n++;
            }
        }
        return n;
    }

    @Test
    void attach_isIdempotent_andDetachRemoves() {
        long before = count();

        registrar.attach();
        registrar.attach();
        assertThat(count()).isEqualTo(before + 1);

        registrar.detach();
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void attachesWhenBeanIsCreated_notOnlyAtApplicationReady_andDetachesOnClose() {
        long before = count();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(SystemLogWriter.class, () -> mock(SystemLogWriter.class));
        context.registerBean(Clock.class, Clock::systemDefaultZone);
        context.registerBean(SystemLogAppenderRegistrar.class);

        // refresh() ohne Spring Boot feuert kein ApplicationReadyEvent - angehaengt sein muss er trotzdem
        context.refresh();
        assertThat(count()).isEqualTo(before + 1);

        context.close();
        assertThat(count()).isEqualTo(before);
    }
}
