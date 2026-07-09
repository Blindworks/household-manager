package com.household.manager.flowengine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * Eigener Thread-Pool für Flow-Ausführungen: kein Flow — egal wie langsam —
 * blockiert je einen Polling-Thread, MQTT-Callback oder Schaltbefehl.
 */
@Configuration
public class FlowEngineConfig {

    @Bean(name = "flowEngineExecutor")
    public Executor flowEngineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("flow-engine-");
        executor.initialize();
        return executor;
    }

    /** Eigener Scheduler für Flow-Timer (Cron, Delay, Verweildauer-Re-Check), damit sie
     *  nicht die Kapazität des geteilten Polling-Schedulers (Airrohr/Tasmota/Weather/
     *  Shelly/AnkerSolix, siehe SchedulingConfig) mitbenutzen. */
    @Bean(name = "flowTaskScheduler")
    public TaskScheduler flowTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("flow-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
