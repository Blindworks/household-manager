package com.household.manager.systemlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SystemLogAppenderTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final SystemLogWriter writer = mock(SystemLogWriter.class);
    private final LoggerContext context = new LoggerContext();
    private SystemLogAppender appender;

    @BeforeEach
    void setUp() {
        appender = new SystemLogAppender(writer, Clock.system(ZONE));
        appender.setContext(context);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        appender.stop();
    }

    private LoggingEvent event(String loggerName, Level level, String message, Throwable t) {
        Logger logger = context.getLogger(loggerName);
        return new LoggingEvent(Logger.class.getName(), logger, level, message, t, null);
    }

    @Test
    void info_isIgnored() {
        appender.doAppend(event("com.household.manager.foo.Bar", Level.INFO, "hi", null));

        verify(writer, never()).writeOrIncrement(any(), any());
    }

    @Test
    void warn_isWrittenWithWindow10Min() {
        appender.doAppend(event("com.household.manager.foo.Bar", Level.WARN, "kaputt", null));

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).writeOrIncrement(captor.capture(), eq(Duration.ofMinutes(10)));
        SystemLogEvent e = captor.getValue();
        assertThat(e.kind()).isEqualTo(SystemLogKind.LOG);
        assertThat(e.status()).isEqualTo(SystemLogStatus.WARN);
        assertThat(e.source()).isEqualTo("foo.Bar");
        assertThat(e.title()).isEqualTo("kaputt");
        assertThat(e.message()).isEqualTo("kaputt");
        assertThat(e.detailsJson()).isNull();
    }

    @Test
    void error_withThrowable_detailsCappedAt20Lines() {
        appender.doAppend(event("a.b.C", Level.ERROR, "x".repeat(300), deepException()));

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).writeOrIncrement(captor.capture(), any());
        SystemLogEvent e = captor.getValue();
        assertThat(e.status()).isEqualTo(SystemLogStatus.ERROR);
        assertThat(e.title()).hasSize(200);
        assertThat(e.message()).hasSize(300);
        assertThat(e.detailsJson().split("\n")).hasSizeLessThanOrEqualTo(20);
        assertThat(e.detailsJson()).startsWith("java.lang.IllegalStateException: tief");
    }

    @Test
    void nullMessage_getsDefaultTitleAndMessage() {
        appender.doAppend(event("a.b.C", Level.WARN, null, new IllegalStateException()));

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).writeOrIncrement(captor.capture(), any());
        assertThat(captor.getValue().title()).isEqualTo("(ohne Text)");
        assertThat(captor.getValue().message()).isEqualTo("(ohne Text)");
    }

    @Test
    void occurredAt_isEventTimestampInClockZone() {
        LoggingEvent logged = event("a.b.C", Level.WARN, "x", null);
        logged.setTimeStamp(Instant.parse("2026-10-22T08:15:30Z").toEpochMilli());

        appender.doAppend(logged);

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).writeOrIncrement(captor.capture(), any());
        assertThat(captor.getValue().occurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 15, 30));
    }

    @Test
    void flowEngineNodeFailureLine_isIgnored_becauseTheRunEntryCarriesTheError() {
        appender.doAppend(event("com.household.manager.flowengine.FlowEngine", Level.WARN,
                "Flow 1 node n1 failed, aborting branch: kaputt", null));
        appender.doAppend(event("com.household.manager.flowengine.FlowEngineBootstrap", Level.ERROR,
                "Flow 1 (X) konnte beim Start nicht geladen werden", null));

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, times(1)).writeOrIncrement(captor.capture(), any());
        assertThat(captor.getValue().source()).isEqualTo("flowengine.FlowEngineBootstrap");
    }

    @Test
    void ownPackageLogger_isIgnored() {
        appender.doAppend(event("com.household.manager.systemlog.writer", Level.ERROR, "x", null));

        verify(writer, never()).writeOrIncrement(any(), any());
    }

    @Test
    void writerThread_isIgnored() {
        when(writer.isWriterThread()).thenReturn(true);

        appender.doAppend(event("a.b.C", Level.ERROR, "x", null));

        verify(writer, never()).writeOrIncrement(any(), any());
    }

    @Test
    void ownThreadNames_areIgnored() throws Exception {
        for (String name : new String[]{SystemLogThreads.WRITER, SystemLogThreads.JOB_MONITOR, SystemLogThreads.PENDING_RUNS}) {
            Thread t = new Thread(() -> appender.doAppend(event("a.b.C", Level.WARN, "x", null)), name);
            t.start();
            t.join();
        }

        verify(writer, never()).writeOrIncrement(any(), any());
        assertThat(SystemLogThreads.isOwnThread("main")).isFalse();
        assertThat(SystemLogThreads.isOwnThread(null)).isFalse();
    }

    @Test
    void writerFailure_isSwallowed() {
        doThrow(new RuntimeException("x")).when(writer).writeOrIncrement(any(), any());

        appender.doAppend(event("a.b.C", Level.WARN, "x", null));
    }

    private static Throwable deepException() {
        Throwable t = new IllegalStateException("tief");
        for (int i = 0; i < 5; i++) {
            t = new RuntimeException("w" + i, t);
        }
        return new IllegalStateException("tief", t);
    }
}
