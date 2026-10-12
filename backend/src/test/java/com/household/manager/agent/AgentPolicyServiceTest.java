package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.repository.AgentActionPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentPolicyServiceTest {
    @Mock AgentActionPolicyRepository repository;
    @Mock AuditService auditService;
    Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    AgentPolicyService service() { return new AgentPolicyService(repository, auditService, clock); }

    @Test void missingRowMeansPropose() {
        when(repository.findById(ActionClass.HEATING)).thenReturn(Optional.empty());
        assertEquals(ActionPolicy.PROPOSE, service().policyFor(ActionClass.HEATING));
    }
    @Test void notifyIsAlwaysDirectWithoutRepositoryAccess() {
        assertEquals(ActionPolicy.DIRECT, service().policyFor(ActionClass.NOTIFY));
        verifyNoInteractions(repository);
    }
    @Test void repositoryFailureFallsBackToPropose() {
        when(repository.findById(any())).thenThrow(new RuntimeException("db"));
        assertEquals(ActionPolicy.PROPOSE, service().policyFor(ActionClass.SWITCH));
    }
    @Test void updateRefusesNotify() {
        assertThrows(IllegalArgumentException.class, () -> service().update(ActionClass.NOTIFY, ActionPolicy.PROPOSE));
        verifyNoInteractions(repository);
    }
    @Test void updateSavesAndAudits() {
        service().update(ActionClass.SWITCH, ActionPolicy.DIRECT);
        verify(repository).save(argThat(e -> e.getActionClass() == ActionClass.SWITCH && e.getPolicy() == ActionPolicy.DIRECT));
        verify(auditService).record("agent.policy.update", "SWITCH=DIRECT");
    }
}
