package com.household.manager.zigbee.dto;

/** Body von POST /v1/zigbee/bridge/permit-join. */
public record PermitJoinRequest(int seconds) {
}
