package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.kasa.dto.KasaDiscoveryDto;

/**
 * Shared mapping from a Kasa {@code system.get_sysinfo} JSON node to
 * {@link KasaDiscoveryDto}. Used both by broadcast discovery
 * ({@link KasaDiscoveryService}) and unicast probing ({@link KasaService#probe(String)}),
 * which receive the identical sysinfo payload shape, so the field extraction
 * exists in exactly one place.
 * <p>
 * The two callers hand this a differently-guaranteed {@code sysInfo} node, on purpose:
 * {@code probe()} validates first and never calls here with a missing node, while
 * {@link KasaDiscoveryService#discover} passes a possibly-missing node as-is, which yields an
 * all-null DTO here rather than an exception — a broadcast reply from a non-Kasa device on the
 * same network must not abort the whole discovery scan.
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
