package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentActionPolicyEntity;
import com.household.manager.repository.AgentActionPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Einzige Definition der Freigabe je Aktionsklasse. Fail-safe: eine fehlende Zeile oder
 * ein Lesefehler ergibt PROPOSE (der Agent darf nur vorschlagen), nie versehentlich DIRECT.
 * NOTIFY ist fest DIRECT, weil eine Nachricht keinen Zustand im Haus aendert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentPolicyService {

    private final AgentActionPolicyRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public ActionPolicy policyFor(ActionClass actionClass) {
        if (actionClass == ActionClass.NOTIFY) {
            return ActionPolicy.DIRECT;
        }
        try {
            return repository.findById(actionClass)
                    .map(AgentActionPolicyEntity::getPolicy)
                    .orElse(ActionPolicy.PROPOSE);
        } catch (Exception ex) {
            log.warn("Freigabe fuer {} nicht lesbar, nutze PROPOSE", actionClass, ex);
            return ActionPolicy.PROPOSE;
        }
    }

    public Map<ActionClass, ActionPolicy> all() {
        Map<ActionClass, ActionPolicy> result = new EnumMap<>(ActionClass.class);
        for (ActionClass actionClass : ActionClass.values()) {
            result.put(actionClass, policyFor(actionClass));
        }
        return result;
    }

    public void update(ActionClass actionClass, ActionPolicy policy) {
        if (actionClass == ActionClass.NOTIFY) {
            throw new IllegalArgumentException("NOTIFY ist immer DIRECT.");
        }
        repository.save(AgentActionPolicyEntity.builder()
                .actionClass(actionClass).policy(policy).updatedAt(LocalDateTime.now(clock)).build());
        auditService.record("agent.policy.update", actionClass + "=" + policy);
    }
}
