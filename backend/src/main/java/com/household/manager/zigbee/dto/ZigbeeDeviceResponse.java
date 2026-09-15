package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ein Zigbee-Geraet fuer die Seite: Grundlage ist zigbee2mqtt's Verzeichnis, unsere
 * Tabelle liefert Batterie/LQ/lastSeen, der Entity-State-Layer die Entitaeten.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceResponse {
    /** DB-Id, null wenn das Geraet nur im Registry steht (noch nie gesendet). */
    private Long id;
    private String friendlyName;
    private String ieeeAddress;
    /** false = nur in unserer Tabelle, zigbee2mqtt kennt es nicht (mehr). */
    private boolean knownToBridge;
    private String type;
    private String powerSource;
    private boolean battery;
    private Boolean interviewCompleted;
    private Boolean supported;
    private String model;
    private String vendor;
    private String description;
    private Integer lastBatteryPercent;
    private Integer lastLinkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastSeen;

    private ZigbeeDeviceHealthResponse health;
    private List<ZigbeeDeviceEntityResponse> entities;
}
