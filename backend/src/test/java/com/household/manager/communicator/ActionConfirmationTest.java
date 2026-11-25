package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionConfirmationTest {

    private final WebInvocationPrivilegeEvaluator evaluator = mock(WebInvocationPrivilegeEvaluator.class);
    private ActionConfirmation confirmation;

    @BeforeEach
    void setUp() {
        confirmation = new ActionConfirmation(new ObjectProvider<>() {
            @Override
            public WebInvocationPrivilegeEvaluator getObject() {
                return evaluator;
            }
        }, new MockEnvironment());
        when(evaluator.isAllowed(any(), any(), any(), any())).thenReturn(true);
    }

    private boolean required(String method, String pattern, String body) throws Exception {
        JsonNode json = body == null ? null : new ObjectMapper().readTree(body);
        return confirmation.required(new ApiCatalog.Endpoint(method, PathPatternParser.defaultInstance.parse(pattern),
                "C", "o", List.of(), null, null), pattern, json);
    }

    @Test
    void loeschenUndHaustuerOeffnenBrauchenDenZweitenTipp() throws Exception {
        String lock = "/v1/nuki/locks/{smartlockId}/actions";
        assertThat(required("DELETE", "/v1/x", null)).isTrue();
        assertThat(required("POST", lock, "{\"action\":\"UNLATCH\"}")).isTrue();
        assertThat(required("POST", lock, "{\"action\":\"UNLOCK\"}")).isTrue();
        assertThat(required("POST", lock, null)).isTrue();
        assertThat(required("POST", lock, "{\"action\":\"LOCK\"}")).isFalse();
        assertThat(required("POST", "/v1/x", null)).isFalse();
    }

    @Test
    void adminAktionUndUnklareRechteBrauchenDenZweitenTipp() throws Exception {
        when(evaluator.isAllowed(any(), any(), any(), any())).thenReturn(false);
        assertThat(required("PUT", "/v1/x", null)).isTrue();
        when(evaluator.isAllowed(any(), any(), any(), any())).thenThrow(new UnsupportedOperationException());
        assertThat(required("PUT", "/v1/x", null)).isTrue();
    }

    @Test
    void dieHaustuerRegelTrifftDenEchtenEndpunkt() throws Exception {
        assertThat(com.household.manager.nuki.NukiController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class).value()[0]).startsWith("/v1/nuki");
        assertThat(java.util.Arrays.stream(com.household.manager.nuki.NukiController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .filter(java.util.Objects::nonNull).flatMap(a -> java.util.Arrays.stream(a.value())))
                .anyMatch(path -> path.endsWith("/{smartlockId}/actions"));
    }
}
