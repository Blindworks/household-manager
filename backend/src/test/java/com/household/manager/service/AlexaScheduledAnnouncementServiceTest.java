package com.household.manager.service;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.repository.AlexaScheduledAnnouncementRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AlexaScheduledAnnouncementServiceTest {

    private final AlexaScheduledAnnouncementService service = new AlexaScheduledAnnouncementService(
            mock(AlexaScheduledAnnouncementRepository.class),
            mock(AlexaAnnouncementService.class));

    private AlexaScheduledAnnouncement announcement(String weekdays, LocalTime time,
                                                    boolean enabled, LocalDateTime lastRun) {
        return AlexaScheduledAnnouncement.builder()
                .text("Test").timeOfDay(time).weekdays(weekdays)
                .mode(AlexaTtsMode.ANNOUNCE).enabled(enabled).lastRun(lastRun)
                .build();
    }

    // 2026-07-08 ist ein Mittwoch (WEDNESDAY)
    private static final LocalDateTime WED_08_00 = LocalDateTime.of(2026, 7, 8, 8, 0, 30);

    @Test
    void dueWhenWeekdayAndTimeMatchAndNotRunYet() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true, null);
        assertThat(service.isDue(a, WED_08_00)).isTrue();
    }

    @Test
    void notDueOnWrongWeekday() {
        AlexaScheduledAnnouncement a = announcement("MONDAY", LocalTime.of(8, 0), true, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueWhenDisabled() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), false, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueOutsideTimeWindow() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 5), true, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueWhenAlreadyRunThisMinute() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true,
                LocalDateTime.of(2026, 7, 8, 8, 0, 10));
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void dueAgainAfterMissedNextDayNotBackfilled() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true,
                LocalDateTime.of(2026, 7, 1, 8, 0, 5));
        assertThat(service.isDue(a, WED_08_00)).isTrue();
    }
}
