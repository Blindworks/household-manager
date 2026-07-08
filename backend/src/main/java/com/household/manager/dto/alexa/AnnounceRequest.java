package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.util.List;

public record AnnounceRequest(String text, List<String> serialNumbers, AlexaTtsMode mode) {}
