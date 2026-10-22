package com.household.manager.systemlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.time.Duration;
import java.util.Arrays;

/**
 * Uebernimmt WARN/ERROR-Zeilen des Backends ins Systemprotokoll. Wirft nie und blockiert nie (der Writer
 * arbeitet asynchron). Ereignisse der Protokoll-Threads und des eigenen Pakets werden ignoriert, sonst
 * speiste ein DB-Ausfall seine eigenen Warnungen zurueck in die Queue.
 */
public class SystemLogAppender extends AppenderBase<ILoggingEvent> {

    static final Duration WINDOW = Duration.ofMinutes(10);
    private static final String OWN_PACKAGE = "com.household.manager.systemlog";
    private static final int MAX_TITLE = 200;
    private static final int MAX_DETAIL_LINES = 20;

    private final SystemLogWriter writer;

    public SystemLogAppender(SystemLogWriter writer) {
        this.writer = writer;
        setName("systemLogAppender");
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (!event.getLevel().isGreaterOrEqual(Level.WARN) || isOwn(event)) {
                return;
            }
            String message = event.getFormattedMessage();
            writer.writeOrIncrement(SystemLogEvent.builder()
                    .kind(SystemLogKind.LOG)
                    .status(event.getLevel().isGreaterOrEqual(Level.ERROR) ? SystemLogStatus.ERROR : SystemLogStatus.WARN)
                    .source(shortLoggerName(event.getLoggerName()))
                    .title(message != null && message.length() > MAX_TITLE ? message.substring(0, MAX_TITLE) : message)
                    .message(message)
                    .detailsJson(stackTrace(event.getThrowableProxy()))
                    .build(), WINDOW);
        } catch (Throwable ex) {
            // Ein Logging-Fehler darf nie in den Aufrufer durchschlagen.
        }
    }

    private boolean isOwn(ILoggingEvent event) {
        String logger = event.getLoggerName();
        return writer.isWriterThread()
                || SystemLogThreads.isOwnThread(event.getThreadName())
                || (logger != null && logger.startsWith(OWN_PACKAGE));
    }

    static String shortLoggerName(String name) {
        if (name == null) {
            return "";
        }
        String[] parts = name.split("\\.");
        return parts.length <= 2 ? name : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String stackTrace(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        String[] lines = ThrowableProxyUtil.asString(proxy).split("\\R");
        return String.join("\n", Arrays.copyOf(lines, Math.min(lines.length, MAX_DETAIL_LINES)));
    }
}
