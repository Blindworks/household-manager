package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;

/**
 * Shared mapping from a Kasa {@code system.get_sysinfo} JSON node to
 * {@link KasaDiscoveryDto}/{@link KasaStatusDto}. Used both by broadcast discovery
 * ({@link KasaDiscoveryService}) and unicast probing/status
 * ({@link KasaService#probe(String)}/{@link KasaService#getStatus(String)}), which receive the
 * identical sysinfo payload shape, so the field extraction exists in exactly one place.
 * <p>
 * The two callers hand this a differently-guaranteed {@code sysInfo} node, on purpose:
 * {@code probe()}/{@code getStatus()} validate first and never call here with a missing node,
 * while {@link KasaDiscoveryService#discover} passes a possibly-missing node as-is, which yields
 * an all-null/all-default DTO here rather than an exception — a broadcast reply from a non-Kasa
 * device on the same network must not abort the whole discovery scan.
 * <p>
 * <b>Plug vs. bulb:</b> a plug's sysinfo has {@code relay_state} and no {@code light_state} node;
 * a bulb's sysinfo has {@code light_state} and no {@code relay_state} at all (verified against the
 * real KL110, 192.168.1.101, 2026-08-18). Presence of an OBJECT {@code light_state} node is
 * therefore the structural discriminator used both for {@link KasaDiscoveryDto#isBulb()} and for
 * choosing where the on/off flag and current light values are read from.
 */
final class KasaSysInfoMapper {

    private KasaSysInfoMapper() {
    }

    static KasaDiscoveryDto toDiscoveryDto(String ip, JsonNode sysInfo) {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp(ip);
        dto.setDeviceId(sysInfo.path("deviceId").asText(null));
        dto.setModel(sysInfo.path("model").asText(null));
        dto.setAlias(trimAlias(sysInfo.path("alias").asText(null)));
        dto.setBulb(isBulb(sysInfo));
        dto.setRelayState(isOn(sysInfo));
        dto.setCapabilities(KasaCapabilityMapper.deriveCapabilities(sysInfo));

        JsonNode lightValues = lightValuesSource(sysInfo);
        dto.setBrightness(intOrNull(lightValues, "brightness"));
        dto.setHue(intOrNull(lightValues, "hue"));
        dto.setSaturation(intOrNull(lightValues, "saturation"));
        dto.setColorTemp(intOrNull(lightValues, "color_temp"));
        return dto;
    }

    static KasaStatusDto toStatusDto(JsonNode sysInfo) {
        KasaDiscoveryDto discovery = toDiscoveryDto(null, sysInfo);
        return new KasaStatusDto(
                discovery.isRelayState(),
                discovery.getAlias(),
                discovery.getDeviceId(),
                discovery.isBulb(),
                discovery.getCapabilities(),
                discovery.getBrightness(),
                discovery.getHue(),
                discovery.getSaturation(),
                discovery.getColorTemp()
        );
    }

    /**
     * TP-Link device names frequently carry a trailing space (the real KL110 reports
     * {@code "Treppenhaus "}, see the class-level javadoc and the CLAUDE.md note about Blink
     * camera names showing the same pattern) — trimmed here, once, for every caller.
     */
    private static String trimAlias(String alias) {
        return alias == null ? null : alias.trim();
    }

    private static boolean isBulb(JsonNode sysInfo) {
        return sysInfo.path("light_state").isObject();
    }

    /**
     * A bulb has no {@code relay_state} at all; its on/off flag lives at
     * {@code light_state.on_off}. A plug has no {@code light_state}, so this falls through to the
     * unchanged {@code relay_state} read for the existing plug behaviour.
     */
    private static boolean isOn(JsonNode sysInfo) {
        JsonNode lightState = sysInfo.path("light_state");
        if (lightState.isObject() && lightState.has("on_off")) {
            return lightState.path("on_off").asInt(0) == 1;
        }
        return sysInfo.path("relay_state").asInt(0) == 1;
    }

    /**
     * Resolves which JSON node current brightness/hue/saturation/colour-temperature values
     * should be read from: when the bulb is ON, its {@code light_state} node carries them
     * directly; when it is OFF, {@code light_state} contains only {@code on_off} and
     * {@code dft_on_state} — the value the bulb will resume when switched back on — so that is
     * read instead. A plug (no {@code light_state} object at all) yields a missing node, so every
     * field read against it comes back {@code null} rather than throwing.
     */
    private static JsonNode lightValuesSource(JsonNode sysInfo) {
        JsonNode lightState = sysInfo.path("light_state");
        if (!lightState.isObject()) {
            return MissingNode.getInstance();
        }
        boolean on = lightState.path("on_off").asInt(0) == 1;
        if (!on) {
            JsonNode dftOnState = lightState.path("dft_on_state");
            if (dftOnState.isObject()) {
                return dftOnState;
            }
        }
        return lightState;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.has(field) ? node.path(field).asInt() : null;
    }
}
