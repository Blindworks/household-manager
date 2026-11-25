package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.stereotype.Component;

/**
 * Entscheidet, welche Aktion einen zweiten Tipp braucht: Löschen, alles was ein MEMBER nicht darf (aus der
 * SecurityConfig, keine zweite Liste) und das Öffnen der Haustür. Im Zweifel: zweiter Tipp.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ActionConfirmation {

    /** Einzige Regel für ein einzelnes Feature: Verriegeln und Öffnen teilen sich einen Endpunkt. */
    private static final String LOCK_ACTIONS = "/v1/nuki/locks/{smartlockId}/actions";
    private static final Authentication MEMBER = UsernamePasswordAuthenticationToken.authenticated(
            "communicator", null, AuthorityUtils.createAuthorityList("ROLE_MEMBER"));

    private final ObjectProvider<WebInvocationPrivilegeEvaluator> evaluator;
    private final Environment environment;

    boolean required(ApiCatalog.Endpoint endpoint, String path, JsonNode body) {
        if ("DELETE".equals(endpoint.method())) {
            return true;
        }
        if (LOCK_ACTIONS.equals(endpoint.path())) {
            return body == null || !"LOCK".equals(body.path("action").asText());
        }
        return !memberMay(endpoint.method(), path);
    }

    private boolean memberMay(String method, String path) {
        try {
            String context = environment.getProperty("server.servlet.context-path", "");
            return evaluator.getObject().isAllowed(context, path, method, MEMBER);
        } catch (RuntimeException ex) {
            log.warn("Rechte fuer {} {} nicht pruefbar, zweiter Tipp noetig: {}", method, path, ex.toString());
            return false;
        }
    }
}
