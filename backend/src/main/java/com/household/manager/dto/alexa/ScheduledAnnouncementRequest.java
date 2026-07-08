package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.time.LocalTime;
import java.util.List;

public record ScheduledAnnouncementRequest(
        String text,
        LocalTime timeOfDay,
        List<String> weekdays,
        List<String> serialNumbers,
        AlexaTtsMode mode,
        boolean enabled) {}
