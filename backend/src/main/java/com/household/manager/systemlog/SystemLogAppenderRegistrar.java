package com.household.manager.systemlog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Haengt den {@link SystemLogAppender} an den Root-Logger, sobald diese Bean entsteht, und loest ihn beim
 * Beenden wieder. Bewusst nicht erst bei {@code ApplicationReadyEvent}: sonst gingen die Warnungen des Starts
 * verloren (z. B. „Flow … konnte beim Start nicht geladen werden“, fruehe Poller). Weil der Writer per
 * Konstruktor injiziert ist, baut Spring diese Bean vor ihm ab - der Appender ist also geloest, bevor der
 * Writer-Thread stoppt.
 */
@Component
@RequiredArgsConstructor
public class SystemLogAppenderRegistrar {

    private final SystemLogWriter writer;
    private final Clock clock;
    private SystemLogAppender appender;

    @PostConstruct
    public synchronized void attach() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (appender != null || !(factory instanceof LoggerContext context)) {
            return;
        }
        SystemLogAppender created = new SystemLogAppender(writer, clock);
        created.setContext(context);
        created.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(created);
        appender = created;
    }

    @PreDestroy
    public synchronized void detach() {
        if (appender == null) {
            return;
        }
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
        appender = null;
    }
}
