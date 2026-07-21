package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiSmartlockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NukiApiClientTest {

    private NukiApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        NukiProperties properties = new NukiProperties();
        properties.setApiToken("test-token");
        properties.setBaseUrl("https://api.nuki.io");
        client = new NukiApiClient(properties, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void listSmartlocksParsesResponseAndSendsBearerToken() {
        server.expect(requestTo("https://api.nuki.io/smartlock"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        [{"smartlockId": 17958143231, "name": "Haustür", "unknownField": true,
                          "state": {"state": 1, "doorState": 2, "batteryCritical": false,
                                    "batteryCharge": 85, "alsoUnknown": 1}}]
                        """, MediaType.APPLICATION_JSON));

        List<NukiSmartlockDto> locks = client.listSmartlocks();

        assertEquals(1, locks.size());
        assertEquals(17958143231L, locks.get(0).smartlockId());
        assertEquals("Haustür", locks.get(0).name());
        assertEquals(1, locks.get(0).state().state());
        server.verify();
    }

    @Test
    void sendActionPostsActionCode() {
        server.expect(requestTo("https://api.nuki.io/smartlock/17958143231/action"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.action").value(2))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertDoesNotThrow(() -> client.sendAction(17958143231L, 2));
        server.verify();
    }

    @Test
    void wrapsHttpErrorsInNukiException() {
        server.expect(requestTo("https://api.nuki.io/smartlock"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(NukiException.class, () -> client.listSmartlocks());
    }
}
