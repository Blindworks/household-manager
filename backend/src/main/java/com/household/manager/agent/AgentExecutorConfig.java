package com.household.manager.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Eigene, einthreadige Executoren des Hausverstands.
 *
 * <p>Der Lauf-Executor hat genau einen Thread: Laeufe sind teuer (Modellaufruf, Werkzeuge) und
 * duerfen sich nie ueberlappen; der Ein-Thread-Executor ist die zweite Sicherung neben dem
 * Lauf-Flag in {@link AgentWakeService}. Der Debounce-Scheduler ist bewusst getrennt — laege der
 * Weck-Timer auf dem Lauf-Thread, wartete er bis zum Ende eines 90-s-Laufs, bevor er seine Gruende
 * ueberhaupt abliefern koennte. Beide Threads sind Daemons, damit sie das Herunterfahren der JVM
 * nie blockieren; {@code destroyMethod = "shutdownNow"} bricht einen laufenden Modellaufruf beim
 * Herunterfahren des Kontexts ab, statt auf ihn zu warten.
 */
@Configuration
public class AgentExecutorConfig {

    @Bean(name = "agentExecutor", destroyMethod = "shutdownNow")
    public ExecutorService agentExecutor() {
        return Executors.newSingleThreadExecutor(daemon("house-agent"));
    }

    @Bean(name = "agentDebounceScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService agentDebounceScheduler() {
        return Executors.newSingleThreadScheduledExecutor(daemon("house-agent-debounce"));
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
