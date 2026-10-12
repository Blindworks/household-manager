package com.household.manager.agent;

import com.household.manager.model.entity.AgentProposal;
import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.CurrentUserService;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AgentSettingsService settingsService;
    @MockitoBean
    private AgentPolicyService policyService;
    @MockitoBean
    private AgentNoteService noteService;
    @MockitoBean
    private AgentProposalService proposalService;
    @MockitoBean
    private AgentRunRecorder runRecorder;
    @MockitoBean
    private AgentWakeService wakeService;
    @MockitoBean
    private AgentNotifier notifier;
    @MockitoBean
    private CurrentUserService currentUserService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void summaryLiefertDieDreiFelder() throws Exception {
        when(proposalService.countPending()).thenReturn(3L);
        when(settingsService.isActive()).thenReturn(true);
        when(notifier.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/v1/agent/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingProposals").value(3))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.telegramConfigured").value(false));
    }

    @Test
    void decideReichtDieEntscheidungDurchMitFallbackNamen() throws Exception {
        when(currentUserService.currentUserId()).thenReturn(Optional.empty());
        AgentProposal decided = AgentProposal.builder().id(7L).actionClass(ActionClass.SWITCH).target("switch.x")
                .actionJson("{}").reason("weil").status(ProposalStatus.EXECUTED).build();
        when(proposalService.decide(7L, ProposalDecision.APPROVE_AND_REMEMBER, "Admin")).thenReturn(decided);
        when(proposalService.actionOf(decided)).thenThrow(new IllegalStateException("kaputt"));

        mockMvc.perform(post("/v1/agent/proposals/7/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE_AND_REMEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.description").value("SWITCH switch.x"));

        verify(proposalService).decide(7L, ProposalDecision.APPROVE_AND_REMEMBER, "Admin");
    }

    @Test
    void decideOhneEntscheidungIst400() throws Exception {
        mockMvc.perform(post("/v1/agent/proposals/7/decide")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zuHaeufigerLaufIst429() throws Exception {
        when(wakeService.runNow()).thenThrow(new TooManyRequestsException("zu frueh"));

        mockMvc.perform(post("/v1/agent/runs")).andExpect(status().isTooManyRequests());
    }

    @Test
    void ausgeschalteterAgentBeimLaufIst400() throws Exception {
        when(wakeService.runNow()).thenThrow(new IllegalStateException("aus"));

        mockMvc.perform(post("/v1/agent/runs")).andExpect(status().isBadRequest());
    }

    @Test
    void manuellerLaufLiefertDasFrischeErgebnis() throws Exception {
        when(wakeService.runNow()).thenReturn(new AgentRunResult(true, "ok", null, 10, 5, 2, 1234L));

        mockMvc.perform(post("/v1/agent/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.triggerDetail").value("Manuell"))
                .andExpect(jsonPath("$.summary").value("ok"))
                .andExpect(jsonPath("$.inputTokens").value(10))
                .andExpect(jsonPath("$.toolCalls").value(2));
    }

    @Test
    void notizLoeschenRuftDeleteMitUserQuelle() throws Exception {
        mockMvc.perform(delete("/v1/agent/notes/5")).andExpect(status().isNoContent());

        verify(noteService).delete(5L, NoteSource.USER);
    }

    @Test
    void notizMitLeeremTextIst400() throws Exception {
        mockMvc.perform(post("/v1/agent/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PREFERENCE\",\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
