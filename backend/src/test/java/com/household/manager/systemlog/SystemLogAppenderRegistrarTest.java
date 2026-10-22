package com.household.manager.systemlog;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemLogAppenderRegistrarTest {

    private final SystemLogAppenderRegistrar registrar = new SystemLogAppenderRegistrar(mock(SystemLogWriter.class));

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
}
