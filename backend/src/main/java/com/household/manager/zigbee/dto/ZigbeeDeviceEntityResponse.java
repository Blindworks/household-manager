package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.entitystate.EntityDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Eine Entitaet des Geraets aus dem Entity-State-Layer, mit lastUpdated (nicht nur lastChanged). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceEntityResponse {
    private String entityId;
    private String displayName;
    private EntityDomain domain;
    private String state;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastChanged;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUpdated;
}
