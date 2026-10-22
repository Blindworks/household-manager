package com.household.manager.systemlog;

import com.household.manager.repository.SystemJobStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wie in der Anwendung (Spring Boot schaltet Auto-Proxy mit CGLIB ein): jeder Bean mit
 * {@code @Scheduled}-Methode wird durch den Aspekt zum Proxy. Haelt fest, dass die Planung dabei
 * erhalten bleibt und ein Bean ohne Default-Konstruktor (Konstruktorinjektion) proxyfaehig ist.
 */
class ScheduledJobAspectContextTest {

    static class Dependency {
        String value() {
            return "ok";
        }
    }

    static class InjectedJob {
        private final Dependency dependency;
        String lastValue;

        InjectedJob(Dependency dependency) {
            this.dependency = dependency;
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void poll() {
            lastValue = dependency.value();
        }
    }

    @Configuration
    @EnableScheduling
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean
        SystemJobMonitor monitor() {
            return new SystemJobMonitor(mock(SystemJobStatusRepository.class), mock(SystemLogWriter.class),
                    Clock.systemDefaultZone());
        }

        @Bean
        ScheduledJobAspect aspect(SystemJobMonitor monitor) {
            return new ScheduledJobAspect(monitor);
        }

        @Bean
        Dependency dependency() {
            return new Dependency();
        }

        @Bean
        InjectedJob job(Dependency dependency) {
            return new InjectedJob(dependency);
        }
    }

    @Test
    void scheduledBean_isProxied_stillScheduled_andRecorded() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            InjectedJob job = context.getBean(InjectedJob.class);
            SystemJobMonitor monitor = context.getBean(SystemJobMonitor.class);

            assertThat(AopUtils.isCglibProxy(job)).isTrue();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);

            job.poll();

            assertThat(monitor.statuses()).singleElement().satisfies(view -> {
                assertThat(view.jobKey()).isEqualTo("InjectedJob.poll");
                assertThat(view.state()).isEqualTo(JobState.OK);
            });
        }
    }
}
