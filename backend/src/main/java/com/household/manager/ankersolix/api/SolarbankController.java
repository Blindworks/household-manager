package com.household.manager.ankersolix.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SolarbankController {

    private final AnkerSolixClient client;
    private final ObjectMapper mapper;

    public SolarbankController(AnkerSolixClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * Liest die rohen Device-Parameter für einen beliebigen param_type.
     * Nützlich um unbekannte Felder zu entdecken (z.B. für Force-Discharge).
     *
     * @param siteId    Die Site-ID
     * @param paramType Der param_type (z.B. "4" für Schedule)
     * @return Die rohe API-Antwort als JsonNode
     */
    public JsonNode getRawDeviceParams(String siteId, String paramType) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("site_id", siteId);
        payload.put("param_type", paramType);
        return client.request(AnkerApiEndpoints.GET_DEVICE_PARM, payload);
    }

    /**
     * Prüft ob die erzwungene Batterie-Entladung aktiv ist.
     * Liest den "priority_discharge_switch" Wert aus den Ranges in param_data (param_type=4).
     * Gibt true zurück wenn mindestens ein Range den Switch aktiviert hat.
     *
     * @param siteId Die Site-ID
     * @return true wenn Force-Discharge aktiv, false sonst
     */
    public boolean isForceDischargeEnabled(String siteId) throws Exception {
        ObjectNode getPayload = mapper.createObjectNode();
        getPayload.put("site_id", siteId);
        getPayload.put("param_type", "4");
        JsonNode getResponse = client.request(AnkerApiEndpoints.GET_DEVICE_PARM, getPayload);

        String paramDataStr = getResponse.path("data").path("param_data").asText();
        JsonNode paramData = mapper.readTree(paramDataStr);

        ArrayNode ranges = (ArrayNode) paramData.path("ranges");
        for (JsonNode rangeNode : ranges) {
            if (rangeNode.path("priority_discharge_switch").asInt(0) == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aktiviert oder deaktiviert die erzwungene Batterie-Entladung.
     * Setzt "priority_discharge_switch" in jedem Range der param_data.
     *
     * @param siteId  Die Site-ID
     * @param enabled true zum Aktivieren, false zum Deaktivieren
     */
    public void setForceDischarge(String siteId, boolean enabled) throws Exception {
        ObjectNode getPayload = mapper.createObjectNode();
        getPayload.put("site_id", siteId);
        getPayload.put("param_type", "4");
        JsonNode getResponse = client.request(AnkerApiEndpoints.GET_DEVICE_PARM, getPayload);

        String paramDataStr = getResponse.path("data").path("param_data").asText();
        ObjectNode paramData = (ObjectNode) mapper.readTree(paramDataStr);

        int value = enabled ? 1 : 0;
        ArrayNode ranges = (ArrayNode) paramData.path("ranges");
        for (JsonNode rangeNode : ranges) {
            ((ObjectNode) rangeNode).put("priority_discharge_switch", value);
        }

        ObjectNode setPayload = mapper.createObjectNode();
        setPayload.put("site_id", siteId);
        setPayload.put("param_type", "4");
        setPayload.put("cmd", 17);
        setPayload.put("param_data", mapper.writeValueAsString(paramData));

        client.request(AnkerApiEndpoints.SET_DEVICE_PARM, setPayload);
    }

    /**
     * Setzt die Ausgangsleistung für ALLE Zeitfenster im Schedule.
     *
     * @param siteId Die Site-ID (aus GET_SITE_LIST)
     * @param watts  Gewünschte Ausgangsleistung in Watt
     */
    public void setOutputPower(String siteId, int watts) throws Exception {
        // 1. Aktuelle Parameter lesen
        ObjectNode getPayload = mapper.createObjectNode();
        getPayload.put("site_id", siteId);
        getPayload.put("param_type", "4");
        JsonNode getResponse = client.request(AnkerApiEndpoints.GET_DEVICE_PARM, getPayload);

        // param_data kommt als escaped JSON-String, muss extra geparst werden
        String paramDataStr = getResponse.path("data").path("param_data").asText();
        ObjectNode paramData = (ObjectNode) mapper.readTree(paramDataStr);

        // 2. Min/Max-Validierung
        int minLoad = paramData.path("min_load").asInt(0);
        int maxLoad = paramData.path("max_load").asInt(Integer.MAX_VALUE);
        if (watts != 0 && (watts < minLoad || watts > maxLoad)) {
            throw new IllegalArgumentException(
                    "Watt-Wert " + watts + " liegt außerhalb des erlaubten Bereichs ["
                    + minLoad + ", " + maxLoad + "]");
        }

        // 3. Alle Zeitfenster anpassen
        ArrayNode ranges = (ArrayNode) paramData.path("ranges");
        for (JsonNode rangeNode : ranges) {
            ObjectNode range = (ObjectNode) rangeNode;
            ArrayNode appLoads = (ArrayNode) range.path("appliance_loads");
            ArrayNode devLoads = (ArrayNode) range.path("device_power_loads");

            // Ratio VOR dem Überschreiben lesen, damit proportionale Anpassung korrekt ist
            if (appLoads.size() > 0 && devLoads.size() > 0) {
                int oldAppPower = appLoads.get(0).path("power").asInt(1);
                for (int i = 0; i < devLoads.size(); i++) {
                    int oldDevPower = devLoads.get(i).path("power").asInt(0);
                    int newDevPower = (oldAppPower > 0)
                            ? (int) Math.round((double) oldDevPower / oldAppPower * watts)
                            : watts / devLoads.size();
                    ((ObjectNode) devLoads.get(i)).put("power", newDevPower);
                }
            }

            if (appLoads.size() > 0) {
                ((ObjectNode) appLoads.get(0)).put("power", watts);
            }
        }

        // 4. Aktualisierte Parameter senden
        // cmd=17 ist für SB1/SB2/SB3 erforderlich; param_data als kompakter JSON-String
        ObjectNode setPayload = mapper.createObjectNode();
        setPayload.put("site_id", siteId);
        setPayload.put("param_type", "4");
        setPayload.put("cmd", 17);
        setPayload.put("param_data", mapper.writeValueAsString(paramData));

        client.request(AnkerApiEndpoints.SET_DEVICE_PARM, setPayload);
    }
}
