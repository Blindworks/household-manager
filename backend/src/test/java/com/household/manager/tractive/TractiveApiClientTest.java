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

        assertThrows(TractiveException.class, () -> client.login("a@b.de", "falsch"));
    }
}
