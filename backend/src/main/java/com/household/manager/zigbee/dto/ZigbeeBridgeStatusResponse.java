package com.household.manager.zigbee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** GET /v1/zigbee/bridge und SSE-Ereignis {@code bridge-info}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeBridgeStatusResponse {
    private String version;
    private boolean connected;
    private boolean registryLoaded;
    private boolean permitJoin;
    /** Ende des Anlernfensters, null wenn geschlossen. */
    private Instant permitJoinEnd;
    /** null, solange nie eine bridge/info kam — die Seite zeigt dann keinen Hinweis. */
    private Boolean availabilityCheckEnabled;
}
