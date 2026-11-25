package com.household.manager.communicator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.communicator.CommunicatorLogDtos.Count;
import com.household.manager.communicator.CommunicatorLogDtos.Day;
import com.household.manager.communicator.CommunicatorLogDtos.Detail;
import com.household.manager.communicator.CommunicatorLogDtos.Row;
import com.household.manager.communicator.CommunicatorLogDtos.RowPage;
import com.household.manager.communicator.CommunicatorLogDtos.SpeechStats;
import com.household.manager.communicator.CommunicatorLogDtos.Step;
import com.household.manager.communicator.CommunicatorLogDtos.Summary;
import com.household.manager.communicator.CommunicatorLogDtos.Tokens;
import com.household.manager.model.entity.CommunicatorRequest;
import com.household.manager.model.entity.CommunicatorRequest.Status;
import com.household.manager.repository.CommunicatorRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Protokoll der Communicator-Anfragen: schreiben (wirft nie), auswerten, nach 30 Tagen loeschen. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicatorLogService {

    static final int RETENTION_DAYS = 30;
    static final int MAX_PAGE_SIZE = 100;
    private static final int TOP = 10;

    private final CommunicatorRequestRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Eine gescheiterte Protokollzeile darf die Antwort nicht verhindern. */
    void record(CommunicatorTrace trace) {
        try {
            repository.save(toEntity(trace));
        } catch (Exception ex) {
            log.warn("Communicator-Anfrage nicht protokolliert: {}", ex.getMessage());
        }
    }

    private CommunicatorRequest toEntity(CommunicatorTrace trace) throws JsonProcessingException {
        List<Step> steps = trace.getSteps();
        int totalMs = trace.totalMs();
        CommunicatorRequest request = new CommunicatorRequest();
        request.setAskedAt(LocalDateTime.now(clock).minus(totalMs, ChronoUnit.MILLIS)
                .truncatedTo(ChronoUnit.SECONDS));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        request.setActor(auth == null ? null : limit(auth.getName(), 100));
        request.setVoice(trace.isVoice());
        request.setStatus(trace.getStatus());
        request.setQuestion(limit(trace.getQuestion(), 1000));
        request.setError(limit(trace.getError(), 500));
        request.setModel(limit(trace.getModel(), 100));
        request.setAudioBytes(trace.getAudioBytes());
        request.setAudioType(limit(trace.getAudioType(), 100));
        request.setAudioMs(trace.getAudioMs());
        request.setSpeechMs(trace.getSpeechMs());
        request.setTotalMs(totalMs);
        request.setAiCalls((int) steps.stream().filter(s -> "CLAUDE".equals(s.type())).count());
        request.setToolCalls((int) steps.stream().filter(s -> "TOOL".equals(s.type())).count());
        request.setToolFailures((int) steps.stream().filter(s -> "TOOL".equals(s.type()) && !s.ok()).count());
        request.setInputTokens(sum(steps, Step::inputTokens));
        request.setOutputTokens(sum(steps, Step::outputTokens));
        request.setCacheReadTokens(sum(steps, Step::cacheReadTokens));
        request.setCacheWriteTokens(sum(steps, Step::cacheWriteTokens));
        request.setEndpoints(limit(String.join("\n", trace.getEndpoints()), 2000));
        request.setDetails(objectMapper.writeValueAsString(Map.of("steps", steps, "cards", trace.getCards())));
        return request;
    }

    public RowPage list(int days, boolean problemsOnly, String query, int page, int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        Page<Row> result = repository.search(since(days), problemsOnly, Status.OK, likePattern(query),
                PageRequest.of(pageNumber, pageSize));
        return new RowPage(result.getContent(), result.getTotalElements(), pageNumber, pageSize);
    }

    public Optional<Detail> detail(long id) {
        return repository.findById(id).map(r -> new Detail(toRow(r), readDetails(r.getDetails())));
    }

    public Summary summary(int days) {
        List<Row> rows = repository.findRowsSince(since(days));
        Map<Status, Long> byStatus = rows.stream()
                .collect(Collectors.groupingBy(Row::status, TreeMap::new, Collectors.counting()));
        return new Summary(rows.size(), problems(rows), byStatus, tokens(rows),
                median(rows.stream().map(Row::totalMs).toList()),
                rows.stream().map(Row::totalMs).max(Integer::compare).orElse(null),
                rows.stream().mapToInt(Row::aiCalls).filter(calls -> calls > 0).average().orElse(0),
                total(rows, Row::toolCalls), total(rows, Row::toolFailures),
                speech(rows), days(rows),
                top(rows.stream().filter(r -> r.endpoints() != null).flatMap(r -> r.endpoints().lines())
                        .filter(line -> !line.isBlank())),
                top(rows.stream().map(Row::error).filter(Objects::nonNull)));
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Europe/Berlin")
    public void purge() {
        try {
            int deleted = repository.deleteAskedBefore(LocalDateTime.now(clock).minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("Communicator-Protokoll: {} Anfragen geloescht", deleted);
            }
        } catch (RuntimeException ex) {
            log.warn("Aufraeumen des Communicator-Protokolls fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /** {@code days} = 1 meint heute, 7 die letzten sieben Kalendertage. */
    private LocalDateTime since(int days) {
        return LocalDateTime.now(clock).toLocalDate().minusDays(Math.clamp(days, 1, RETENTION_DAYS) - 1L)
                .atStartOfDay();
    }

    private static List<Day> days(List<Row> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> r.askedAt().toLocalDate(), TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(e -> new Day(e.getKey(), e.getValue().size(), problems(e.getValue()), tokens(e.getValue())))
                .toList();
    }

    private static SpeechStats speech(List<Row> rows) {
        List<Row> measured = rows.stream().filter(r -> r.voice() && r.speechMs() != null).toList();
        List<Row> answered = measured.stream()
                .filter(r -> r.status() != Status.SPEECH_ERROR && r.status() != Status.MODEL_MISSING).toList();
        List<Row> withLength = answered.stream().filter(r -> r.audioMs() != null).toList();
        List<Integer> times = answered.stream().map(Row::speechMs).toList();
        int audioMs = total(withLength, Row::audioMs);
        return new SpeechStats(measured.size(),
                (int) measured.stream().filter(r -> r.status() == Status.NOT_UNDERSTOOD).count(),
                measured.size() - answered.size(), median(times),
                times.stream().max(Integer::compare).orElse(null),
                audioMs == 0 ? null : (double) total(withLength, Row::speechMs) / audioMs);
    }

    private static int problems(Collection<Row> rows) {
        return (int) rows.stream().filter(Row::problem).count();
    }

    private static Tokens tokens(Collection<Row> rows) {
        return new Tokens(rows.stream().mapToLong(Row::inputTokens).sum(),
                rows.stream().mapToLong(Row::outputTokens).sum(),
                rows.stream().mapToLong(Row::cacheReadTokens).sum(),
                rows.stream().mapToLong(Row::cacheWriteTokens).sum());
    }

    private static List<Count> top(Stream<String> names) {
        return names.collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> new Count(e.getKey(), e.getValue()))
                .sorted((a, b) -> Long.compare(b.count(), a.count())).limit(TOP).toList();
    }

    private static Integer median(List<Integer> values) {
        return values.isEmpty() ? null : values.stream().sorted().toList().get(values.size() / 2);
    }

    private static int total(Collection<Row> rows, ToIntFunction<Row> value) {
        return rows.stream().mapToInt(value).sum();
    }

    private static int sum(List<Step> steps, Function<Step, Integer> value) {
        return steps.stream().map(value).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    private JsonNode readDetails(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("Communicator-Verlauf nicht lesbar: {}", ex.getOriginalMessage());
            return null;
        }
    }

    private static Row toRow(CommunicatorRequest r) {
        return new Row(r.getId(), r.getAskedAt(), r.getActor(), r.isVoice(), r.getStatus(), r.getQuestion(),
                r.getError(), r.getModel(), r.getAudioBytes(), r.getAudioType(), r.getAudioMs(), r.getSpeechMs(),
                r.getTotalMs(), r.getAiCalls(), r.getToolCalls(), r.getToolFailures(), r.getInputTokens(),
                r.getOutputTokens(), r.getCacheReadTokens(), r.getCacheWriteTokens(), r.getEndpoints());
    }

    private static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
