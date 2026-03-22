package com.household.manager.ankersolix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.ankersolix.api.AnkerApiEndpoints;
import com.household.manager.ankersolix.api.AnkerSolixClient;
import com.household.manager.ankersolix.api.SolarbankController;
import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import com.household.manager.ankersolix.dto.AnkerSolixEnergyDayDto;
import com.household.manager.ankersolix.dto.AnkerSolixLiveDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Spring-managed service wrapping the Anker Solix Cloud API.
 * The underlying HTTP client and controller are created lazily after
 * property injection via {@link PostConstruct}.
 */
@Service
@ConditionalOnProperty(name = "ankersolix.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class AnkerSolixService {

    @Value("${ankersolix.user}")
    private String email;

    @Value("${ankersolix.password}")
    private String password;

    @Value("${ankersolix.country:DE}")
    private String country;

    private AnkerSolixClient client;
    private ObjectMapper mapper;
    private SolarbankController solarbankController;

    /** Cached site ID – fetched once on first access. Volatile for safe lazy-init across threads. */
    private volatile String cachedSiteId;

    @PostConstruct
    void init() {
        client = new AnkerSolixClient(email, password, country);
        mapper = new ObjectMapper();
        solarbankController = new SolarbankController(client, mapper);
        log.info("AnkerSolixService initialised (country={})", country);
    }

    /**
     * Returns the site ID for the account's first power system.
     * The result is cached after the first successful API call.
     */
    public String getSiteId() {
        if (cachedSiteId != null) {
            return cachedSiteId;
        }
        synchronized (this) {
            if (cachedSiteId != null) {
                return cachedSiteId;
            }
            try {
                JsonNode response = client.request(AnkerApiEndpoints.GET_SITE_LIST);
                cachedSiteId = response.path("data").path("site_list").get(0).path("site_id").asText();
                log.info("Resolved site ID: {}", cachedSiteId);
                return cachedSiteId;
            } catch (Exception ex) {
                log.error("Failed to fetch site ID: {}", ex.getMessage(), ex);
                throw new RuntimeException("Failed to fetch Anker Solix site ID", ex);
            }
        }
    }

    /**
     * Returns the raw GET_SITE_HOMEPAGE response for field-name discovery.
     * Remove once AnkerSolixService field mapping is confirmed correct.
     */
    public JsonNode getRawHomepage() {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());
            return client.request(AnkerApiEndpoints.GET_SITE_HOMEPAGE, payload);
        } catch (Exception ex) {
            log.error("Failed to fetch raw homepage: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch raw homepage", ex);
        }
    }

    /**
     * Returns the raw GET_SCENE_INFO response for field-name discovery.
     * Remove once AnkerSolixService field mapping is confirmed correct.
     */
    public JsonNode getRawSceneInfo() {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());
            return client.request(AnkerApiEndpoints.GET_SCENE_INFO, payload);
        } catch (Exception ex) {
            log.error("Failed to fetch raw scene info: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch raw scene info", ex);
        }
    }

    /** Returns raw GET_SYSTEM_INFO for device discovery. */
    public JsonNode getRawSystemInfo() {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());
            return client.request(AnkerApiEndpoints.GET_SYSTEM_INFO, payload);
        } catch (Exception ex) {
            log.error("Failed to fetch raw system info: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch raw system info", ex);
        }
    }

    /**
     * Fetches the live power flow snapshot via GET_SCENE_INFO.
     * All power values in the API response are strings (e.g. "401") and must be parsed.
     * Battery SOC is a decimal fraction (e.g. "0.75" = 75 %).
     */
    public AnkerSolixLiveDto getLiveData() {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());

            JsonNode data = client.request(AnkerApiEndpoints.GET_SCENE_INFO, payload).path("data");
            log.debug("GET_SCENE_INFO data: {}", data);

            JsonNode sbInfo   = data.path("solarbank_info");
            JsonNode gridInfo = data.path("grid_info");

            // PV generation – total across all solarbanks (string watts)
            double pvPowerW = parseWatt(sbInfo.path("total_photovoltaic_power"));

            // Battery SOC – "total_battery_power" is a decimal fraction 0..1 → convert to %
            double batteryFraction = sbInfo.path("total_battery_power").asDouble(0.0);
            int batteryPercent = (int) Math.round(batteryFraction * 100);

            // Battery net power: positive = charging, negative = discharging
            double chargingW    = parseWatt(sbInfo.path("total_charging_power"));
            double dischargingW = parseWatt(sbInfo.path("total_output_power"));
            double batteryPowerW = chargingW > 0 ? chargingW : -dischargingW;

            // Grid: positive = import, negative = export (PV to grid)
            double gridToHomeW  = parseWatt(gridInfo.path("grid_to_home_power"));
            double pvToGridW    = parseWatt(gridInfo.path("photovoltaic_to_grid_power"));
            double gridPowerW   = gridToHomeW > 0 ? gridToHomeW : -pvToGridW;

            // Home consumption
            double homePowerW = parseWatt(data.path("home_load_power"));

            return AnkerSolixLiveDto.builder()
                    .pvPowerW(pvPowerW)
                    .batteryPercent(batteryPercent)
                    .batteryPowerW(batteryPowerW)
                    .gridPowerW(gridPowerW)
                    .homePowerW(homePowerW)
                    .timestamp(LocalDateTime.now())
                    .build();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to fetch live data: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch Anker Solix live data", ex);
        }
    }

    /** Parses a JsonNode containing a watt value as a string or number. Returns 0.0 on missing/blank. */
    private static double parseWatt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0.0;
        String text = node.asText("0").trim();
        if (text.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Fetches the daily energy breakdown via energy_analysis endpoint.
     * Values are returned as strings (kWh) and must be parsed.
     */
    public AnkerSolixEnergyDayDto getEnergyDay(LocalDate date) {
        try {
            String dateStr = date.toString(); // YYYY-MM-DD

            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());
            payload.put("device_sn", "");
            payload.put("device_type", "solarbank");
            payload.put("type", "day");
            payload.put("start_time", dateStr);
            payload.put("end_time", dateStr);

            JsonNode data = client.request(AnkerApiEndpoints.ENERGY_ANALYSIS, payload).path("data");
            log.debug("ENERGY_ANALYSIS data: {}", data);

            double pvEnergyKwh         = parseKwh(data.path("solar_total"));
            double batteryChargeKwh    = parseKwh(data.path("solar_to_battery_total"));
            double batteryDischargeKwh = parseKwh(data.path("battery_discharging_total"));

            return AnkerSolixEnergyDayDto.builder()
                    .date(date)
                    .pvEnergyKwh(pvEnergyKwh)
                    .batteryChargeKwh(batteryChargeKwh)
                    .batteryDischargeKwh(batteryDischargeKwh)
                    .build();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to fetch energy day data for {}: {}", date, ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch Anker Solix energy day data", ex);
        }
    }

    private static double parseKwh(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0.0;
        String text = node.asText("0").trim();
        if (text.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Fetches the current device operating parameters (min/max/current output).
     */
    public AnkerSolixDeviceParamDto getDeviceParams() {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("site_id", getSiteId());
            payload.put("param_type", "4");

            JsonNode response = client.request(AnkerApiEndpoints.GET_DEVICE_PARM, payload);

            // param_data arrives as an escaped JSON string – parse it first
            String paramDataStr = response.path("data").path("param_data").asText();
            JsonNode paramData = mapper.readTree(paramDataStr);

            if (log.isDebugEnabled()) {
                log.debug("GET_DEVICE_PARM param_data fields: {}", paramData.toString());
            }

            int minLoad     = paramData.path("min_load").asInt(0);
            int maxLoad     = paramData.path("max_load").asInt(0);
            int currentLoad = paramData.path("current_load").asInt(0);

            // Fallback: read first appliance_load power from the first schedule range
            if (currentLoad == 0) {
                JsonNode firstRange = paramData.path("ranges").get(0);
                if (firstRange != null) {
                    JsonNode firstLoad = firstRange.path("appliance_loads").get(0);
                    if (firstLoad != null) {
                        currentLoad = firstLoad.path("power").asInt(0);
                    }
                }
            }

            return AnkerSolixDeviceParamDto.builder()
                    .minLoadW(minLoad)
                    .maxLoadW(maxLoad)
                    .currentOutputW(currentLoad)
                    .build();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to fetch device params: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to fetch Anker Solix device params", ex);
        }
    }

    /**
     * Sets the output power on the solarbank for all schedule time slots.
     *
     * @param watts desired output power in watts
     */
    public void setOutputPower(int watts) {
        try {
            solarbankController.setOutputPower(getSiteId(), watts);
            log.info("Output power set to {} W", watts);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to set output power to {} W: {}", watts, ex.getMessage(), ex);
            throw new RuntimeException("Failed to set Anker Solix output power", ex);
        }
    }
}
