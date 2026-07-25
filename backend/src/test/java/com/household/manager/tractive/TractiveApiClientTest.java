package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveTokenDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TractiveApiClientTest {

    private TractiveApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        TractiveProperties properties = new TractiveProperties();
        properties.setBaseUrl("https://graph.tractive.com/4");
        client = new TractiveApiClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void loginSendsCredentialsAndClientHeader() {
        server.expect(requestTo("https://graph.tractive.com/4/auth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-tractive-client", "625e533dc3c3b41c28a669f0"))
                .andExpect(jsonPath("$.platform_email").value("halter@example.com"))
                .andExpect(jsonPath("$.platform_token").value("geheim"))
                .andExpect(jsonPath("$.grant_type").value("tractive"))
                .andRespond(withSuccess("""
                        {"user_id": "u-1", "access_token": "tok-1",
                         "expires_at": 1800000000, "unknownField": true}
                        """, MediaType.APPLICATION_JSON));

        TractiveTokenDto token = client.login("halter@example.com", "geheim");

        assertEquals("u-1", token.userId());
        assertEquals("tok-1", token.accessToken());
        assertEquals(1800000000L, token.expiresAt());
        server.verify();
    }

    @Test
    void wrapsUnauthorizedInTractiveException() {
        server.expect(requestTo("https://graph.tractive.com/4/auth/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(TractiveAuthException.class, () -> client.login("a@b.de", "falsch"));
    }

    @Test
    void serverErrorIsNotTreatedAsBadCredentials() {
        server.expect(requestTo("https://graph.tractive.com/4/auth/token"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        TractiveException ex = assertThrows(TractiveException.class,
                () -> client.login("a@b.de", "geheim"));
        assertFalse(ex instanceof TractiveAuthException);
    }

    @Test
    void listTrackableObjectsSendsAuthHeaders() {
        server.expect(requestTo("https://graph.tractive.com/4/user/u-1/trackable_objects"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(header("x-tractive-user", "u-1"))
                .andExpect(header("x-tractive-client", "625e533dc3c3b41c28a669f0"))
                .andRespond(withSuccess("""
                        [{"_id": "trk-1"}, {"_id": "trk-2"}]
                        """, MediaType.APPLICATION_JSON));

        var refs = client.listTrackableObjects("tok-1", "u-1");

        assertEquals(2, refs.size());
        assertEquals("trk-1", refs.get(0).id());
        server.verify();
    }

    @Test
    void trackableDetailsParseNameAndDeviceId() {
        server.expect(requestTo("https://graph.tractive.com/4/trackable_object/trk-1"))
                .andRespond(withSuccess("""
                        {"_id": "trk-1", "device_id": "dev-9",
                         "details": {"name": "Bello", "pet_type": "DOG"}, "extra": 1}
                        """, MediaType.APPLICATION_JSON));

        var trackable = client.getTrackable("tok-1", "u-1", "trk-1");

        assertEquals("dev-9", trackable.deviceId());
        assertEquals("Bello", trackable.details().name());
        server.verify();
    }

    @Test
    void positionReportParsesLatLong() {
        server.expect(requestTo("https://graph.tractive.com/4/device_pos_report/dev-9"))
                .andRespond(withSuccess("""
                        {"latlong": [48.2082, 16.3738], "accuracy": 12,
                         "sensor_used": "GPS", "time": 1800000000, "extra": true}
                        """, MediaType.APPLICATION_JSON));

        var position = client.getPosition("tok-1", "u-1", "dev-9");

        assertEquals(48.2082, position.latitude());
        assertEquals(16.3738, position.longitude());
        assertEquals("GPS", position.sensorUsed());
        server.verify();
    }

    @Test
    void hardwareReportParsesBatteryAndCharging() {
        server.expect(requestTo("https://graph.tractive.com/4/device_hw_report/dev-9/"))
                .andRespond(withSuccess("""
                        {"battery_level": 87, "charging_state": "CHARGING", "time": 1800000000}
                        """, MediaType.APPLICATION_JSON));

        var hardware = client.getHardware("tok-1", "u-1", "dev-9");

        assertEquals(87, hardware.batteryLevel());
        assertTrue(hardware.isCharging());
        server.verify();
    }

    @Test
    void geofencesAreParsedIntoZones() {
        server.expect(requestTo("https://graph.tractive.com/4/tracker/dev-9/geofences"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"name": "Garten", "active": true,
                          "shape": {"type": "circle", "center": [48.2082, 16.3738], "radius": 120}}]
                        """, MediaType.APPLICATION_JSON));

        var fences = client.listGeofences("tok-1", "u-1", "dev-9");

        assertEquals(1, fences.size());
        var zone = fences.get(0).toZone().orElseThrow();
        assertEquals("Garten", zone.name());
        assertEquals(120, zone.radiusMeters());
        server.verify();
    }

    @Test
    void inactiveOrNonCircularGeofencesAreIgnored() {
        var inactive = new com.household.manager.tractive.dto.TractiveGeofenceDto(
                "Aus", false, new com.household.manager.tractive.dto.TractiveGeofenceDto.Shape(
                        "circle", java.util.List.of(48.0, 16.0), 100.0));
        var withoutRadius = new com.household.manager.tractive.dto.TractiveGeofenceDto(
                "Polygon", true, new com.household.manager.tractive.dto.TractiveGeofenceDto.Shape(
                        "polygon", java.util.List.of(48.0, 16.0), null));

        assertTrue(inactive.toZone().isEmpty());
        assertTrue(withoutRadius.toZone().isEmpty());
    }
}
