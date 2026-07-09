package com.household.manager.flowengine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
