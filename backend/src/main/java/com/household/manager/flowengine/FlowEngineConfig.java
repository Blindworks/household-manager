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

    /**
     * Ein Thread nur für Alexa-Töne ({@code alexa-sound}): der Ton geht raus, ohne dass der
     * Flow-Lauf auf Amazon wartet — nachgelagerte Nodes (z. B. das Verriegeln) laufen sofort weiter.
     * Kleine Queue: ein Ton, der erst nach einem Stau käme, wäre als Rückmeldung wertlos.
     */
    @Bean(name = "alexaSoundExecutor")
    public Executor alexaSoundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("alexa-sound-");
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
