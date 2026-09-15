package com.household.manager.zigbee.dto;

import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Ein Bridge-Ereignis fuer GET /v1/zigbee/bridge/events und SSE {@code bridge-event}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeBridgeEventResponse {
    private String type;
    private String friendlyName;
    private String ieeeAddress;
    private String status;
    private String model;
    private String vendor;
    private Instant receivedAt;

    public static ZigbeeBridgeEventResponse from(ZigbeeBridgeEvent event) {
        return ZigbeeBridgeEventResponse.builder()
                .type(event.type())
                .friendlyName(event.friendlyName())
                .ieeeAddress(event.ieeeAddress())
                .status(event.status())
                .model(event.model())
                .vendor(event.vendor())
                .receivedAt(event.receivedAt())
                .build();
    }
}
