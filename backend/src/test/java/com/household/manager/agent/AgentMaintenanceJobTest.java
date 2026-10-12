package com.household.manager.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentMaintenanceJobTest {

    private final AgentProposalService proposals = mock(AgentProposalService.class);
    private final AgentNoteService notes = mock(AgentNoteService.class);
    private final AgentMaintenanceJob job = new AgentMaintenanceJob(proposals, notes);

    @Test
    void throwingExpireStaleDoesNotStopTheOtherSteps() {
        doThrow(new RuntimeException("db weg")).when(proposals).expireStale();

        assertDoesNotThrow(job::minute);

        verify(proposals).deliverPending();
        verify(notes).purgeExpired();
    }

    @Test
    void throwingDeliverPendingDoesNotStopPurge() {
        doThrow(new RuntimeException("db weg")).when(proposals).deliverPending();

        assertDoesNotThrow(job::minute);

        verify(proposals).expireStale();
        verify(notes).purgeExpired();
    }

    @Test
    void throwingPurgeDoesNotEscape() {
        doThrow(new RuntimeException("db weg")).when(notes).purgeExpired();

        assertDoesNotThrow(job::minute);

        verify(proposals).expireStale();
        verify(proposals).deliverPending();
    }
}
