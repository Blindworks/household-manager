package com.household.manager.meross.lib;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the vendored Meross MQTT library: real devices answer with
 * additional header/payload fields and the cloud device list carries no channel
 * metadata, both of which previously broke switching plugs on/off.
 */
class MerossMqttResponseHandlingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Geraete-Antworten mit unbekannten Header-Feldern (triggerSrc, uuid) werden geparst")
    void parsesMessageWithUnknownHeaderFields() {
        String json = """
                {"header":{"messageId":"a8fd756033c7a8b0d16494719e7a7dee",
                           "namespace":"Appliance.Control.ToggleX",
                           "method":"SETACK",
                           "payloadVersion":1,
                           "from":"/appliance/1903115105716739080834298f1c9c6e/publish",
                           "uuid":"1903115105716739080834298f1c9c6e",
                           "triggerSrc":"CloudControl",
                           "timestamp":1555613748,
                           "timestampMs":635,
                           "sign":"ac3482ede0243265e9f79166e94d2b1c"},
                 "payload":{"togglex":[{"channel":0,"onoff":1,"lmTime":1555613747}]}}
                """;

        Message message = assertDoesNotThrow(() -> MAPPER.readValue(json, Message.class));

        assertEquals("SETACK", message.getHeader().getMethod());
        assertEquals("Appliance.Control.ToggleX", message.getHeader().getNamespace());
    }

    @Test
    @DisplayName("Appliance.System.All-Antworten mit unbekannten verschachtelten Feldern werden konvertiert")
    void convertsSystemAllResponseWithUnknownNestedFields() throws Exception {
        String json = """
                {"all":{"system":{"hardware":{"type":"mss310","subType":"un","version":"2.0.0",
                                              "chipType":"mt7682","uuid":"x","macAddress":"aa:bb"},
                                  "firmware":{"version":"2.1.17","compileTime":"2019","wifiMac":"cc:dd",
                                              "innerIp":"192.168.0.2","server":"mqtt-eu.meross.com","port":2001,
                                              "userId":1,"secondServer":"mqtt-eu-2.meross.com","secondPort":443},
                                  "online":{"status":1,"bindId":"abc","who":1},
                                  "time":{"timestamp":1,"timezone":"Europe/Berlin","timeRule":[]}},
                        "digest":{"togglex":[{"channel":0,"onoff":1,"lmTime":1}],"triggerx":[],"timerx":[]},
                        "control":{}}}
                """;
        Map<?, ?> payload = MAPPER.readValue(json, Map.class);

        NetworkDevice device = assertDoesNotThrow(() -> MAPPER.convertValue(payload, NetworkDevice.class));

        assertEquals(1, device.getAll().getDigest().getTogglex().size());
        assertTrue(device.getAll().getSystem().isOnline());
    }

    @Test
    @DisplayName("Togglex-Status wird auch ohne Channel-Metadaten aus der Cloud initialisiert")
    void initializesChannelStatesWhenCloudDeviceListsNoChannels() throws Exception {
        Device device = new Device();
        device.setUuid("1903115105716739080834298f1c9c6e");
        AttachedDevice attachedDevice = new AttachedDevice(device, "token", "key", 1L);
        MerossDevice merossDevice = new MerossDevice(attachedDevice, new MqttConnection());

        Method initStates = MerossDevice.class.getDeclaredMethod("initStatesFromTogglexList", List.class);
        initStates.setAccessible(true);

        assertDoesNotThrow(() -> initStates.invoke(merossDevice, List.of(Map.of("channel", 0, "onoff", 1))));

        Field stateField = MerossDevice.class.getDeclaredField("state");
        stateField.setAccessible(true);
        boolean[] state = (boolean[]) stateField.get(merossDevice);
        assertTrue(state[0]);
    }

    @Test
    @DisplayName("Fehler beim Senden eines Kommandos werden nicht verschluckt")
    void executecmdPropagatesPublishFailures() {
        MqttConnection connection = new MqttConnection();

        assertThrows(MQTTException.class,
                () -> connection.executecmd("SET", "Appliance.Control.ToggleX", Map.of(), "/appliance/x/subscribe"));
    }

    @Test
    @DisplayName("receiveMessage laeuft in einen Timeout statt endlos zu blockieren")
    void receiveMessageTimesOutInsteadOfBlockingForever() throws Exception {
        MqttConnection connection = new MqttConnection();
        Mqtt3BlockingClient client = mock(Mqtt3BlockingClient.class);
        Mqtt3BlockingClient.Mqtt3Publishes publishes = mock(Mqtt3BlockingClient.Mqtt3Publishes.class);
        when(client.publishes(any())).thenReturn(publishes);
        when(publishes.receive(anyLong(), any(TimeUnit.class))).thenReturn(Optional.empty());

        Field clientField = MqttConnection.class.getDeclaredField("blockingClient");
        clientField.setAccessible(true);
        clientField.set(connection, client);

        assertThrows(MQTTException.class, connection::receiveMessage);
    }
}
