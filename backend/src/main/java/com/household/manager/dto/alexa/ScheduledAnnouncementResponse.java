package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record ScheduledAnnouncementResponse(
        Long id,
        String text,
        LocalTime timeOfDay,
        List<String> weekdays,
        List<String> serialNumbers,
        AlexaTtsMode mode,
        boolean enabled,
        LocalDateTime lastRun,
        String lastError) {}
