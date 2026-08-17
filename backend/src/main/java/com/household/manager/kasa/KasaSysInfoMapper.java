package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.kasa.dto.KasaDiscoveryDto;

/**
 * Shared mapping from a Kasa {@code system.get_sysinfo} JSON node to
 * {@link KasaDiscoveryDto}. Used both by broadcast discovery
 * ({@link KasaDiscoveryService}) and unicast probing ({@link KasaService#probe(String)}),
 * which receive the identical sysinfo payload shape, so the field extraction
 * exists in exactly one place.
 */
final class KasaSysInfoMapper {

    private KasaSysInfoMapper() {
    }

    static KasaDiscoveryDto toDiscoveryDto(String ip, JsonNode sysInfo) {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp(ip);
        dto.setDeviceId(sysInfo.path("deviceId").asText(null));
        dto.setModel(sysInfo.path("model").asText(null));
        dto.setAlias(sysInfo.path("alias").asText(null));
        dto.setRelayState(sysInfo.path("relay_state").asInt(0) == 1);
        return dto;
    }
}
