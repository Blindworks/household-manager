package com.household.manager.agent;

import com.household.manager.model.entity.AgentProposal;
import com.household.manager.repository.AgentProposalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentActionGateTest {

    @Mock AgentPolicyService policyService;
    @Mock AgentProposalService proposalService;
    @Mock AgentProposalRepository repository;

    private AgentActionGate gate() {
        return new AgentActionGate(policyService, proposalService, repository, new AgentProperties());
    }

    private static AgentAction heating(double temp) {
        return new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", temp), "Test", false);
    }

    private static AgentProposal row(long id, ProposalStatus status, String error) {
        AgentProposal p = new AgentProposal();
        p.setId(id);
        p.setStatus(status);
        p.setError(error);
        return p;
    }

    @Test
    void heatingAboveCorridorIsRefusedBeforeAnyPolicyLookup() {
        assertThrows(IllegalArgumentException.class, () -> gate().submit(heating(26.0)));
        verifyNoInteractions(policyService, proposalService);
    }

    @Test
    void heatingBelowCorridorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> gate().submit(heating(4.5)));
    }

    @Test
    void heatingNaNIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> gate().submit(heating(Double.NaN)));
    }

    @Test
    void corridorBoundsAreInclusive() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.DIRECT);
        when(proposalService.executeDirect(any())).thenReturn(row(1L, ProposalStatus.EXECUTED, null));
        gate().submit(heating(5.0));
        gate().submit(heating(24.0));
        verify(proposalService, times(2)).executeDirect(any());
    }

    @Test
    void unknownModeIsRefused() {
        AgentAction a = new AgentAction(ActionClass.HEATING, "3", Map.of("mode", "TURBO"), "r", false);
        assertThrows(IllegalArgumentException.class, () -> gate().submit(a));
    }

    @Test
    void blankReasonIsRefused() {
        AgentAction a = new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 20.0), " ", false);
        assertThrows(IllegalArgumentException.class, () -> gate().submit(a));
    }

    @Test
    void directPolicyExecutes() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.DIRECT);
        when(proposalService.executeDirect(any())).thenReturn(row(9L, ProposalStatus.EXECUTED, null));
        GateResult r = gate().submit(heating(21.0));
        assertTrue(r.executed());
        assertEquals(9L, r.proposalId());
        assertTrue(r.messageForModel().startsWith("Ausgeführt"));
        verify(proposalService, never()).propose(any());
        verifyNoInteractions(repository);
    }

    @Test
    void directFailureReturnsErrorText() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.DIRECT);
        when(proposalService.executeDirect(any())).thenReturn(row(9L, ProposalStatus.FAILED, "Hub offline"));
        GateResult r = gate().submit(heating(21.0));
        assertTrue(r.messageForModel().contains("Hub offline"));
        assertFalse(r.messageForModel().startsWith("Ausgeführt"));
    }

    @Test
    void proposePolicyCreatesProposal() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.PROPOSE);
        when(repository.existsByStatusAndTarget(ProposalStatus.PENDING, "3")).thenReturn(false);
        when(repository.countByStatus(ProposalStatus.PENDING)).thenReturn(0L);
        when(proposalService.propose(any())).thenReturn(row(4L, ProposalStatus.PENDING, null));
        GateResult r = gate().submit(heating(21.0));
        assertEquals(4L, r.proposalId());
        assertFalse(r.executed());
        assertTrue(r.messageForModel().contains("#4"));
    }

    @Test
    void duplicateOpenTargetIsBlocked() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.PROPOSE);
        when(repository.existsByStatusAndTarget(ProposalStatus.PENDING, "3")).thenReturn(true);
        GateResult r = gate().submit(heating(21.0));
        assertTrue(r.messageForModel().contains("bereits"));
        assertNull(r.proposalId());
        verify(proposalService, never()).propose(any());
    }

    @Test
    void openLimitIsBlocked() {
        when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.PROPOSE);
        when(repository.existsByStatusAndTarget(ProposalStatus.PENDING, "3")).thenReturn(false);
        when(repository.countByStatus(ProposalStatus.PENDING)).thenReturn(3L);
        GateResult r = gate().submit(heating(21.0));
        assertTrue(r.messageForModel().contains("bereits 3 Vorschläge"));
        verify(proposalService, never()).propose(any());
    }

    @Test
    void notifyIsDirect() {
        when(policyService.policyFor(ActionClass.NOTIFY)).thenReturn(ActionPolicy.DIRECT);
        when(proposalService.executeDirect(any())).thenReturn(row(1L, ProposalStatus.EXECUTED, null));
        gate().submit(new AgentAction(ActionClass.NOTIFY, "telegram",
                Map.of("channel", "telegram", "message", "Hi"), "r", false));
        verify(proposalService).executeDirect(any());
    }

    @Test
    void blankNotifyMessageIsRefused() {
        AgentAction a = new AgentAction(ActionClass.NOTIFY, "telegram", Map.of("message", " "), "r", false);
        assertThrows(IllegalArgumentException.class, () -> gate().submit(a));
    }

    @Test
    void invalidSwitchStateIsRefused() {
        AgentAction a = new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "toggle"), "r", false);
        assertThrows(IllegalArgumentException.class, () -> gate().submit(a));
    }
}
