package com.household.manager.meross.dto;

import java.util.List;

public record MerossCloudDevicesResponse(
        int apiStatus,
        int sysStatus,
        String message,
        List<MerossCloudDevice> devices
) {
}
