package com.household.manager.communicator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.communicator.CommunicatorLogDtos.Row;
import com.household.manager.model.entity.CommunicatorRequest;
import com.household.manager.model.entity.CommunicatorRequest.Status;
import com.household.manager.repository.CommunicatorRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunicatorLogServiceTest {

    private final CommunicatorRequestRepository repository = mock(CommunicatorRequestRepository.class);
    private final CommunicatorLogService service = new CommunicatorLogService(repository, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-11-25T11:00:00Z"), ZoneId.of("Europe/Berlin")));

    @Test
    void schreibtKennzahlenUndVerlauf() throws Exception {
        CommunicatorTrace trace = CommunicatorTrace.voice(5000, "audio/mp4", 3000);
        trace.question("Wie warm ist es?");
        trace.claude(1, new AnthropicResponse("tool_use", List.of(), 10, 5, 900, 100), 1_000_000);
        trace.tool(1, "api_get", "{}", "x".repeat(5000), true, 1);
        trace.tool(1, "show_card", "{}", "falscher Zeiger", false, 1);
        trace.claude(2, new AnthropicResponse("end_turn", List.of(), 20, 7), 1_000_000);

        service.record(trace);

        ArgumentCaptor<CommunicatorRequest> saved = ArgumentCaptor.forClass(CommunicatorRequest.class);
        verify(repository).save(saved.capture());
        CommunicatorRequest request = saved.getValue();
        assertThat(List.of(request.getAiCalls(), request.getToolCalls(), request.getToolFailures()))
                .containsExactly(2, 2, 1);
        assertThat(List.of(request.getInputTokens(), request.getOutputTokens(), request.getCacheReadTokens(),
                request.getCacheWriteTokens())).containsExactly(30, 12, 900, 100);
        var step = new ObjectMapper().readTree(request.getDetails()).path("steps").get(1);
        assertThat(step.path("outputChars").asInt()).isEqualTo(5000);
        assertThat(step.path("output").asText()).hasSizeLessThan(4100);
    }

    @Test
    void einDatenbankfehlerKipptDieAntwortNicht() {
        when(repository.save(any())).thenThrow(new IllegalStateException("weg"));
        assertThatCode(() -> service.record(CommunicatorTrace.text())).doesNotThrowAnyException();
    }

    @Test
    void wertetWhisperOhneAusfaelleAus() {
        when(repository.findRowsSince(LocalDateTime.of(2026, 11, 19, 0, 0))).thenReturn(List.of(
                row(Status.OK, 1000, null), row(Status.NOT_UNDERSTOOD, 3000, "nichts"),
                row(Status.SPEECH_ERROR, 30000, "weg")));

        var summary = service.summary(7);

        assertThat(summary.requests()).isEqualTo(3);
        assertThat(summary.problems()).isEqualTo(2);
        assertThat(summary.speech().recordings()).isEqualTo(3);
        assertThat(summary.speech().failures()).isEqualTo(1);
        assertThat(summary.speech().notUnderstood()).isEqualTo(1);
        assertThat(summary.speech().maxMs()).isEqualTo(3000);
        assertThat(summary.speech().realtimeFactor()).isCloseTo(1.0, within(0.001));
        assertThat(summary.endpoints()).extracting(CommunicatorLogDtos.Count::count).containsExactly(6L, 3L);
        assertThat(summary.days()).hasSize(1);
    }

    private static Row row(Status status, int speechMs, String error) {
        return new Row(1L, LocalDateTime.of(2026, 11, 25, 9, 0), "a", true, status, "q", error, "m", 1, "audio/mp4",
                2000, speechMs, 5000, 1, 1, 0, 1, 1, 1, 1, "/v1/a\n/v1/a\n/v1/b");
    }
}
