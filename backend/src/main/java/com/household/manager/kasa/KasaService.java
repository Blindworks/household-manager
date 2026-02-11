package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KasaService {

    private static final String TURN_ON_PAYLOAD = "{\"system\":{\"set_relay_state\":{\"state\":1}}}";
    private static final String TURN_OFF_PAYLOAD = "{\"system\":{\"set_relay_state\":{\"state\":0}}}";
    private static final String GET_STATUS_PAYLOAD = "{\"system\":{\"get_sysinfo\":{}}}";

    private final KasaTcpClient kasaTcpClient;
    private final ObjectMapper objectMapper;

    public void turnOn(String ip) {
        log.info("Sending Kasa command turnOn to {}", ip);
        kasaTcpClient.send(ip, TURN_ON_PAYLOAD);
    }

    public void turnOff(String ip) {
        log.info("Sending Kasa command turnOff to {}", ip);
        kasaTcpClient.send(ip, TURN_OFF_PAYLOAD);
    }

    public KasaStatusDto getStatus(String ip) {
        log.info("Sending Kasa command getStatus to {}", ip);
        String response = kasaTcpClient.send(ip, GET_STATUS_PAYLOAD);
        return parseStatusResponse(ip, response);
    }

    private KasaStatusDto parseStatusResponse(String ip, String response) {
        try {
            JsonNode sysInfo = objectMapper.readTree(response)
                    .path("system")
                    .path("get_sysinfo");

            if (sysInfo.isMissingNode()) {
                throw new KasaCommunicationException("Kasa response from " + ip + " does not contain system.get_sysinfo");
            }

            int relayStateRaw = sysInfo.path("relay_state").asInt(0);
            String alias = sysInfo.path("alias").asText(null);
            String deviceId = sysInfo.path("deviceId").asText(null);
            return new KasaStatusDto(relayStateRaw == 1, alias, deviceId);
        } catch (KasaCommunicationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KasaCommunicationException("Failed to parse Kasa status response from " + ip, ex);
        }
    }
}
