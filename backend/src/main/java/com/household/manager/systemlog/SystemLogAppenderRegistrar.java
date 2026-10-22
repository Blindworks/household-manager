package com.household.manager.systemlog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Haengt den {@link SystemLogAppender} nach dem Start an den Root-Logger und loest ihn beim Beenden wieder. */
@Component
@RequiredArgsConstructor
public class SystemLogAppenderRegistrar {

    private final SystemLogWriter writer;
    private SystemLogAppender appender;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void attach() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (appender != null || !(factory instanceof LoggerContext context)) {
            return;
        }
        SystemLogAppender created = new SystemLogAppender(writer);
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
