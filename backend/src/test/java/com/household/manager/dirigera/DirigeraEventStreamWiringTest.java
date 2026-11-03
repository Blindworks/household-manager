package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * contextLoads scheitert lokal ohne DB schon an Liquibase und verdeckt damit Verdrahtungsfehler.
 * Dieser minimale Kontext baut die echten Dirigera-Beans des Ereignisstroms (Rest gemockt) und
 * prueft, dass sie sich verdrahten lassen und beide Zeitplaene mit den Defaults entstehen.
 */
class DirigeraEventStreamWiringTest {

    @Configuration
    @EnableScheduling
    @Import({DirigeraEventStreamService.class, DirigeraPollingService.class, DirigeraButtonRegistry.class,
            DirigeraEventParser.class, DirigeraDeviceParser.class, DirigeraEntityMapper.class})
    static class Config {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        DirigeraSettingsService settingsService() {
            return mock(DirigeraSettingsService.class);
        }

        @Bean
        DirigeraHttpClient httpClient() {
            return mock(DirigeraHttpClient.class);
        }

        @Bean
        DirigeraSensorRepository repository() {
            return mock(DirigeraSensorRepository.class);
        }

        @Bean
        DirigeraReadingRecorder recorder() {
            return mock(DirigeraReadingRecorder.class);
        }

        @Bean
        EntityStateService entityStateService() {
            return mock(EntityStateService.class);
        }

        @Bean
        SystemJobMonitor jobMonitor() {
            return mock(SystemJobMonitor.class);
        }
    }

    @Test
    void ereignisstromUndPollLassenSichVerdrahten() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(context.getBean(DirigeraEventStreamService.class).isConnected()).isFalse();
            var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();

            assertThat(tasks).extracting(ScheduledTask::getTask)
                    .filteredOn(IntervalTask.class::isInstance)
                    .extracting(task -> ((IntervalTask) task).getIntervalDuration())
                    .contains(Duration.ofSeconds(20), Duration.ofSeconds(60));
        }
    }
}
