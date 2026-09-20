package com.household.manager.charging;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Poll-Intervalle des Ladesaeulen-Pollers stehen in Sekunden in den Properties und werden
 * per SpEL in Millisekunden umgerechnet. Dieser Ausdruck wird nur beim Kontextstart ausgewertet,
 * den kein lokaler Test durchlaeuft (contextLoads scheitert ohne DB) - deshalb hier ein
 * minimaler Kontext mit genau derselben Annotation.
 */
class ChargingPollingScheduleTest {

    @Configuration
    @EnableScheduling
    static class ScheduleConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ScheduledProbe probe() {
            return new ScheduledProbe();
        }
    }

    static class ScheduledProbe {
        @Scheduled(fixedDelayString = "#{${charging.area-poll-seconds:300} * 1000}",
                initialDelayString = "${charging.initial-delay-ms:25000}")
        void area() {
        }

        @Scheduled(fixedDelayString = "#{${charging.favorite-poll-seconds:60} * 1000}",
                initialDelayString = "${charging.initial-delay-ms:25000}")
        void favorites() {
        }
    }

    @Test
    void spelAusdrueckeDerPollIntervalleWerdenBeimKontextstartAusgewertet() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ScheduleConfig.class)) {
            ScheduledAnnotationBeanPostProcessor processor =
                    context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            Set<ScheduledTask> tasks = processor.getScheduledTasks();

            assertThat(tasks).hasSize(2);
            assertThat(tasks).extracting(task -> ((IntervalTask) task.getTask()).getIntervalDuration())
                    .containsExactlyInAnyOrder(Duration.ofSeconds(300), Duration.ofSeconds(60));
        }
    }
}
