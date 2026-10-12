# Hausverstand (proaktiver KI-Agent) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein proaktiver Claude-Agent im Spring-Backend, der alle 30 Minuten und bei Schlüsselereignissen den Hauszustand bewertet, Aktionen je Freigabeklasse vorschlägt (Telegram-Knöpfe) oder direkt ausführt, sich Notizen merkt und über eine Admin-Seite gesteuert wird.

**Architecture:** Die Claude-Anbindung des Telegram-Bots wandert in ein gemeinsames Paket `ai/` (Client, Nachrichten, `AgentTool`, Registry mit Scopes). Das neue Modul `agent/` besteht aus Wecker (`AgentWakeService`), Snapshot (`HouseSnapshotBuilder`), Lauf (`AgentRunService`), Schleuse (`AgentActionGate` → `AgentProposalService`/`AgentActionExecutor`), Gedächtnis (vier Tabellen) und REST-Controller; Telegram bekommt Inline-Knöpfe und `callback_query`-Verarbeitung. Frontend: Admin-Seite `admin/agent` und ein Hub-Eintrag.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Lombok / Liquibase (MariaDB) / Anthropic Messages API über `RestTemplate` / Angular 19 standalone / Karma-Jasmine.

**Spec:** `docs/superpowers/specs/2026-10-12-hausverstand-agent-design.md`

## Global Constraints

- Backend-Build nur mit JDK 21 (`JAVA_HOME` auf jdk-21.0.10 setzen, siehe Memory `backend-jdk21-build`); Tests mit `mvn -q test -Dtest=<Klasse>` aus `backend/`.
- Alle JPA-Repositories liegen in `com.household.manager.repository` (JpaConfig scannt nur dort).
- Jede Tabelle ein eigenes Liquibase-Changeset (MariaDB-DDL-Autocommit); Changeset-Ids fortlaufend `20261012-0061` … `20261012-0065`, Autor `claude`, immer mit `<rollback>`.
- Scheduled-Methoden und Services, die aus dem Lauf heraus gerufen werden, **werfen nie** (fangen, loggen).
- Aktionen laufen über die bestehenden Services, nie über REST.
- `/v1/agent/**` ADMIN (methodenlos, vor der generischen GET-Regel); einzige Ausnahme `GET /v1/agent/summary` → KIOSK, Matcher davor.
- Heizung nur 5,0–24,0 °C; `LOCK` nur Verriegeln; max. 3 offene Vorschläge; kein zweiter offener Vorschlag je Ziel; Vorschlag verfällt nach 2 h; Ruhezeit `22:00-07:00` (außer `urgent`); Notizen max. 50 aktiv, 500 Zeichen; Lauf-Deckel 8 Tool-Iterationen und 90 s.
- Nie 401 aus Agent-Pfaden (ein 401 wirft den Nutzer aus der Haushalts-Session); Ratenverstöße → `TooManyRequestsException` (429).
- Modell-Default `claude-sonnet-4-5` über `AGENT_MODEL`; beim Implementieren von Task 1 die ID gegen den `claude-api`-Skill prüfen und ggf. im Plan-Default anpassen.
- Deutsch in Nutzer-sichtbaren Texten, bestehende Kommentar-Konvention (Deutsch, „warum").
- Commit-Messages enden mit `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; Commit-Text per `git commit -F -` mit Heredoc (keine Anführungszeichen-Falle in PowerShell).

## Review Focus

1. **Doppelter Knopfdruck auf „Ja" aus zwei Chats gleichzeitig** darf genau eine Geräteaktion auslösen (Status-Wechsel `PENDING → APPROVED` atomar, Task 5 testet es per `@Transactional` + erneutem Laden).
2. **Wecker während eines laufenden Laufs** darf nicht verloren gehen und nicht parallel laufen (Task 9: Folgelauf-Flag).
3. **Ruhezeit-Vorschlag, der morgens nicht mehr gilt** (Heizung inzwischen von Hand gestellt): Zustellung erst nach erneuter Zustandsprüfung, sonst `EXECUTED` mit „war bereits so" ohne Nachricht (Task 5 `deliverPending`).
4. **Snapshot-Quelle wirft** (Tractive-Token abgelaufen): Lauf läuft weiter mit „nicht abrufbar" (Task 7).
5. **Telegram nicht konfiguriert**: Vorschläge entstehen trotzdem, keine Exception aus `AgentNotifier` (Task 5).

---

### Task 1: Gemeinsames Paket `ai/` (Claude-Client aus Telegram herauslösen)

**Files:**
- Create: `backend/src/main/java/com/household/manager/ai/AiProperties.java`
- Create: `backend/src/main/java/com/household/manager/ai/AiException.java`
- Create: `backend/src/main/java/com/household/manager/ai/AnthropicRequest.java`
- Move: `telegram/AnthropicApiClient.java`, `telegram/AnthropicMessage.java`, `telegram/AnthropicResponse.java`, `telegram/ToolResult.java`, `telegram/tools/AgentTool.java` → `ai/`
- Create: `backend/src/main/java/com/household/manager/ai/AgentScope.java`
- Rename: `telegram/TelegramToolRegistry.java` → `ai/AgentToolRegistry.java`
- Modify: `telegram/TelegramProperties.java`, `telegram/TelegramAgentService.java`, `telegram/TelegramPollingService.java`, alle `telegram/tools/*Tool.java` (Import)
- Modify: `backend/src/main/resources/application.properties:203-205`
- Move/Modify Tests: `telegram/AnthropicApiClientTest.java` → `ai/AnthropicApiClientTest.java`, `telegram/TelegramToolRegistryTest.java` → `ai/AgentToolRegistryTest.java`, `telegram/TelegramAgentServiceTest.java`, `telegram/TelegramNotificationServiceTest.java`, `telegram/TelegramPropertiesTest.java`

**Interfaces:**
- Produces:
  - `ai.AiProperties` (`@ConfigurationProperties("ai")`): `anthropicApiKey`, `anthropicBaseUrl` (Default `https://api.anthropic.com`), `httpTimeoutMs` (10000); `boolean isConfigured()`.
  - `ai.AnthropicRequest(String model, int maxTokens, List<Map<String,Object>> system, List<AnthropicMessage> messages, List<Map<String,Object>> tools)` mit `static AnthropicRequest of(String model, int maxTokens, String systemText, List<AnthropicMessage> messages, List<Map<String,Object>> tools)` (ein Textblock) und `static List<Map<String,Object>> cachedBlock(String text)` (Textblock mit `cache_control: {type: ephemeral}`).
  - `ai.AnthropicResponse(String stopReason, List<Map<String,Object>> content, int inputTokens, int outputTokens)` mit `wantsToolUse()`, `toolUseBlocks()`, `text()`.
  - `ai.AnthropicApiClient(AiProperties, RestTemplateBuilder)`: `AnthropicResponse createMessage(AnthropicRequest request)`; wirft `AiException`.
  - `ai.AgentScope { TELEGRAM, HOUSE }`; `ai.AgentTool` bekommt `default AgentScope scope() { return AgentScope.TELEGRAM; }`.
  - `ai.AgentToolRegistry(List<AgentTool>)`: `List<Map<String,Object>> toolDefinitions(AgentScope)`, `ToolResult execute(AgentScope, String name, Map<String,Object> input)` (unbekannt oder falscher Scope ⇒ `ToolResult.failure`).
  - `ai.ToolResult` unverändert (nur Paket).

- [ ] **Step 1: Failing Test für `AgentToolRegistry` mit Scopes**

`backend/src/test/java/com/household/manager/ai/AgentToolRegistryTest.java`:

```java
package com.household.manager.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {

    private AgentTool tool(String name, AgentScope scope, String result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public AgentScope scope() { return scope; }
            @Override public String execute(Map<String, Object> input) { return result; }
        };
    }

    @Test
    void definitionsAreFilteredByScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("tg", AgentScope.TELEGRAM, "a"), tool("house", AgentScope.HOUSE, "b")));

        List<Map<String, Object>> defs = registry.toolDefinitions(AgentScope.HOUSE);

        assertEquals(1, defs.size());
        assertEquals("house", defs.get(0).get("name"));
    }

    @Test
    void executeRefusesToolOfOtherScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("tg", AgentScope.TELEGRAM, "a")));

        ToolResult result = registry.execute(AgentScope.HOUSE, "tg", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("Unbekanntes Tool"));
    }

    @Test
    void executeRunsToolOfMatchingScope() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("tg", AgentScope.TELEGRAM, "ok")));

        ToolResult result = registry.execute(AgentScope.TELEGRAM, "tg", Map.of());

        assertFalse(result.error());
        assertEquals("ok", result.content());
    }

    @Test
    void executeConvertsExceptionIntoFailure() {
        AgentTool failing = new AgentTool() {
            @Override public String name() { return "boom"; }
            @Override public String description() { return "t"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String execute(Map<String, Object> input) { throw new IllegalStateException("kaputt"); }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(failing));

        ToolResult result = registry.execute(AgentScope.TELEGRAM, "boom", null);

        assertTrue(result.error());
        assertTrue(result.content().contains("kaputt"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=AgentToolRegistryTest`
Expected: Compile-Fehler (Paket `ai` existiert nicht).

- [ ] **Step 3: Neue Dateien im Paket `ai` anlegen**

`ai/AiProperties.java`:

```java
package com.household.manager.ai;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Gemeinsame Anthropic-Anbindung fuer Telegram-Bot und Hausverstand. Secret nur per Env, nie loggen. */
@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    @ToString.Exclude
    private String anthropicApiKey = "";
    private String anthropicBaseUrl = "https://api.anthropic.com";
    private int httpTimeoutMs = 10000;

    public boolean isConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
```

`ai/AiException.java`:

```java
package com.household.manager.ai;

/** Fehler der Claude-API (Transport oder leere Antwort). */
public class AiException extends RuntimeException {
    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ai/AgentScope.java`:

```java
package com.household.manager.ai;

/** Welcher Agent ein Tool sehen darf: der Telegram-Dialog oder der proaktive Hausverstand. */
public enum AgentScope {
    TELEGRAM,
    HOUSE
}
```

`ai/AgentTool.java` (verschoben, plus `scope()`):

```java
package com.household.manager.ai;

import java.util.Map;

/**
 * Ein Werkzeug eines KI-Agenten: Definition (Name, Beschreibung, JSON-Schema) plus
 * Ausfuehrung. Ein Spring-Bean pro Tool; die Registry sammelt alle Beans ein und
 * filtert nach {@link #scope()}.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON-Schema der Eingabe im Anthropic-Format ("type": "object", ...). */
    Map<String, Object> inputSchema();

    /** Default TELEGRAM, damit die Bestandstools unveraendert bleiben. */
    default AgentScope scope() {
        return AgentScope.TELEGRAM;
    }

    String execute(Map<String, Object> input) throws Exception;
}
```

`ai/AgentToolRegistry.java` (ersetzt `TelegramToolRegistry`):

```java
package com.household.manager.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sammelt alle {@link AgentTool}-Beans ein und kapselt ihre Ausfuehrung je Scope:
 * Fehler werden nie geworfen, sondern als Fehler-Result zurueckgegeben, damit das
 * Modell sinnvoll darauf antworten kann. Ein Tool des falschen Scopes gilt als unbekannt.
 */
@Component
@Slf4j
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> agentTools) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        agentTools.forEach(tool -> byName.put(tool.name(), tool));
        this.tools = byName;
    }

    public List<Map<String, Object>> toolDefinitions(AgentScope scope) {
        return tools.values().stream()
                .filter(tool -> tool.scope() == scope)
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "input_schema", tool.inputSchema()))
                .toList();
    }

    public ToolResult execute(AgentScope scope, String name, Map<String, Object> input) {
        AgentTool tool = tools.get(name);
        if (tool == null || tool.scope() != scope) {
            return ToolResult.failure("Unbekanntes Tool: " + name);
        }
        try {
            return ToolResult.ok(tool.execute(input != null ? input : Map.of()));
        } catch (Exception ex) {
            log.warn("Tool {} fehlgeschlagen: {}", name, ex.getMessage());
            return ToolResult.failure("Tool-Fehler: " + ex.getMessage());
        }
    }
}
```

`ai/AnthropicRequest.java`:

```java
package com.household.manager.ai;

import java.util.List;
import java.util.Map;

/** Ein Aufruf der Messages-API. {@code system} sind Content-Bloecke (Prompt-Caching moeglich). */
public record AnthropicRequest(String model, int maxTokens, List<Map<String, Object>> system,
                               List<AnthropicMessage> messages, List<Map<String, Object>> tools) {

    public static AnthropicRequest of(String model, int maxTokens, String systemText,
                                      List<AnthropicMessage> messages, List<Map<String, Object>> tools) {
        return new AnthropicRequest(model, maxTokens,
                List.of(Map.of("type", "text", "text", systemText)), messages, tools);
    }

    /** Textblock, den die API fuer Folgeaufrufe zwischenspeichert (nur fuer den statischen Prompt-Teil). */
    public static Map<String, Object> cachedBlock(String text) {
        return Map.of("type", "text", "text", text, "cache_control", Map.of("type", "ephemeral"));
    }

    public static Map<String, Object> block(String text) {
        return Map.of("type", "text", "text", text);
    }
}
```

`ai/AnthropicResponse.java` (verschoben, plus Tokens):

```java
package com.household.manager.ai;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Antwort der Messages-API: stop_reason, rohe Content-Bloecke, Token-Zaehler (0, wenn unbekannt). */
public record AnthropicResponse(String stopReason, List<Map<String, Object>> content,
                                int inputTokens, int outputTokens) {

    public AnthropicResponse(String stopReason, List<Map<String, Object>> content) {
        this(stopReason, content, 0, 0);
    }

    public boolean wantsToolUse() {
        return "tool_use".equals(stopReason);
    }

    public List<Map<String, Object>> toolUseBlocks() {
        return content.stream().filter(block -> "tool_use".equals(block.get("type"))).toList();
    }

    public String text() {
        return content.stream()
                .filter(block -> "text".equals(block.get("type")))
                .map(block -> String.valueOf(block.get("text")))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
```

`ai/AnthropicApiClient.java`:

```java
package com.household.manager.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP-Client fuer die Anthropic Messages API (Tool-Use), geteilt von Telegram-Bot und Hausverstand. */
@Component
public class AnthropicApiClient {

    private static final String API_VERSION = "2023-06-01";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final AiProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicApiClient(AiProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    public AnthropicResponse createMessage(AnthropicRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        body.put("messages", request.messages());
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", properties.getAnthropicApiKey());
        headers.set("anthropic-version", API_VERSION);

        try {
            JsonNode root = restTemplate.postForObject(
                    properties.getAnthropicBaseUrl() + "/v1/messages",
                    new HttpEntity<>(body, headers), JsonNode.class);
            if (root == null) {
                throw new AiException("Claude API lieferte keine Antwort", null);
            }
            List<Map<String, Object>> content = objectMapper.convertValue(
                    root.path("content"), new TypeReference<>() {
                    });
            JsonNode usage = root.path("usage");
            return new AnthropicResponse(root.path("stop_reason").asText(), content,
                    usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
        } catch (RestClientException ex) {
            throw new AiException("Claude API-Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
```

`ai/AnthropicMessage.java` und `ai/ToolResult.java`: Datei nach `ai/` verschieben, Package-Zeile anpassen, sonst unverändert.

- [ ] **Step 4: Telegram-Modul auf das neue Paket umstellen**

`TelegramProperties`: Felder `anthropicApiKey` und `anthropicBaseUrl` entfernen; `isConfigured()` prüft nur noch `enabled`, `botToken`, `allowedChatIds`. Javadoc ergänzen: „Der Anthropic-Key liegt in `AiProperties`."

`TelegramPollingService.start()`:

```java
    private final AiProperties aiProperties; // neues Feld (Konstruktor-Injection via @RequiredArgsConstructor)

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isConfigured() || !aiProperties.isConfigured()) {
            log.info("Telegram-Assistent nicht konfiguriert (Token/Key/Chat-IDs fehlen) — Polling aus");
            return;
        }
        // Rest unveraendert
```

`TelegramAgentService`: Felder `AnthropicApiClient anthropicApiClient` (jetzt `ai`), `AgentToolRegistry toolRegistry`; Aufruf:

```java
                AnthropicResponse response = anthropicApiClient.createMessage(AnthropicRequest.of(
                        properties.getModel(), properties.getMaxTokens(), SYSTEM_PROMPT, messages,
                        toolRegistry.toolDefinitions(AgentScope.TELEGRAM)));
```

und in `executeTools`: `toolRegistry.execute(AgentScope.TELEGRAM, toolName, input)`.

Alle `telegram/tools/*Tool.java`: Import `com.household.manager.telegram.tools.AgentTool` → `com.household.manager.ai.AgentTool`; `TelegramToolRegistry` löschen. `TelegramNotificationService` unverändert.

`application.properties` (Zeilen 203–205 ersetzen):

```properties
telegram.model=${TELEGRAM_AGENT_MODEL:claude-haiku-4-5-20251001}

# Gemeinsame Claude-Anbindung (Telegram-Bot und Hausverstand)
ai.anthropic-api-key=${ANTHROPIC_API_KEY:}
```

(`telegram.anthropic-api-key` entfällt.)

- [ ] **Step 5: Tests verschieben und anpassen**

`ai/AnthropicApiClientTest.java`: aus `telegram/` verschieben; Setup wird

```java
        AiProperties props = new AiProperties();
        props.setAnthropicApiKey("sk-test");
        props.setAnthropicBaseUrl("https://api.anthropic.test");
        client = new AnthropicApiClient(props, new RestTemplateBuilder());
```

Aufrufe `client.createMessage("sys", messages, tools)` → `client.createMessage(AnthropicRequest.of("m", 100, "sys", messages, tools))`; `assertThrows(TelegramException.class` → `AiException.class`. Zusätzlicher Test:

```java
    @Test
    void readsUsageTokens() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],
                         "usage":{"input_tokens":123,"output_tokens":7}}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage(
                AnthropicRequest.of("m", 100, "sys", List.of(AnthropicMessage.user("hi")), List.of()));

        assertEquals(123, response.inputTokens());
        assertEquals(7, response.outputTokens());
    }
```

`telegram/TelegramToolRegistryTest.java` löschen (ersetzt durch `AgentToolRegistryTest`). `TelegramAgentServiceTest`: Mock-Typ `AgentToolRegistry`; `when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))` → `when(anthropicApiClient.createMessage(any(AnthropicRequest.class)))`; `toolRegistry.toolDefinitions()` → `toolDefinitions(AgentScope.TELEGRAM)`; `toolRegistry.execute(eq("x"), any())` → `execute(eq(AgentScope.TELEGRAM), eq("x"), any())`. `TelegramNotificationServiceTest` und `TelegramPropertiesTest`: Zeilen `p.setAnthropicApiKey("k")` entfernen; ein Test in `TelegramPropertiesTest`, der „ohne Key nicht konfiguriert" prüft, wird gelöscht. `TelegramPollingServiceTest`: Konstruktor bekommt zusätzlich ein `AiProperties` mit gesetztem Key.

- [ ] **Step 6: Alle betroffenen Tests laufen lassen**

Run: `cd backend && mvn -q test -Dtest='AgentToolRegistryTest,AnthropicApiClientTest,Telegram*Test,*ToolTest'`
Expected: PASS, keine Compile-Fehler im Gesamtbuild (`mvn -q compile test-compile`).

- [ ] **Step 7: Commit**

```bash
git add -A backend/src
git commit -F - <<'EOF'
refactor(ai): Claude-Anbindung aus dem Telegram-Modul in ein gemeinsames Paket heben

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 2: Persistenz (Liquibase, Entities, Repositories) und Konfiguration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261012-0061-create-agent-action-policy.xml`, `…-0062-create-agent-note.xml`, `…-0063-create-agent-proposal.xml`, `…-0064-create-agent-proposal-message.xml`, `…-0065-create-agent-run.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (fünf `<include>` am Ende)
- Create: `backend/src/main/java/com/household/manager/model/entity/AgentActionPolicyEntity.java`, `AgentNote.java`, `AgentProposal.java`, `AgentProposalMessage.java`, `AgentRun.java`
- Create: `backend/src/main/java/com/household/manager/agent/ActionClass.java`, `ActionPolicy.java`, `NoteKind.java`, `NoteSource.java`, `ProposalStatus.java`, `AgentTriggerType.java`
- Create: `backend/src/main/java/com/household/manager/repository/AgentActionPolicyRepository.java`, `AgentNoteRepository.java`, `AgentProposalRepository.java`, `AgentProposalMessageRepository.java`, `AgentRunRepository.java`
- Create: `backend/src/main/java/com/household/manager/agent/AgentProperties.java`
- Modify: `backend/src/main/resources/application.properties` (Block `# Hausverstand`)
- Test: `backend/src/test/java/com/household/manager/agent/AgentPropertiesTest.java`

**Interfaces:**
- Produces: Enums `ActionClass {HEATING, SWITCH, MODE, NOTIFY, LOCK}`, `ActionPolicy {PROPOSE, DIRECT}`, `NoteKind {OBSERVATION, PREFERENCE}`, `NoteSource {AGENT, USER}`, `ProposalStatus {PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED, FAILED}`, `AgentTriggerType {SCHEDULED, WAKE, MANUAL}`.
- Entities (Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`):
  - `AgentActionPolicyEntity`: `@Id @Enumerated(STRING) ActionClass actionClass`; `@Enumerated(STRING) ActionPolicy policy`; `LocalDateTime updatedAt`.
  - `AgentNote`: `Long id`; `NoteKind kind`; `String text` (500); `NoteSource source`; `LocalDateTime createdAt, updatedAt`; `LocalDateTime expiresAt` (nullable).
  - `AgentProposal`: `Long id`; `ActionClass actionClass`; `String target` (200); `String actionJson` (TEXT); `String reason` (1000); `boolean urgent`; `ProposalStatus status`; `String decidedBy` (100, nullable); `LocalDateTime decidedAt, deliveredAt` (nullable); `String error` (1000, nullable); `LocalDateTime createdAt`.
  - `AgentProposalMessage`: `Long id`; `Long proposalId`; `long chatId`; `long messageId`.
  - `AgentRun`: `Long id`; `AgentTriggerType triggerType`; `String triggerDetail` (1000); `LocalDateTime startedAt`; `long durationMs`; `String model` (100); `int inputTokens, outputTokens, toolCalls`; `String summary` (2000, nullable); `String error` (1000, nullable).
- Repositories:
  - `AgentActionPolicyRepository extends JpaRepository<AgentActionPolicyEntity, ActionClass>`
  - `AgentNoteRepository`: `List<AgentNote> findAllByOrderByCreatedAtAsc()`; `@Transactional @Modifying @Query("delete from AgentNote n where n.expiresAt is not null and n.expiresAt < :now") int deleteExpired(@Param("now") LocalDateTime now)`
  - `AgentProposalRepository`: `List<AgentProposal> findByStatus(ProposalStatus)`; `long countByStatus(ProposalStatus)`; `boolean existsByStatusAndTarget(ProposalStatus, String)`; `List<AgentProposal> findTop50ByOrderByCreatedAtDesc()`; `List<AgentProposal> findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(Collection<ProposalStatus>, LocalDateTime)`
  - `AgentProposalMessageRepository`: `List<AgentProposalMessage> findByProposalId(Long)`
  - `AgentRunRepository`: `List<AgentRun> findTop50ByOrderByStartedAtDesc()`; `@Query("select coalesce(sum(r.inputTokens),0), coalesce(sum(r.outputTokens),0) from AgentRun r where r.startedAt >= :since") List<Object[]> sumTokensSince(@Param("since") LocalDateTime since)`; `@Transactional @Modifying @Query("delete from AgentRun r where r.startedAt < :cutoff") int deleteByStartedAtBefore(@Param("cutoff") LocalDateTime cutoff)`; `List<AgentRun> findTop3ByOrderByStartedAtDesc()`
- `AgentProperties` (`@ConfigurationProperties("agent")`): `boolean enabled=true`; `String model="claude-sonnet-4-5"`; `int maxTokens=2048`; `long tickIntervalMs=1_800_000`; `long initialDelayMs=120_000`; `List<String> wakeEntities`; `long wakeDebounceMs=180_000`; `String quietHours="22:00-07:00"`; `int maxToolIterations=8`; `int runTimeoutSeconds=90`; `int maxOpenProposals=3`; `int proposalTtlHours=2`; `int maxActiveNotes=50`; `int runRetentionDays=30`; `long manualRunMinIntervalMs=60_000`; Methode `TimeWindow quietWindow()` (parst `HH:mm-HH:mm`, bei Fehler `log.warn` und `new TimeWindow(LocalTime.of(22,0), LocalTime.of(7,0))`).

- [ ] **Step 1: Failing Test für `AgentProperties.quietWindow()`**

```java
package com.household.manager.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class AgentPropertiesTest {

    @Test
    void parsesQuietHours() {
        AgentProperties p = new AgentProperties();
        p.setQuietHours("23:30-06:00");

        assertTrue(p.quietWindow().contains(LocalTime.of(2, 0)));
        assertFalse(p.quietWindow().contains(LocalTime.of(12, 0)));
    }

    @Test
    void fallsBackToDefaultOnGarbage() {
        AgentProperties p = new AgentProperties();
        p.setQuietHours("abends");

        assertTrue(p.quietWindow().contains(LocalTime.of(23, 0)));
        assertFalse(p.quietWindow().contains(LocalTime.of(8, 0)));
    }
}
```

- [ ] **Step 2: Test laufen lassen** — `mvn -q test -Dtest=AgentPropertiesTest`, erwartet Compile-Fehler.

- [ ] **Step 3: Enums, `AgentProperties`, Entities, Repositories, Changesets schreiben**

`agent/AgentProperties.java`:

```java
package com.household.manager.agent;

import com.household.manager.common.TimeWindow;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;
import java.util.List;

/** Konfiguration des Hausverstands (Deploy-Ebene; der Betriebsschalter liegt in application_settings). */
@Configuration
@ConfigurationProperties(prefix = "agent")
@Data
@Slf4j
public class AgentProperties {

    private boolean enabled = true;
    private String model = "claude-sonnet-4-5";
    private int maxTokens = 2048;
    private long tickIntervalMs = 1_800_000;
    private long initialDelayMs = 120_000;
    private List<String> wakeEntities = List.of();
    private long wakeDebounceMs = 180_000;
    private String quietHours = "22:00-07:00";
    private int maxToolIterations = 8;
    private int runTimeoutSeconds = 90;
    private int maxOpenProposals = 3;
    private int proposalTtlHours = 2;
    private int maxActiveNotes = 50;
    private int runRetentionDays = 30;
    private long manualRunMinIntervalMs = 60_000;

    /** Ruhezeit als Fenster; ein unlesbarer Wert faellt auf 22–7 zurueck statt den Start zu kippen. */
    public TimeWindow quietWindow() {
        try {
            String[] parts = quietHours.split("-");
            return new TimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
        } catch (Exception ex) {
            log.warn("agent.quiet-hours '{}' unlesbar, nutze 22:00-07:00", quietHours);
            return new TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0));
        }
    }
}
```

Changeset `20261012-0061-create-agent-action-policy.xml` (Muster `20261009-0058`):

```xml
    <changeSet id="20261012-0061-create-agent-action-policy" author="claude">
        <comment>Freigabe je Aktionsklasse des Hausverstands (PROPOSE/DIRECT). Fehlt eine Zeile, gilt PROPOSE.</comment>
        <createTable tableName="agent_action_policy">
            <column name="action_class" type="VARCHAR(20)"><constraints primaryKey="true" nullable="false"/></column>
            <column name="policy" type="VARCHAR(20)"><constraints nullable="false"/></column>
            <column name="updated_at" type="DATETIME"><constraints nullable="false"/></column>
        </createTable>
        <rollback><dropTable tableName="agent_action_policy"/></rollback>
    </changeSet>
```

`0062 agent_note`: `id BIGINT autoIncrement PK`, `kind VARCHAR(20) NOT NULL`, `text VARCHAR(500) NOT NULL`, `source VARCHAR(10) NOT NULL`, `created_at DATETIME NOT NULL`, `updated_at DATETIME NOT NULL`, `expires_at DATETIME NULL`.

`0063 agent_proposal`: `id BIGINT autoIncrement PK`, `action_class VARCHAR(20) NOT NULL`, `target VARCHAR(200) NOT NULL`, `action_json TEXT NOT NULL`, `reason VARCHAR(1000) NOT NULL`, `urgent BOOLEAN NOT NULL default false`, `status VARCHAR(20) NOT NULL`, `decided_by VARCHAR(100)`, `decided_at DATETIME`, `delivered_at DATETIME`, `error VARCHAR(1000)`, `created_at DATETIME NOT NULL`.

`0064 agent_proposal_message`: `id BIGINT autoIncrement PK`, `proposal_id BIGINT NOT NULL`, `chat_id BIGINT NOT NULL`, `message_id BIGINT NOT NULL`; `<addForeignKeyConstraint baseTableName="agent_proposal_message" baseColumnNames="proposal_id" referencedTableName="agent_proposal" referencedColumnNames="id" constraintName="fk_agent_proposal_message_proposal" onDelete="CASCADE"/>` im selben Changeset ist **nicht** erlaubt (DDL-Autocommit) — deshalb den FK direkt in `<createTable>` über `<constraints foreignKeyName="fk_agent_proposal_message_proposal" references="agent_proposal(id)" deleteCascade="true"/>` an der Spalte deklarieren.

`0065 agent_run`: `id BIGINT autoIncrement PK`, `trigger_type VARCHAR(20) NOT NULL`, `trigger_detail VARCHAR(1000)`, `started_at DATETIME NOT NULL`, `duration_ms BIGINT NOT NULL`, `model VARCHAR(100) NOT NULL`, `input_tokens INT NOT NULL default 0`, `output_tokens INT NOT NULL default 0`, `tool_calls INT NOT NULL default 0`, `summary VARCHAR(2000)`, `error VARCHAR(1000)`.

Master-Changelog ergänzen:

```xml
    <!-- Hausverstand: proaktiver KI-Agent -->
    <include file="db/changelog/changes/20261012-0061-create-agent-action-policy.xml"/>
    <include file="db/changelog/changes/20261012-0062-create-agent-note.xml"/>
    <include file="db/changelog/changes/20261012-0063-create-agent-proposal.xml"/>
    <include file="db/changelog/changes/20261012-0064-create-agent-proposal-message.xml"/>
    <include file="db/changelog/changes/20261012-0065-create-agent-run.xml"/>
```

Entities nach dem Muster `model/entity/Thermostat.java`; Enums mit `@Enumerated(EnumType.STRING)`; `@PrePersist` setzt `createdAt`/`updatedAt` per `LocalDateTime.now()` **nicht** — stattdessen setzen die Services die Zeiten über das `Clock`-Bean (testbar). Beispiel `AgentProposal`:

```java
@Entity
@Table(name = "agent_proposal")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentProposal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(name = "action_class", nullable = false, length = 20) private ActionClass actionClass;
    @Column(nullable = false, length = 200) private String target;
    @Column(name = "action_json", nullable = false, columnDefinition = "TEXT") private String actionJson;
    @Column(nullable = false, length = 1000) private String reason;
    @Column(nullable = false) private boolean urgent;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ProposalStatus status;
    @Column(name = "decided_by", length = 100) private String decidedBy;
    @Column(name = "decided_at") private LocalDateTime decidedAt;
    @Column(name = "delivered_at") private LocalDateTime deliveredAt;
    @Column(length = 1000) private String error;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
```

`application.properties` (neuer Block nach `# Telegram-KI-Assistent`):

```properties
# Hausverstand (proaktiver KI-Agent); Betriebsschalter liegt in application_settings (AGENT/agent_active)
agent.enabled=${AGENT_ENABLED:true}
agent.model=${AGENT_MODEL:claude-sonnet-4-5}
agent.tick-interval-ms=${AGENT_TICK_INTERVAL_MS:1800000}
agent.initial-delay-ms=120000
agent.wake-debounce-ms=180000
agent.quiet-hours=${AGENT_QUIET_HOURS:22:00-07:00}
# Weckerliste: nur diese Entitaeten loesen zwischen den Grundtakten einen Lauf aus
agent.wake-entities=binary_sensor.presence_household,input_boolean.manual_abwesend,input_boolean.manual_toni_allein,input_boolean.manual_nachtmodus,sensor.sun,input_boolean.manual_waschmaschine_fertig,input_boolean.manual_spuelmaschine_fertig
```

- [ ] **Step 4: Tests** — `mvn -q test -Dtest=AgentPropertiesTest` PASS; `mvn -q compile` ohne Fehler. (Die lokale `contextLoads`-Baseline ist rot, siehe Memory — nicht als Regression werten.)

- [ ] **Step 5: Commit** — `feat(agent): Tabellen, Entities und Konfiguration des Hausverstands`

---

### Task 3: Freigaben, Betriebsschalter und Aktionsausführung

**Files:**
- Create: `agent/AgentAction.java`, `agent/ActionOutcome.java`, `agent/AgentPolicyService.java`, `agent/AgentSettingsService.java`, `agent/AgentActionExecutor.java`
- Modify: `audit/AuditActor.java` (`agent()`)
- Test: `agent/AgentPolicyServiceTest.java`, `agent/AgentSettingsServiceTest.java`, `agent/AgentActionExecutorTest.java`

**Interfaces:**
- Produces:
  - `AgentAction(ActionClass actionClass, String target, Map<String,Object> params, String reason, boolean urgent)`; `String describe()` (menschenlesbar, z. B. „Heizung 3 auf 21,0 °C"). Parameter je Klasse: HEATING `targetTemp` (Double) und/oder `mode` (String MANUAL/OFF/FROST_PROTECTION), `target` = Thermostat-Id; SWITCH/MODE `state` (on/off), `target` = entityId; NOTIFY `channel` (telegram/push/alexa), `message`, `target` = channel; LOCK `target` = smartlockId.
  - `ActionOutcome(boolean alreadySatisfied, String summary)`.
  - `AgentPolicyService`: `ActionPolicy policyFor(ActionClass)` (NOTIFY immer DIRECT, fehlende Zeile PROPOSE, Lesefehler PROPOSE + warn); `Map<ActionClass, ActionPolicy> all()`; `void update(ActionClass, ActionPolicy)` (NOTIFY-Änderung ⇒ `IllegalArgumentException`; Audit `agent.policy.update`).
  - `AgentSettingsService`: `boolean isActive()` (Kategorie `AGENT`, Schlüssel `agent_active`, Default false, wirft nie); `void setActive(boolean)` (Audit `agent.settings.update`).
  - `AgentActionExecutor`: `ActionOutcome execute(AgentAction action)` — wirft bei Gerätefehler; liest vorher den Zustand und liefert `alreadySatisfied=true` ohne Geräteaufruf.
  - `AuditActor.agent()` = `new AuditActor(AuditActorType.SYSTEM, "AGENT")`.

- [ ] **Step 1: Failing Tests**

`AgentPolicyServiceTest` (Mockito):

```java
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
```

`AgentSettingsServiceTest`: `isActive()` liefert false bei fehlendem Wert, true bei `"true"`, false bei Exception aus `ApplicationSettingsService.getSettingsByCategory`; `setActive(true)` ruft `saveSettings("AGENT", Map.of("agent_active","true"))` und `auditService.record("agent.settings.update","agent_active=true")`.

`AgentActionExecutorTest` (Mocks: `ThermostatService`, `SwitchQueryService`, `SwitchCommandService`, `HouseModeQueryService`, `ManualEntityService`, `NukiLockService`, `TelegramNotificationService`, `PushNotificationService`, `AlexaAnnouncementService`, `AuditService`):

```java
    @Test void heatingSetsTargetAndMode() throws Exception {
        when(thermostatService.list()).thenReturn(List.of(new ThermostatDto(3L, "Wohnzimmer", true, 18.5, 17.0, ThermostatMode.MANUAL, false, false, null)));
        ActionOutcome out = executor().execute(new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), "kalt", false));
        verify(thermostatService).setTarget(3L, 21.0);
        assertFalse(out.alreadySatisfied());
    }
    @Test void heatingAlreadyAtTargetDoesNothing() throws Exception {
        when(thermostatService.list()).thenReturn(List.of(new ThermostatDto(3L, "Wohnzimmer", true, 20.0, 21.0, ThermostatMode.MANUAL, false, false, null)));
        ActionOutcome out = executor().execute(new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), "kalt", false));
        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
        assertTrue(out.alreadySatisfied());
    }
    @Test void switchTogglesOnlyWhenStateDiffers() throws Exception {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("off").displayName("X").build()));
        executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "r", false));
        verify(switchCommandService).toggle("switch.x");
    }
    @Test void switchAlreadyOnSkips() throws Exception {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("on").displayName("X").build()));
        assertTrue(executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "r", false)).alreadySatisfied());
        verifyNoInteractions(switchCommandService);
    }
    @Test void unknownSwitchThrows() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> executor().execute(new AgentAction(ActionClass.SWITCH, "switch.nope", Map.of("state", "on"), "r", false)));
    }
    @Test void modeUsesManualEntityToggle() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(ModeResponse.builder().entityId("input_boolean.manual_nachtmodus").state("off").displayName("Nacht").build()));
        executor().execute(new AgentAction(ActionClass.MODE, "input_boolean.manual_nachtmodus", Map.of("state", "on"), "r", false));
        verify(manualEntityService).toggle("input_boolean.manual_nachtmodus");
    }
    @Test void lockOnlyLocks() throws Exception {
        when(nukiLockService.listLocks()).thenReturn(List.of(new NukiLockResponse(7L, "Haustür", "unlocked", "closed", 80, false)));
        executor().execute(new AgentAction(ActionClass.LOCK, "7", Map.of(), "r", false));
        verify(nukiLockService).executeAction(7L, NukiLockAction.LOCK);
    }
    @Test void lockAlreadyLockedSkips() throws Exception {
        when(nukiLockService.listLocks()).thenReturn(List.of(new NukiLockResponse(7L, "Haustür", "locked", "closed", 80, false)));
        assertTrue(executor().execute(new AgentAction(ActionClass.LOCK, "7", Map.of(), "r", false)).alreadySatisfied());
        verify(nukiLockService, never()).executeAction(anyLong(), any());
    }
    @Test void notifyTelegramSendsToAllowedChats() throws Exception {
        executor().execute(new AgentAction(ActionClass.NOTIFY, "telegram", Map.of("channel", "telegram", "message", "Hallo"), "r", false));
        verify(telegramNotificationService).sendToAllowedChats("Hallo");
    }
    @Test void notifyPushUsesAgentTitle() throws Exception {
        executor().execute(new AgentAction(ActionClass.NOTIFY, "push", Map.of("channel", "push", "message", "Hallo"), "r", false));
        verify(pushNotificationService).sendToAll("Hausverstand", "Hallo");
    }
    @Test void everyExecutedActionIsAudited() throws Exception {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("off").displayName("X").build()));
        executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "weil", false));
        verify(auditService).record(eq(AuditActor.agent()), eq("agent.action.execute"), contains("switch.x"));
    }
```

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler erwartet.

- [ ] **Step 3: Implementierung**

`AuditActor`: `public static AuditActor agent() { return new AuditActor(AuditActorType.SYSTEM, "AGENT"); }`

`agent/AgentAction.java`:

```java
package com.household.manager.agent;

import java.util.Map;

/** Eine vom Modell gewuenschte Aktion, typisiert und mit Begruendung; nur die Schleuse fuehrt sie aus. */
public record AgentAction(ActionClass actionClass, String target, Map<String, Object> params,
                          String reason, boolean urgent) {

    public Double doubleParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value).trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " muss eine Zahl sein: " + value); }
    }

    public String stringParam(String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    public String describe() {
        return switch (actionClass) {
            case HEATING -> "Heizung " + target + (doubleParam("targetTemp") != null ? " auf " + doubleParam("targetTemp") + " °C" : "")
                    + (stringParam("mode") != null ? " Modus " + stringParam("mode") : "");
            case SWITCH -> "Schalter " + target + " " + stringParam("state");
            case MODE -> "Modus " + target + " " + stringParam("state");
            case NOTIFY -> "Nachricht (" + target + "): " + stringParam("message");
            case LOCK -> "Schloss " + target + " verriegeln";
        };
    }
}
```

`agent/ActionOutcome.java`: `public record ActionOutcome(boolean alreadySatisfied, String summary) {}`

`agent/AgentPolicyService.java`:

```java
@Service @RequiredArgsConstructor @Slf4j
public class AgentPolicyService {
    private final AgentActionPolicyRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public ActionPolicy policyFor(ActionClass actionClass) {
        if (actionClass == ActionClass.NOTIFY) return ActionPolicy.DIRECT;
        try {
            return repository.findById(actionClass).map(AgentActionPolicyEntity::getPolicy).orElse(ActionPolicy.PROPOSE);
        } catch (Exception ex) {
            log.warn("Freigabe fuer {} nicht lesbar, nutze PROPOSE", actionClass, ex);
            return ActionPolicy.PROPOSE;
        }
    }

    public Map<ActionClass, ActionPolicy> all() {
        Map<ActionClass, ActionPolicy> result = new EnumMap<>(ActionClass.class);
        for (ActionClass c : ActionClass.values()) result.put(c, policyFor(c));
        return result;
    }

    public void update(ActionClass actionClass, ActionPolicy policy) {
        if (actionClass == ActionClass.NOTIFY) throw new IllegalArgumentException("NOTIFY ist immer DIRECT.");
        repository.save(AgentActionPolicyEntity.builder().actionClass(actionClass).policy(policy)
                .updatedAt(LocalDateTime.now(clock)).build());
        auditService.record("agent.policy.update", actionClass + "=" + policy);
    }
}
```

`agent/AgentSettingsService.java` nach dem Muster `AppearanceSettingsService` (Kategorie `AGENT`, Schlüssel `agent_active`, `Boolean.parseBoolean`).

`agent/AgentActionExecutor.java`:

```java
@Service @RequiredArgsConstructor @Slf4j
public class AgentActionExecutor {
    static final double TOLERANCE = 0.01;
    static final String PUSH_TITLE = "Hausverstand";

    private final ThermostatService thermostatService;
    private final SwitchQueryService switchQueryService;
    private final SwitchCommandService switchCommandService;
    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;
    private final NukiLockService nukiLockService;
    private final TelegramNotificationService telegramNotificationService;
    private final PushNotificationService pushNotificationService;
    private final AlexaAnnouncementService alexaAnnouncementService;
    private final AuditService auditService;

    /** Liest den Zustand frisch und fuehrt nur aus, wenn das Ziel nicht schon erreicht ist (Regel aus confirmToggle). */
    public ActionOutcome execute(AgentAction action) {
        ActionOutcome outcome = switch (action.actionClass()) {
            case HEATING -> heating(action);
            case SWITCH -> toggleEntity(action, switchQueryService.listSwitches(null).stream()
                    .filter(s -> s.entityId().equals(action.target())).findFirst().map(SwitchResponse::state)
                    .orElseThrow(() -> new IllegalArgumentException("Unbekannter Schalter: " + action.target())),
                    () -> switchCommandService.toggle(action.target()));
            case MODE -> toggleEntity(action, houseModeQueryService.listModes().stream()
                    .filter(m -> m.entityId().equals(action.target())).findFirst().map(ModeResponse::state)
                    .orElseThrow(() -> new IllegalArgumentException("Kein Haus-Modus: " + action.target())),
                    () -> manualEntityService.toggle(action.target()));
            case NOTIFY -> notify(action);
            case LOCK -> lock(action);
        };
        if (!outcome.alreadySatisfied()) {
            auditService.record(AuditActor.agent(), "agent.action.execute", action.describe() + " — " + action.reason());
        }
        return outcome;
    }

    private ActionOutcome heating(AgentAction action) {
        long id = Long.parseLong(action.target());
        ThermostatDto current = thermostatService.list().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Thermostat: " + id));
        Double target = action.doubleParam("targetTemp");
        String mode = action.stringParam("mode");
        boolean targetSatisfied = target == null || (current.targetTemp() != null && Math.abs(current.targetTemp() - target) < TOLERANCE);
        boolean modeSatisfied = mode == null || current.mode() == ThermostatMode.valueOf(mode);
        if (targetSatisfied && modeSatisfied) return new ActionOutcome(true, current.name() + " steht bereits so.");
        if (mode != null && !modeSatisfied) thermostatService.setMode(id, ThermostatMode.valueOf(mode));
        if (target != null && !targetSatisfied) thermostatService.setTarget(id, target);
        return new ActionOutcome(false, current.name() + ": " + action.describe());
    }

    private ActionOutcome toggleEntity(AgentAction action, String currentState, Runnable toggle) {
        String desired = action.stringParam("state");
        if (!"on".equals(desired) && !"off".equals(desired)) throw new IllegalArgumentException("state muss on oder off sein");
        if (desired.equals(currentState)) return new ActionOutcome(true, action.target() + " ist bereits " + desired + ".");
        toggle.run();
        return new ActionOutcome(false, action.target() + " ist jetzt " + desired + ".");
    }

    private ActionOutcome lock(AgentAction action) {
        long smartlockId = Long.parseLong(action.target());
        NukiLockResponse lock = nukiLockService.listLocks().stream().filter(l -> l.smartlockId() == smartlockId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Schloss: " + smartlockId));
        if ("locked".equalsIgnoreCase(lock.state())) return new ActionOutcome(true, lock.name() + " ist bereits verriegelt.");
        nukiLockService.executeAction(smartlockId, NukiLockAction.LOCK); // SICHERHEITSANKER: nur LOCK
        return new ActionOutcome(false, lock.name() + " wird verriegelt.");
    }

    private ActionOutcome notify(AgentAction action) {
        String message = action.stringParam("message");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message fehlt");
        switch (String.valueOf(action.stringParam("channel"))) {
            case "telegram" -> telegramNotificationService.sendToAllowedChats(message);
            case "push" -> pushNotificationService.sendToAll(PUSH_TITLE, message);
            case "alexa" -> alexaAnnouncementService.announce(message, List.of(), AlexaTtsMode.ANNOUNCEMENT);
            default -> throw new IllegalArgumentException("channel muss telegram, push oder alexa sein");
        }
        return new ActionOutcome(false, "Nachricht gesendet.");
    }
}
```

(`AlexaTtsMode`-Konstante für die Durchsage beim Implementieren aus `alexa/AlexaTtsMode.java` übernehmen; falls sie anders heißt als `ANNOUNCEMENT`, den Namen hier und im Test anpassen.)

- [ ] **Step 4: Tests** — `mvn -q test -Dtest='AgentPolicyServiceTest,AgentSettingsServiceTest,AgentActionExecutorTest'` PASS.

- [ ] **Step 5: Commit** — `feat(agent): Freigaben, Betriebsschalter und Aktionsausfuehrung`

---

### Task 4: Notizen (Service und Modell-Tools)

**Files:**
- Create: `agent/AgentNoteService.java`, `agent/tools/NoteCreateTool.java`, `agent/tools/NoteUpdateTool.java`, `agent/tools/NoteDeleteTool.java`
- Test: `agent/AgentNoteServiceTest.java`, `agent/tools/NoteToolsTest.java`

**Interfaces:**
- Produces `AgentNoteService`: `List<AgentNote> listActive()` (ohne abgelaufene, aufsteigend nach `createdAt`); `AgentNote create(NoteKind kind, String text, NoteSource source, LocalDateTime expiresAt)` (trim, leer/`>500` ⇒ `IllegalArgumentException`, Obergrenze `maxActiveNotes` ⇒ `IllegalStateException("Notizgrenze erreicht …")`, Audit `agent.note.create`); `AgentNote update(long id, String text, LocalDateTime expiresAt)`; `void delete(long id, NoteSource requester)` (AGENT darf keine USER-Notiz löschen ⇒ `IllegalArgumentException`); `int purgeExpired()` (wirft nie); `String renderForPrompt()` (eine Zeile je Notiz: `#<id> [PREFERENCE|OBSERVATION, <source>, bis <expiresAt>|unbefristet] <text>`, leer ⇒ „(keine Notizen)").
- Tools (`AgentScope.HOUSE`): `note_create` (`kind`, `text`, `expiresInHours` optional Integer), `note_update` (`id`, `text`, `expiresInHours` optional), `note_delete` (`id`); alle rufen den Service mit `NoteSource.AGENT`; `expiresInHours` wird über das `Clock`-Bean in `expiresAt` umgerechnet.

- [ ] **Step 1: Failing Tests**

`AgentNoteServiceTest` (Mocks `AgentNoteRepository`, `AuditService`; `AgentProperties` mit `maxActiveNotes=2`; fixe `Clock`):

```java
    @Test void listActiveHidesExpired() { … repository liefert eine Notiz mit expiresAt vor now und eine ohne → nur die zweite … }
    @Test void createRejectsBlankAndTooLong() { assertThrows(IllegalArgumentException.class, () -> service().create(NoteKind.PREFERENCE, "  ", NoteSource.USER, null)); assertThrows(IllegalArgumentException.class, () -> service().create(NoteKind.PREFERENCE, "x".repeat(501), NoteSource.USER, null)); }
    @Test void createRefusesWhenLimitReached() { … zwei aktive vorhanden → IllegalStateException, kein save … }
    @Test void createSavesWithTimestampsAndAudits() { … verify save(createdAt == now, source, kind), auditService.record("agent.note.create", contains(text)) … }
    @Test void agentMayNotDeleteUserNote() { … findById → USER-Notiz; assertThrows(IllegalArgumentException, delete(1, AGENT)); verify(repository, never()).deleteById(any()) … }
    @Test void userMayDeleteAnyNote() { … delete(1, USER) → deleteById(1), Audit "agent.note.delete" … }
    @Test void purgeExpiredNeverThrows() { when(repository.deleteExpired(any())).thenThrow(new RuntimeException()); assertEquals(0, service().purgeExpired()); }
    @Test void rendersPromptLines() { … zwei Notizen → zwei Zeilen, beginnen mit "#1 [" bzw. "#2 [" … }
```

`NoteToolsTest`: `note_create` mit `expiresInHours=24` ruft `create(OBSERVATION, text, AGENT, now+24h)`; `note_delete` ruft `delete(5, AGENT)`; jedes Tool meldet `scope() == AgentScope.HOUSE`.

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler.

- [ ] **Step 3: Implementierung** (Service nach obigem Vertrag; Tools nach dem Muster `telegram/tools/SetModeTool`, `inputSchema` mit `required`-Listen; Beschreibungen auf Deutsch: „Merkt sich eine Beobachtung (mit Ablauf) oder eine Vorliebe (unbefristet). Beobachtungen immer mit expiresInHours.").

- [ ] **Step 4: Tests** PASS. **Step 5: Commit** — `feat(agent): Notizen als Gedaechtnis des Hausverstands`

---

### Task 5: Vorschläge, Telegram-Knöpfe und Zustellung

**Files:**
- Modify: `telegram/TelegramApiClient.java` (Inline-Keyboard, `editMessageReplyMarkup`, `answerCallbackQuery`), `telegram/dto/TelegramUpdate.java`, `telegram/dto/TelegramMessage.java`; Create: `telegram/dto/TelegramCallbackQuery.java`, `telegram/dto/TelegramUser.java`, `telegram/InlineButton.java`
- Create: `agent/ProposalDecision.java`, `agent/AgentNotifier.java`, `agent/AgentProposalService.java`, `agent/AgentQuietHours.java`
- Test: `telegram/TelegramApiClientTest.java` (erweitern), `agent/AgentNotifierTest.java`, `agent/AgentProposalServiceTest.java`, `agent/AgentQuietHoursTest.java`

**Interfaces:**
- `telegram.InlineButton(String text, String callbackData)`.
- `TelegramApiClient`: `long sendMessage(long chatId, String text, List<List<InlineButton>> keyboard)` (liefert `result.message_id`; `keyboard` leer ⇒ ohne `reply_markup`); bestehendes `void sendMessage(long, String)` bleibt und delegiert; `void editReplyMarkup(long chatId, long messageId, List<List<InlineButton>> keyboard)` (leere Liste ⇒ Knöpfe entfernen); `void answerCallbackQuery(String callbackQueryId, String text)`.
- DTOs: `TelegramMessage(TelegramChat chat, String text, @JsonProperty("message_id") Long messageId)`; `TelegramUser(long id, @JsonProperty("first_name") String firstName)`; `TelegramCallbackQuery(String id, TelegramUser from, TelegramMessage message, String data)`; `TelegramUpdate(updateId, message, @JsonProperty("callback_query") TelegramCallbackQuery callbackQuery)`.
- `agent.ProposalDecision { APPROVE, REJECT, APPROVE_AND_REMEMBER }`; Callback-Daten `agent:<proposalId>:<decision>`; `static Optional<ParsedCallback> AgentProposalService.parseCallback(String data)` mit `record ParsedCallback(long proposalId, ProposalDecision decision)`.
- `AgentQuietHours(AgentProperties, Clock)`: `boolean isQuietNow()`.
- `AgentNotifier(TelegramProperties, TelegramApiClient, AgentProposalMessageRepository)`: `void sendProposal(AgentProposal p)` (an alle erlaubten Chats, Knöpfe `[Ja] [Nein] [Ja, und merken]`, speichert `AgentProposalMessage` je Chat; wirft nie, Fehler je Chat isoliert); `void markDecided(AgentProposal p, String label)` (Knöpfe in allen gespeicherten Nachrichten entfernen und eine Folgezeile „→ <label>" senden; wirft nie); `void sendInfo(String text)` (wirft nie); `boolean isConfigured()`.
- `AgentProposalService(AgentProposalRepository, AgentActionExecutor, AgentNoteService, AgentNotifier, AgentQuietHours, AgentProperties, ObjectMapper, AuditService, Clock)`:
  - `AgentProposal propose(AgentAction action)` — legt `PENDING` an; `deliveredAt` sofort gesetzt und Telegram gesendet, außer Ruhezeit ohne `urgent` (dann `deliveredAt = null`).
  - `AgentProposal executeDirect(AgentAction action)` — führt aus, Zeile `EXECUTED`/`FAILED`, Info-Nachricht nur bei echter Ausführung.
  - `AgentProposal decide(long id, ProposalDecision decision, String decidedBy)` — `@Transactional`; nur `PENDING` (sonst `IllegalStateException("bereits entschieden")`); `REJECT` ⇒ `REJECTED` + Beobachtungsnotiz 24 h „<decidedBy> hat abgelehnt: <describe()>"; `APPROVE*` ⇒ `APPROVED`, dann Ausführung ⇒ `EXECUTED` (Summary in `error`-Feld **nicht**, sondern bleibt null) oder `FAILED` mit Fehlertext; `APPROVE_AND_REMEMBER` zusätzlich Vorliebe „<decidedBy> stimmt zu: <describe()> (<reason>)"; Audit `agent.proposal.decide`; `markDecided`.
  - `int expireStale()` — `PENDING` älter als `proposalTtlHours` ⇒ `EXPIRED`, Knöpfe entfernen; wirft nie.
  - `int deliverPending()` — `PENDING` mit `deliveredAt == null` außerhalb der Ruhezeit: erst `executor`-Vorprüfung über eine **Trockenlauf-Methode** `boolean AgentActionExecutor.isSatisfied(AgentAction)` (Task 3 ergänzen: dieselbe Zustandslesung ohne Ausführung); falls erfüllt ⇒ `EXECUTED` mit `error = null`, `decidedBy = "system"`, keine Nachricht; sonst senden + `deliveredAt`; wirft nie.
  - `long countPending()`, `List<AgentProposal> listRecent()` (`findTop50…`), `List<AgentProposal> recentForPrompt()` (letzte 24 h, alle Status), `AgentAction actionOf(AgentProposal)` (JSON zurück in `AgentAction`).

- [ ] **Step 1: Failing Tests (Auszug, alle vollständig ausformulieren)**

`TelegramApiClientTest` ergänzen (MockRestServiceServer, Muster der Bestandstests):

```java
    @Test
    void sendMessageWithKeyboardPostsReplyMarkupAndReturnsMessageId() {
        server.expect(requestTo("https://tg.test/bottoken/sendMessage"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].callback_data").value("agent:1:APPROVE"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":42}}", MediaType.APPLICATION_JSON));

        long id = client.sendMessage(5L, "Hallo", List.of(List.of(new InlineButton("Ja", "agent:1:APPROVE"))));

        assertEquals(42L, id);
    }

    @Test
    void editReplyMarkupWithEmptyKeyboardRemovesButtons() {
        server.expect(requestTo("https://tg.test/bottoken/editMessageReplyMarkup"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard").isEmpty())
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        client.editReplyMarkup(5L, 42L, List.of());
    }
```

`AgentQuietHoursTest`: 23:30 Berlin ⇒ quiet; 12:00 ⇒ nicht.

`AgentProposalServiceTest` (Mockito, fixe Clock 2026-10-12T12:00 Berlin, `maxOpenProposals=3`, `proposalTtlHours=2`):

```java
    @Test void proposeOutsideQuietHoursSendsImmediately() { when(quietHours.isQuietNow()).thenReturn(false); when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0)); AgentProposal p = service().propose(heating21()); assertEquals(ProposalStatus.PENDING, p.getStatus()); assertNotNull(p.getDeliveredAt()); verify(notifier).sendProposal(p); }
    @Test void proposeInQuietHoursWithoutUrgentIsHeldBack() { when(quietHours.isQuietNow()).thenReturn(true); … assertNull(p.getDeliveredAt()); verify(notifier, never()).sendProposal(any()); }
    @Test void urgentIgnoresQuietHours() { when(quietHours.isQuietNow()).thenReturn(true); … urgent=true … verify(notifier).sendProposal(any()); }
    @Test void decideApproveExecutesAndMarksExecuted() { AgentProposal p = pending(1L); when(repository.findById(1L)).thenReturn(Optional.of(p)); when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok")); service().decide(1L, ProposalDecision.APPROVE, "Benedikt"); assertEquals(ProposalStatus.EXECUTED, p.getStatus()); assertEquals("Benedikt", p.getDecidedBy()); verify(notifier).markDecided(eq(p), contains("Benedikt")); verify(auditService).record(eq("agent.proposal.decide"), contains("APPROVE")); }
    @Test void decideApproveWithDeviceFailureMarksFailed() { … when(executor.execute(any())).thenThrow(new RuntimeException("Hub weg")); … assertEquals(ProposalStatus.FAILED, p.getStatus()); assertTrue(p.getError().contains("Hub weg")); }
    @Test void decideRejectCreatesObservationNote() { … service().decide(1L, ProposalDecision.REJECT, "Benedikt"); assertEquals(ProposalStatus.REJECTED, p.getStatus()); verify(noteService).create(eq(NoteKind.OBSERVATION), contains("abgelehnt"), eq(NoteSource.USER), eq(LocalDateTime.of(2026,10,13,12,0))); verifyNoInteractions(executor); }
    @Test void approveAndRememberCreatesPreference() { … verify(noteService).create(eq(NoteKind.PREFERENCE), contains("stimmt zu"), eq(NoteSource.USER), isNull()); }
    @Test void secondDecisionIsRefused() { AgentProposal p = pending(1L); p.setStatus(ProposalStatus.EXECUTED); when(repository.findById(1L)).thenReturn(Optional.of(p)); assertThrows(IllegalStateException.class, () -> service().decide(1L, ProposalDecision.APPROVE, "x")); verifyNoInteractions(executor); }
    @Test void expireStaleMarksOldPendingAndRemovesButtons() { AgentProposal old = pending(1L); old.setCreatedAt(LocalDateTime.of(2026,10,12,9,0)); when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(old, pending(2L))); assertEquals(1, service().expireStale()); assertEquals(ProposalStatus.EXPIRED, old.getStatus()); verify(notifier).markDecided(eq(old), contains("Verfallen")); }
    @Test void deliverPendingSkipsAlreadySatisfied() { AgentProposal held = pending(1L); held.setDeliveredAt(null); when(quietHours.isQuietNow()).thenReturn(false); when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held)); when(executor.isSatisfied(any())).thenReturn(true); service().deliverPending(); assertEquals(ProposalStatus.EXECUTED, held.getStatus()); verify(notifier, never()).sendProposal(any()); }
    @Test void deliverPendingSendsWhenStillRelevant() { … isSatisfied false → sendProposal, deliveredAt gesetzt … }
    @Test void executeDirectSendsInfoOnlyWhenSomethingHappened() { when(executor.execute(any())).thenReturn(new ActionOutcome(true, "schon so")); service().executeDirect(heating21()); verify(notifier, never()).sendInfo(any()); }
    @Test void parseCallbackHandlesGarbage() { assertTrue(AgentProposalService.parseCallback("agent:7:APPROVE").isPresent()); assertTrue(AgentProposalService.parseCallback("agent:x:APPROVE").isEmpty()); assertTrue(AgentProposalService.parseCallback("foo").isEmpty()); }
```

`AgentNotifierTest`: nicht konfiguriert ⇒ `sendProposal` speichert nichts, wirft nicht; konfiguriert mit zwei Chats, einer wirft ⇒ für den anderen wird `AgentProposalMessage` gespeichert; `markDecided` ruft `editReplyMarkup(chat, messageId, List.of())` je Nachricht und `sendMessage(chat, "→ …")`.

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler.

- [ ] **Step 3: Implementierung**

`TelegramApiClient` (Ergänzungen):

```java
    public long sendMessage(long chatId, String text, List<List<InlineButton>> keyboard) {
        String payload = text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", payload);
        if (keyboard != null && !keyboard.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", keyboardRows(keyboard)));
        }
        try {
            JsonNode root = restTemplate.postForObject(apiUrl("sendMessage"), body, JsonNode.class);
            return root == null ? 0L : root.path("result").path("message_id").asLong(0L);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram sendMessage fehlgeschlagen: " + masked(ex), ex);
        }
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, List.of());
    }

    public void editReplyMarkup(long chatId, long messageId, List<List<InlineButton>> keyboard) {
        Map<String, Object> body = Map.of("chat_id", chatId, "message_id", messageId,
                "reply_markup", Map.of("inline_keyboard", keyboardRows(keyboard == null ? List.of() : keyboard)));
        try {
            restTemplate.postForObject(apiUrl("editMessageReplyMarkup"), body, String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram editMessageReplyMarkup fehlgeschlagen: " + masked(ex), ex);
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            restTemplate.postForObject(apiUrl("answerCallbackQuery"),
                    Map.of("callback_query_id", callbackQueryId, "text", text), String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram answerCallbackQuery fehlgeschlagen: " + masked(ex), ex);
        }
    }

    private List<List<Map<String, String>>> keyboardRows(List<List<InlineButton>> keyboard) {
        return keyboard.stream().map(row -> row.stream()
                .map(b -> Map.of("text", b.text(), "callback_data", b.callbackData())).toList()).toList();
    }
```

`AgentQuietHours`:

```java
@Component @RequiredArgsConstructor
public class AgentQuietHours {
    private final AgentProperties properties;
    private final Clock clock;
    public boolean isQuietNow() { return properties.quietWindow().contains(LocalTime.now(clock)); }
}
```

`AgentNotifier` (Textaufbau):

```java
    static final String LABEL_YES = "Ja";
    static final String LABEL_NO = "Nein";
    static final String LABEL_REMEMBER = "Ja, und merken";

    public void sendProposal(AgentProposal p) {
        if (!isConfigured()) { log.debug("Telegram nicht konfiguriert — Vorschlag {} nur in der Admin-Seite", p.getId()); return; }
        String text = "Vorschlag #" + p.getId() + "\n" + p.getReason() + "\n\n→ " + describe(p) + "?";
        List<List<InlineButton>> keyboard = List.of(List.of(
                new InlineButton(LABEL_YES, callback(p, ProposalDecision.APPROVE)),
                new InlineButton(LABEL_NO, callback(p, ProposalDecision.REJECT)),
                new InlineButton(LABEL_REMEMBER, callback(p, ProposalDecision.APPROVE_AND_REMEMBER))));
        for (long chatId : telegramProperties.getAllowedChatIds()) {
            try {
                long messageId = apiClient.sendMessage(chatId, text, keyboard);
                messageRepository.save(AgentProposalMessage.builder().proposalId(p.getId()).chatId(chatId).messageId(messageId).build());
            } catch (Exception ex) {
                log.warn("Vorschlag {} an Chat {} nicht zustellbar: {}", p.getId(), chatId, ex.getMessage());
            }
        }
    }
```

`describe(p)` = `actionOf(p).describe()` — dafür bekommt `AgentNotifier` den `ObjectMapper` und eine kleine statische Hilfe `AgentActionJson.read(String)` / `write(AgentAction)` (neue Klasse `agent/AgentActionJson.java`, von Notifier und ProposalService geteilt).

`AgentProposalService.decide`:

```java
    @Transactional
    public AgentProposal decide(long id, ProposalDecision decision, String decidedBy) {
        AgentProposal p = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Unbekannter Vorschlag: " + id));
        if (p.getStatus() != ProposalStatus.PENDING) throw new IllegalStateException("Vorschlag #" + id + " ist bereits entschieden (" + p.getStatus() + ").");
        AgentAction action = AgentActionJson.read(objectMapper, p.getActionJson());
        LocalDateTime now = LocalDateTime.now(clock);
        p.setDecidedBy(decidedBy);
        p.setDecidedAt(now);
        String label;
        if (decision == ProposalDecision.REJECT) {
            p.setStatus(ProposalStatus.REJECTED);
            noteService.create(NoteKind.OBSERVATION, decidedBy + " hat abgelehnt: " + action.describe(), NoteSource.USER, now.plusHours(24));
            label = "Abgelehnt von " + decidedBy;
        } else {
            p.setStatus(ProposalStatus.APPROVED);
            if (decision == ProposalDecision.APPROVE_AND_REMEMBER) {
                noteService.create(NoteKind.PREFERENCE, decidedBy + " stimmt zu: " + action.describe() + " (" + p.getReason() + ")", NoteSource.USER, null);
            }
            label = runAction(p, action, "Ausgeführt von " + decidedBy);
        }
        repository.save(p);
        auditService.record("agent.proposal.decide", "#" + id + " " + decision + " durch " + decidedBy);
        notifier.markDecided(p, label);
        return p;
    }

    private String runAction(AgentProposal p, AgentAction action, String successLabel) {
        try {
            ActionOutcome outcome = executor.execute(action);
            p.setStatus(ProposalStatus.EXECUTED);
            return outcome.alreadySatisfied() ? "War bereits so" : successLabel;
        } catch (Exception ex) {
            p.setStatus(ProposalStatus.FAILED);
            p.setError(abbreviate(ex.getMessage(), 1000));
            return "Fehlgeschlagen: " + ex.getMessage();
        }
    }
```

`propose` prüft **nicht** die Leitplanken (das macht die Schleuse in Task 6), sondern nur Persistenz + Zustellung. `AgentActionExecutor.isSatisfied(AgentAction)` in Task 3 nachziehen: gleiche Lesungen wie `execute`, ohne Aufrufe und ohne Audit (Refactoring: `execute` nutzt intern dieselbe Prüfmethode).

- [ ] **Step 4: Tests** — `mvn -q test -Dtest='TelegramApiClientTest,AgentQuietHoursTest,AgentNotifierTest,AgentProposalServiceTest,AgentActionExecutorTest'` PASS.

- [ ] **Step 5: Commit** — `feat(agent): Vorschlaege mit Telegram-Knoepfen, Ruhezeit und Entscheidung`

---

### Task 6: Schleuse und Aktions-/Lese-Tools des Hausverstands

**Files:**
- Create: `agent/AgentActionGate.java`, `agent/GateResult.java`
- Create: `agent/tools/SetHeatingTool.java`, `SetSwitchTool.java` (Name `house_set_switch`), `SetModeTool.java` (`house_set_mode`), `NotifyTool.java`, `LockDoorTool.java` (`house_lock_door`), `GetTemperatureHistoryTool.java`, `GetCalendarTool.java`
- Test: `agent/AgentActionGateTest.java`, `agent/tools/HouseActionToolsTest.java`

**Interfaces:**
- `GateResult(String messageForModel, Long proposalId, boolean executed)`.
- `AgentActionGate(AgentPolicyService, AgentProposalService, AgentProposalRepository, AgentProperties)`: `GateResult submit(AgentAction action)`:
  1. Leitplanken: HEATING `targetTemp` außerhalb 5,0–24,0 ⇒ `IllegalArgumentException("Zieltemperatur … liegt außerhalb 5–24 °C")`; `mode` nicht in `ThermostatMode` ⇒ Fehler; SWITCH/MODE `state` ∉ {on, off} ⇒ Fehler; NOTIFY `message` leer ⇒ Fehler.
  2. `policyFor(class) == DIRECT` ⇒ `proposalService.executeDirect(action)` ⇒ `GateResult("Ausgeführt: <summary>", id, true)`.
  3. sonst: `existsByStatusAndTarget(PENDING, target)` ⇒ `GateResult("Für <target> ist bereits Vorschlag offen — nicht erneut vorschlagen.", null, false)`; `countByStatus(PENDING) >= maxOpenProposals` ⇒ `GateResult("Es sind bereits 3 Vorschläge offen — keine weiteren, bis entschieden wurde.", null, false)`; sonst `propose` ⇒ `GateResult("Als Vorschlag #<id> gestellt (Telegram). Nicht erneut vorschlagen.", id, false)`.
- Tools (`AgentScope.HOUSE`), jedes baut eine `AgentAction` und gibt `gate.submit(action).messageForModel()` zurück; Pflichtfeld `reason` (ein Satz, deutsch) und optional `urgent` (nur NotifyTool):
  - `set_heating`: `thermostatId` (integer), `targetTemp` (number, optional), `mode` (enum MANUAL/OFF/FROST_PROTECTION, optional), `reason`.
  - `house_set_switch`: `entityId`, `state` (on/off), `reason`.
  - `house_set_mode`: `entityId`, `state`, `reason`.
  - `notify`: `channel` (telegram/push/alexa), `message`, `urgent` (boolean, optional), `reason`.
  - `house_lock_door`: `smartlockId` (optional, wie Telegram-Tool über `NukiLockService.listLocks()` aufgelöst), `reason`.
  - `get_temperature_history`: `sensorId` (aus dem Snapshot) ⇒ `TemperatureSeriesService.getSensorSeries(sensorId, SeriesRange.DAY)` als Text (max. 48 Punkte, „HH:mm: 21,3 °C").
  - `get_calendar`: `days` (1–14) ⇒ `CalendarEventService.getUpcoming(20)` gefiltert auf das Fenster, als Zeilen „2026-10-13 08:00 Zahnarzt (Benedikt)".

- [ ] **Step 1: Failing Tests**

`AgentActionGateTest`:

```java
    @Test void heatingAboveCorridorIsRefusedBeforeAnyPolicyLookup() { assertThrows(IllegalArgumentException.class, () -> gate().submit(heating(26.0))); verifyNoInteractions(policyService, proposalService); }
    @Test void heatingBelowCorridorIsRefused() { assertThrows(IllegalArgumentException.class, () -> gate().submit(heating(4.5))); }
    @Test void directPolicyExecutes() { when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.DIRECT); when(proposalService.executeDirect(any())).thenReturn(executed(9L)); GateResult r = gate().submit(heating(21.0)); assertTrue(r.executed()); verify(proposalService).executeDirect(any()); verify(proposalService, never()).propose(any()); }
    @Test void proposePolicyCreatesProposal() { when(policyService.policyFor(ActionClass.HEATING)).thenReturn(ActionPolicy.PROPOSE); when(repository.existsByStatusAndTarget(ProposalStatus.PENDING, "3")).thenReturn(false); when(repository.countByStatus(ProposalStatus.PENDING)).thenReturn(0L); when(proposalService.propose(any())).thenReturn(pending(4L)); GateResult r = gate().submit(heating(21.0)); assertEquals(4L, r.proposalId()); assertTrue(r.messageForModel().contains("#4")); }
    @Test void duplicateOpenTargetIsBlocked() { … existsByStatusAndTarget true → kein propose, Text enthält "bereits" … }
    @Test void openLimitIsBlocked() { … countByStatus 3 → kein propose … }
    @Test void notifyIsDirectEvenWithoutPolicyRow() { when(policyService.policyFor(ActionClass.NOTIFY)).thenReturn(ActionPolicy.DIRECT); when(proposalService.executeDirect(any())).thenReturn(executed(1L)); gate().submit(new AgentAction(ActionClass.NOTIFY, "telegram", Map.of("channel","telegram","message","Hi"), "r", false)); verify(proposalService).executeDirect(any()); }
    @Test void invalidSwitchStateIsRefused() { assertThrows(IllegalArgumentException.class, () -> gate().submit(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "toggle"), "r", false))); }
```

`HouseActionToolsTest`: je Tool ein Test, dass die gebaute `AgentAction` Klasse/Ziel/Parameter trägt (ArgumentCaptor auf `gate.submit`), `scope()==HOUSE`, und `set_heating` ohne `targetTemp` **und** ohne `mode` mit `IllegalArgumentException` ablehnt.

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler.

- [ ] **Step 3: Implementierung** nach obigem Vertrag. `AgentActionGate.submit`:

```java
    public GateResult submit(AgentAction action) {
        validate(action);
        if (policyService.policyFor(action.actionClass()) == ActionPolicy.DIRECT) {
            AgentProposal row = proposalService.executeDirect(action);
            return new GateResult(row.getStatus() == ProposalStatus.FAILED
                    ? "Fehlgeschlagen: " + row.getError()
                    : "Ausgeführt: " + action.describe(), row.getId(), true);
        }
        if (repository.existsByStatusAndTarget(ProposalStatus.PENDING, action.target())) {
            return new GateResult("Für " + action.target() + " ist bereits ein Vorschlag offen — nicht erneut vorschlagen.", null, false);
        }
        if (repository.countByStatus(ProposalStatus.PENDING) >= properties.getMaxOpenProposals()) {
            return new GateResult("Es sind bereits " + properties.getMaxOpenProposals() + " Vorschläge offen — keine weiteren, bis entschieden wurde.", null, false);
        }
        AgentProposal row = proposalService.propose(action);
        return new GateResult("Als Vorschlag #" + row.getId() + " gestellt (Telegram). Nicht erneut vorschlagen.", row.getId(), false);
    }

    static final double MIN_TARGET = 5.0;
    static final double MAX_TARGET = 24.0;

    private void validate(AgentAction action) {
        switch (action.actionClass()) {
            case HEATING -> {
                Double t = action.doubleParam("targetTemp");
                String mode = action.stringParam("mode");
                if (t == null && mode == null) throw new IllegalArgumentException("targetTemp oder mode angeben");
                if (t != null && (!Double.isFinite(t) || t < MIN_TARGET || t > MAX_TARGET))
                    throw new IllegalArgumentException("Zieltemperatur " + t + " liegt außerhalb " + MIN_TARGET + "–" + MAX_TARGET + " °C");
                if (mode != null) ThermostatMode.valueOf(mode);
            }
            case SWITCH, MODE -> {
                String s = action.stringParam("state");
                if (!"on".equals(s) && !"off".equals(s)) throw new IllegalArgumentException("state muss on oder off sein");
            }
            case NOTIFY -> { if (action.stringParam("message") == null || action.stringParam("message").isBlank()) throw new IllegalArgumentException("message fehlt"); }
            case LOCK -> { /* nur Verriegeln existiert */ }
        }
        if (action.reason() == null || action.reason().isBlank()) throw new IllegalArgumentException("reason fehlt");
    }
```

- [ ] **Step 4: Tests** PASS. **Step 5: Commit** — `feat(agent): Aktionsschleuse und Werkzeuge des Hausverstands`

---

### Task 7: `HouseSnapshotBuilder`

**Files:**
- Create: `agent/HouseSnapshotBuilder.java`
- Test: `agent/HouseSnapshotBuilderTest.java`

**Interfaces:**
- `HouseSnapshotBuilder(Clock, PresenceStatusService, HouseModeQueryService, ThermostatService, TemperatureSeriesService, EntityStateService, EntityStateResponseMapper, PowerConsumerQueryService, PetSupplyService, CalendarEventService, NetworkStatusService, ZigbeeStreamMonitor, NukiLockService, AgentProposalService, AgentNoteService, AgentPolicyService)`: `String build()`. Abschnitte in dieser Reihenfolge, jeder über `section(String title, Supplier<String> body)`, das Exceptions fängt und „<title>: nicht abrufbar (<ex.getMessage()>)" schreibt:
  1. `Zeit`: „Montag, 12.10.2026 14:03, Sonnenphase: day (sonnenuntergang 18:31)" — aus `entityStateService.getByEntityId("sensor.sun")`.
  2. `Anwesenheit`: je Person „Benedikt: zu Hause (zuletzt 13:58)" / „abwesend seit …" / „unbekannt"; Haushalt gesamt.
  3. `Modi`: „Nachtmodus: aus, Abwesend: aus, Toni allein: an".
  4. `Heizung`: je Thermostat „Wohnzimmer (id 3): ist 18,5 °C, soll 17,0 °C, MANUAL, heizt nicht, Stand 14:02" — offline ⇒ „offline".
  5. `Temperaturen`: je Sensor „Wohnzimmer (sensorId zigbee:12): 20,1 °C / 48 %, vor 3 min"; Alter > 60 min mit „VERALTET".
  6. `Kontakte offen`: Zigbee-`BINARY_SENSOR` mit `deviceClass=door` und `state=on` ⇒ Namen; keine ⇒ „keine"; `unavailable` ⇒ „<Name>: unbekannt".
  7. `Verbraucher`: Top 10 „Waschmaschine: 540 W".
  8. `Helfer`: alle `INPUT_BOOLEAN`/`MANUAL` ohne Modus-Marker, „Waschmaschine läuft: an (seit 13:40)".
  9. `Vorräte`: „Futter: 12 Dosen, reicht 12 Tage".
  10. `Termine`: nächste 5 aus `getUpcoming(5)`.
  11. `Netzwerk`: online/offline, Latenz.
  12. `Zigbee`: `status().health()`.
  13. `Haustür`: je Schloss state/doorState.
  14. `Offene und letzte Vorschläge (24 h)`: „#17 PENDING Heizung 3 auf 21 °C — kalt bei Anwesenheit", „#16 REJECTED …".
  15. `Freigaben`: „HEATING: nur vorschlagen; SWITCH: direkt; …".
  16. `Notizen`: `noteService.renderForPrompt()`.
  Gesamtlänge auf 12 000 Zeichen gedeckelt (Abschneiden mit „… [gekürzt]").

- [ ] **Step 1: Failing Tests** (alle Abhängigkeiten gemockt, `lenient()`-Defaults liefern leere Listen):

```java
    @Test void failingSourceIsReportedNotThrown() { when(presenceStatusService.getStatus()).thenThrow(new RuntimeException("Tractive 401")); String s = builder().build(); assertTrue(s.contains("Anwesenheit: nicht abrufbar")); assertTrue(s.contains("Modi:")); }
    @Test void staleTemperatureIsMarked() { … CurrentTemperatureReading measuredAt now-90min → enthält "VERALTET" … }
    @Test void openDoorContactsAreListedByName() { … EntityState BINARY_SENSOR ZIGBEE attributes {"deviceClass":"door"} state on customName "Terrassentür" → "Terrassentür" unter "Kontakte offen" … }
    @Test void unavailableContactIsReportedAsUnknownNotClosed() { … state unavailable → "unbekannt" … }
    @Test void policiesAndNotesAreIncluded() { when(policyService.all()).thenReturn(Map.of(ActionClass.HEATING, ActionPolicy.PROPOSE, …)); when(noteService.renderForPrompt()).thenReturn("#1 [PREFERENCE, USER, unbefristet] 21 Grad reichen"); String s = builder().build(); assertTrue(s.contains("HEATING: nur vorschlagen")); assertTrue(s.contains("21 Grad reichen")); }
    @Test void snapshotIsCapped() { when(noteService.renderForPrompt()).thenReturn("x".repeat(20000)); assertTrue(builder().build().length() <= 12_050); }
```

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler. **Step 3: Implementierung** nach obigem Vertrag (`section`-Helfer, deutsche `DateTimeFormatter` mit `Locale.GERMAN`). **Step 4: Tests** PASS. **Step 5: Commit** — `feat(agent): Hauszustand als Snapshot fuer den Systemprompt`

---

### Task 8: `AgentRunService`, Laufprotokoll, Retention, Fehlerserie

**Files:**
- Create: `agent/AgentTrigger.java`, `agent/AgentRunResult.java`, `agent/AgentRunService.java`, `agent/AgentRunRecorder.java`, `agent/AgentRunRetentionJob.java`, `agent/AgentFailureStreak.java`
- Test: `agent/AgentRunServiceTest.java`, `agent/AgentRunRecorderTest.java`, `agent/AgentFailureStreakTest.java`

**Interfaces:**
- `AgentTrigger(AgentTriggerType type, List<String> details)`; `String describe()` („Grundtakt" / „Wecker: a, b" / „Manuell").
- `AgentRunResult(boolean success, String summary, String error, int inputTokens, int outputTokens, int toolCalls, long durationMs)`.
- `AgentRunRecorder(AgentRunRepository, Clock)`: `void record(AgentTrigger, AgentRunResult, String model, LocalDateTime startedAt)` wirft nie; `List<AgentRun> recent()`; `long[] tokensToday()`.
- `AgentFailureStreak(AgentNotifier)`: `void onResult(AgentRunResult)` — bei der 3. Fehlserie in Folge **einmal** `notifier.sendInfo("Hausverstand: 3 Läufe in Folge fehlgeschlagen — …")`, dann still bis zum nächsten Erfolg (setzt zurück).
- `AgentRunService(AgentProperties, AnthropicApiClient, AgentToolRegistry, HouseSnapshotBuilder, AgentRunRecorder, AgentFailureStreak, Clock)`: `AgentRunResult run(AgentTrigger trigger)` — wirft nie; setzt `AuditActorContext.set(AuditActor.agent())` und räumt im `finally`; Zeitbudget über `System.nanoTime()` je Iteration (Deckel überschritten ⇒ Abbruch mit Fehler „Zeitbudget überschritten"); Systemprompt = `AnthropicRequest.cachedBlock(STATIC_RULES)` + `block(snapshot)`; Nutzernachricht = `trigger.describe() + "\n\nPrüfe, ob etwas zu tun ist. Wenn nein, antworte mit einem Satz Begründung und ohne Tool-Aufruf."`.
- `STATIC_RULES` (Konstante, Deutsch): Rolle („Du bist der Hausverstand des Household Managers …"), die festen Regeln aus der Spec (24-h-Sperre, Komfort nur bei Anwesenheit, im Zweifel nichts tun, Sicherheitsbeobachtungen immer melden und `urgent` markieren, Anzeigenamen statt IDs, höchstens drei Vorschläge je Lauf, Begründungen in einem Satz, Beobachtungen immer mit `expiresInHours`).

- [ ] **Step 1: Failing Tests**

`AgentRunServiceTest` (Mocks; `properties.maxToolIterations=3`, `runTimeoutSeconds=90`):

```java
    @Test void plainAnswerRecordsSummaryWithoutToolCalls() { when(snapshotBuilder.build()).thenReturn("SNAP"); when(client.createMessage(any())).thenReturn(endTurn("Nichts zu tun.", 500, 20)); AgentRunResult r = service().run(scheduled()); assertTrue(r.success()); assertEquals("Nichts zu tun.", r.summary()); assertEquals(500, r.inputTokens()); assertEquals(0, r.toolCalls()); verify(recorder).record(any(), eq(r), eq("claude-sonnet-4-5"), any()); }
    @Test void systemPromptContainsCachedRulesAndSnapshot() { … ArgumentCaptor<AnthropicRequest>; system.get(0) hat cache_control; system.get(1).text enthält "SNAP"; tools == registry.toolDefinitions(HOUSE) … }
    @Test void toolCallsAreExecutedInHouseScopeAndFedBack() { … erste Antwort tool_use "notify", zweite end_turn; verify(registry).execute(eq(AgentScope.HOUSE), eq("notify"), any()); r.toolCalls()==1 … }
    @Test void iterationCapEndsRunAsFailure() { … immer tool_use → r.success()==false, error enthält "Iterationen" … }
    @Test void apiFailureIsRecordedNotThrown() { when(client.createMessage(any())).thenThrow(new AiException("down", null)); AgentRunResult r = service().run(scheduled()); assertFalse(r.success()); assertTrue(r.error().contains("down")); verify(recorder).record(any(), any(), any(), any()); verify(failureStreak).onResult(r); }
    @Test void snapshotFailureIsRecordedNotThrown() { when(snapshotBuilder.build()).thenThrow(new RuntimeException("db")); assertFalse(service().run(scheduled()).success()); }
    @Test void auditActorIsAgentDuringRunAndClearedAfter() { when(client.createMessage(any())).thenAnswer(inv -> { assertEquals(AuditActor.agent(), AuditActorContext.get()); return endTurn("ok", 1, 1); }); service().run(scheduled()); assertNull(AuditActorContext.get()); }
```

`AgentFailureStreakTest`: zwei Fehler ⇒ keine Nachricht; dritter ⇒ genau eine; vierter ⇒ keine weitere; Erfolg danach + drei neue Fehler ⇒ wieder eine.

`AgentRunRecorderTest`: `record` speichert alle Felder, kürzt `summary` auf 2000 und `error` auf 1000; Repository-Exception wird geschluckt.

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler. **Step 3: Implementierung**

Kern von `AgentRunService.run`:

```java
    public AgentRunResult run(AgentTrigger trigger) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long startNanos = System.nanoTime();
        AuditActorContext.set(AuditActor.agent());
        int inputTokens = 0, outputTokens = 0, toolCalls = 0;
        AgentRunResult result;
        try {
            String snapshot = snapshotBuilder.build();
            List<Map<String, Object>> system = List.of(AnthropicRequest.cachedBlock(STATIC_RULES), AnthropicRequest.block(snapshot));
            List<AnthropicMessage> messages = new ArrayList<>();
            messages.add(AnthropicMessage.user(trigger.describe() + "\n\n" + TASK_INSTRUCTION));
            List<Map<String, Object>> tools = toolRegistry.toolDefinitions(AgentScope.HOUSE);
            result = null;
            for (int i = 0; i < properties.getMaxToolIterations() && result == null; i++) {
                if (elapsedSeconds(startNanos) > properties.getRunTimeoutSeconds()) {
                    result = failure("Zeitbudget überschritten", inputTokens, outputTokens, toolCalls, startNanos);
                    break;
                }
                AnthropicResponse response = client.createMessage(new AnthropicRequest(properties.getModel(), properties.getMaxTokens(), system, messages, tools));
                inputTokens += response.inputTokens();
                outputTokens += response.outputTokens();
                if (!response.wantsToolUse()) {
                    result = new AgentRunResult(true, response.text(), null, inputTokens, outputTokens, toolCalls, elapsedMs(startNanos));
                } else {
                    messages.add(AnthropicMessage.assistant(response.content()));
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (Map<String, Object> block : response.toolUseBlocks()) {
                        toolCalls++;
                        ToolResult tr = toolRegistry.execute(AgentScope.HOUSE, String.valueOf(block.get("name")), (Map<String, Object>) block.get("input"));
                        results.add(toolResultBlock(String.valueOf(block.get("id")), tr));
                    }
                    messages.add(AnthropicMessage.toolResults(results));
                }
            }
            if (result == null) result = failure("Nach " + properties.getMaxToolIterations() + " Iterationen abgebrochen", inputTokens, outputTokens, toolCalls, startNanos);
        } catch (Exception ex) {
            log.warn("Hausverstand-Lauf fehlgeschlagen: {}", ex.getMessage(), ex);
            result = failure(ex.getMessage(), inputTokens, outputTokens, toolCalls, startNanos);
        } finally {
            AuditActorContext.clear();
        }
        recorder.record(trigger, result, properties.getModel(), startedAt);
        failureStreak.onResult(result);
        return result;
    }
```

(`toolResultBlock` baut `{"type":"tool_result","tool_use_id":id,"content":content,"is_error":error}` — identisch zu `TelegramAgentService.executeTools`; diese Hilfsmethode nach `ai/AnthropicMessage.toolResult(id, ToolResult)` ziehen und in beiden Services nutzen.)

`AgentRunRetentionJob`: `@Scheduled(cron = "0 50 3 * * *")`, `deleteByStartedAtBefore(now - runRetentionDays)`, Muster `ThermostatHistoryRetentionJob`.

- [ ] **Step 4: Tests** PASS. **Step 5: Commit** — `feat(agent): Lauf des Hausverstands mit Protokoll und Fehlerserie`

---

### Task 9: `AgentWakeService` und Wartungsjob

**Files:**
- Create: `agent/AgentWakeService.java`, `agent/AgentExecutorConfig.java`, `agent/AgentMaintenanceJob.java`
- Test: `agent/AgentWakeServiceTest.java`, `agent/AgentMaintenanceJobTest.java`

**Interfaces:**
- `AgentExecutorConfig`: Bean `@Bean("agentExecutor") ExecutorService` = `Executors.newSingleThreadExecutor` (Daemon-Thread `house-agent`).
- `AgentWakeService(AgentProperties, AgentSettingsService, AgentRunService, @Qualifier("agentExecutor") ExecutorService, Clock)`:
  - `@Scheduled(fixedDelayString = "${agent.tick-interval-ms}", initialDelayString = "${agent.initial-delay-ms}") void tick()` ⇒ `submit(new AgentTrigger(SCHEDULED, List.of()))`.
  - `@EventListener void onEntityStateChanged(EntityStateChangedEvent e)`: nur wenn `wakeEntities.contains(e.entityId())`; sammelt `"<entityId>: <old> → <new> um HH:mm"` in `pendingReasons` (synchronisiert); erster Wecker startet einen `ScheduledFuture` nach `wakeDebounceMs` (über ein `ScheduledExecutorService`-Feld, einthreadig), der `submit(WAKE, reasons)` ruft.
  - `submit(trigger)`: wenn `!settings.isActive()` ⇒ verwerfen (debug-Log); wenn `running.compareAndSet(false, true)` ⇒ `executor.submit(() -> { try { runService.run(trigger); } finally { running.set(false); if (followUp.getAndSet(false)) submit(WAKE, …); } })`; sonst `followUp.set(true)` und Gründe aufheben.
  - `AgentRunResult runNow()` (manuell): `!properties.isEnabled()`/`!settings.isActive()` ⇒ `IllegalStateException`; Mindestabstand `manualRunMinIntervalMs` ⇒ `TooManyRequestsException`; läuft **synchron** auf dem Aufrufer-Thread (wie `presence/refresh`), aber nur, wenn gerade kein Lauf aktiv ist (sonst 429).
- `AgentMaintenanceJob(AgentProposalService, AgentNoteService)`: `@Scheduled(fixedDelay = 60_000) void minute()` ⇒ `expireStale()`, `deliverPending()`, `purgeExpired()` — jeder Aufruf in eigenem try/catch.

- [ ] **Step 1: Failing Tests**

`AgentWakeServiceTest` (synchroner Test-Executor: `ExecutorService` aus `Executors.newSingleThreadExecutor()` + `awaitTermination`-Hilfe, oder ein direkt ausführender Stub, der `submit` sofort ausführt; für den Debounce ein injizierbarer `ScheduledExecutorService`-Stub, der den Runnable festhält und per `fire()` auslöst):

```java
    @Test void inactiveSwitchDropsWake() { when(settings.isActive()).thenReturn(false); service().onEntityStateChanged(event("binary_sensor.presence_household")); debounce.fire(); verifyNoInteractions(runService); }
    @Test void unlistedEntityIsIgnored() { service().onEntityStateChanged(event("sensor.irgendwas")); assertFalse(debounce.isScheduled()); }
    @Test void listedEntityStartsDebouncedRunWithReason() { service().onEntityStateChanged(event("binary_sensor.presence_household")); debounce.fire(); verify(runService).run(argThat(t -> t.type() == AgentTriggerType.WAKE && t.details().get(0).contains("presence_household"))); }
    @Test void twoWakesWithinDebounceBecomeOneRun() { service().onEntityStateChanged(event("binary_sensor.presence_household")); service().onEntityStateChanged(event("sensor.sun")); debounce.fire(); verify(runService, times(1)).run(argThat(t -> t.details().size() == 2)); }
    @Test void wakeDuringRunCausesExactlyOneFollowUp() { … runService.run blockiert über CountDownLatch; waehrenddessen zwei Wecker; Latch frei → insgesamt genau 2 Laeufe … }
    @Test void runNowRespectsMinInterval() { service().runNow(); assertThrows(TooManyRequestsException.class, () -> service().runNow()); }
    @Test void runNowRefusesWhileRunActive() { … running gesetzt → TooManyRequestsException … }
```

`AgentMaintenanceJobTest`: wirft `expireStale` ⇒ `deliverPending` und `purgeExpired` laufen trotzdem.

- [ ] **Step 2: Tests laufen lassen** — Compile-Fehler. **Step 3: Implementierung** nach Vertrag; Konstruktor für Tests: `AgentWakeService(AgentProperties, AgentSettingsService, AgentRunService, ExecutorService agentExecutor, ScheduledExecutorService debounceScheduler, Clock)`; die Spring-Variante bekommt den `debounceScheduler` aus `AgentExecutorConfig` (`@Bean("agentDebounceScheduler") ScheduledExecutorService`). `@PreDestroy` fährt beide Executoren herunter.

- [ ] **Step 4: Tests** PASS. **Step 5: Commit** — `feat(agent): Grundtakt, Weckerliste und Wartungsjob des Hausverstands`

---

### Task 10: Telegram-Knopfdrücke verarbeiten

**Files:**
- Modify: `telegram/TelegramPollingService.java`
- Test: `telegram/TelegramPollingServiceTest.java` (erweitern)

**Interfaces:**
- `TelegramPollingService` bekommt `AgentProposalService proposalService` (Konstruktor). `handleUpdate`: wenn `update.callbackQuery() != null`: Chat-ID aus `callbackQuery.message().chat().id()`; nicht in Allowlist ⇒ warn + return; `AgentProposalService.parseCallback(data)` leer ⇒ `answerCallbackQuery(id, "Unbekannter Knopf")`; sonst `decide(proposalId, decision, name)` mit `name = from.firstName()` (leer ⇒ `"Chat " + chatId`); Erfolg ⇒ `answerCallbackQuery(id, "Erledigt")`; `IllegalStateException` ⇒ `answerCallbackQuery(id, ex.getMessage())`; jede andere Exception ⇒ warn + `answerCallbackQuery(id, "Fehler, siehe Admin-Seite")`. Textnachrichten unverändert.

- [ ] **Step 1: Failing Tests**

```java
    @Test void callbackFromAllowedChatDecidesProposal() { TelegramUpdate u = new TelegramUpdate(1L, null, new TelegramCallbackQuery("cb1", new TelegramUser(9L, "Benedikt"), new TelegramMessage(new TelegramChat(100L), null, 42L), "agent:7:APPROVE")); service.handleUpdate(u); verify(proposalService).decide(7L, ProposalDecision.APPROVE, "Benedikt"); verify(apiClient).answerCallbackQuery("cb1", "Erledigt"); verifyNoInteractions(agentService); }
    @Test void callbackFromForeignChatIsIgnored() { … chat 999 → verifyNoInteractions(proposalService) … }
    @Test void alreadyDecidedIsReportedToUser() { when(proposalService.decide(anyLong(), any(), any())).thenThrow(new IllegalStateException("bereits entschieden")); … verify(apiClient).answerCallbackQuery("cb1", "bereits entschieden"); }
    @Test void garbageCallbackDataIsAnswered() { … data "foo" → answerCallbackQuery(…, "Unbekannter Knopf"), keine decide … }
```

- [ ] **Step 2–5:** Test rot → Implementierung → `mvn -q test -Dtest=TelegramPollingServiceTest` PASS → Commit `feat(telegram): Inline-Knoepfe des Hausverstands verarbeiten`

---

### Task 11: REST-API, Security, Audit

**Files:**
- Create: `agent/AgentController.java`, `agent/AgentDtos.java`
- Modify: `security/SecurityConfig.java` (Matcher), `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`
- Test: `agent/AgentControllerTest.java` (`@WebMvcTest`-Muster wie bestehende Controller-Tests, z. B. `MeterReadingSeriesControllerTest`)

**Interfaces:**
- DTOs (Records in `AgentDtos`): `SummaryResponse(long pendingProposals, boolean active)`; `SettingsDto(boolean active)`; `PolicyDto(ActionClass actionClass, ActionPolicy policy)`; `PolicyUpdateRequest(ActionPolicy policy)`; `NoteDto(Long id, NoteKind kind, String text, NoteSource source, LocalDateTime createdAt, LocalDateTime expiresAt)`; `NoteRequest(@NotNull NoteKind kind, @NotBlank @Size(max=500) String text, LocalDateTime expiresAt)`; `ProposalDto(Long id, ActionClass actionClass, String target, String description, String reason, boolean urgent, ProposalStatus status, String decidedBy, LocalDateTime decidedAt, LocalDateTime deliveredAt, String error, LocalDateTime createdAt)`; `DecisionRequest(@NotNull ProposalDecision decision)`; `RunDto(Long id, AgentTriggerType triggerType, String triggerDetail, LocalDateTime startedAt, long durationMs, String model, int inputTokens, int outputTokens, int toolCalls, String summary, String error)`; `RunsResponse(List<RunDto> runs, long inputTokensToday, long outputTokensToday)`.
- Endpunkte unter `/v1/agent` (Pfade exakt wie Spec): `GET /summary`; `GET|PUT /settings`; `GET /policies`, `PUT /policies/{actionClass}`; `GET /notes`, `POST /notes` (201), `PUT /notes/{id}`, `DELETE /notes/{id}` (204, `NoteSource.USER`); `GET /proposals?limit`; `POST /proposals/{id}/decide` (decidedBy = Anzeigename des angemeldeten Nutzers über `CurrentUserService`/`AppUserRepository`, Fallback „Admin"); `GET /runs`; `POST /runs` (liefert `RunDto` des gerade gelaufenen Laufs; `TooManyRequestsException` ⇒ 429, `IllegalStateException` ⇒ 400).
- `SecurityConfig`: **vor** dem ADMIN-Block `.requestMatchers(HttpMethod.GET, "/v1/agent/summary").hasRole("KIOSK")` (direkt neben `mode-quick-access/due`), im ADMIN-Block `"/v1/agent", "/v1/agent/**"` ergänzen (Kommentar: Notizen enthalten Gewohnheiten des Haushalts).

- [ ] **Step 1: Failing Tests**

`SecurityRulesTest` ergänzen:

```java
    @Test @WithMockUser(roles = "KIOSK") void kioskDarfAgentSummaryLesen() throws Exception { mockMvc.perform(get("/v1/agent/summary")).andExpect(status().isOk()); }
    @Test @WithMockUser(roles = "KIOSK") void kioskDarfAgentNotizenNichtLesen() throws Exception { mockMvc.perform(get("/v1/agent/notes")).andExpect(status().isForbidden()); }
    @Test @WithMockUser(roles = "MEMBER") void memberDarfAgentNichtKonfigurieren() throws Exception { mockMvc.perform(get("/v1/agent/policies")).andExpect(status().isForbidden()); mockMvc.perform(put("/v1/agent/settings").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isForbidden()); }
    @Test @WithMockUser(roles = "ADMIN") void adminDarfAgentKonfigurieren() throws Exception { mockMvc.perform(get("/v1/agent/policies")).andExpect(status().isOk()); }
```

(Wie die Bestandstests die Service-Beans mocken, dort nachschlagen und für `AgentSettingsService`, `AgentPolicyService`, `AgentNoteService`, `AgentProposalService`, `AgentRunRecorder`, `AgentWakeService` nachziehen.)

`AgentControllerTest`: `POST /proposals/{id}/decide` reicht `decision` durch; `POST /runs` mit `TooManyRequestsException` ⇒ 429; `DELETE /notes/{id}` ruft `delete(id, USER)`.

- [ ] **Step 2–5:** rot → Implementierung (Controller thin, `@Valid`, Audit liegt in den Services) → `mvn -q test -Dtest='SecurityRulesTest,AgentControllerTest'` PASS → Commit `feat(agent): REST-API und Security des Hausverstands`

---

### Task 12: Frontend Admin-Seite „Hausverstand"

**Files:**
- Create: `frontend/src/app/models/agent.model.ts`, `frontend/src/app/services/agent.service.ts`, `frontend/src/app/pages/admin-agent/admin-agent.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/app.routes.ts` (Route `admin/agent`, `adminGuard`, Titel „Hausverstand - Household Manager"), `frontend/src/app/components/header/header.component.ts:83` (Eintrag `{ path: '/admin/agent', label: 'Hausverstand', minRole: 'ADMIN' }`), `frontend/src/styles.scss:574` (`.admin-agent` in `$admin-scopes`)

**Interfaces:**
- `agent.model.ts`: Typen 1:1 zu `AgentDtos` (`AgentSummary`, `AgentSettings`, `AgentPolicy`, `AgentNote`, `AgentNoteRequest`, `AgentProposal`, `AgentRun`, `AgentRunsResponse`, Union-Typen `ActionClass`, `ActionPolicy`, `NoteKind`, `ProposalStatus`, `ProposalDecision`).
- `AgentService` (`providedIn: 'root'`, Basis `/api/v1/agent`): `getSummary()`, `getSettings()`, `updateSettings(active)`, `getPolicies()`, `updatePolicy(actionClass, policy)`, `getNotes()`, `createNote(req)`, `updateNote(id, req)`, `deleteNote(id)`, `getProposals(limit)`, `decide(id, decision)`, `getRuns()`, `runNow()`.
- Komponente `AdminAgentComponent` (Wurzelklasse `admin-agent`, Muster `AdminPresenceComponent` mit Signals): vier Abschnitte — Betrieb (Schalter + Banner „Telegram nicht konfiguriert" wenn `getSummary` … nein: Banner kommt aus einem neuen Feld `telegramConfigured` in `SummaryResponse` (Task 11 nachziehen: `SummaryResponse(long pendingProposals, boolean active, boolean telegramConfigured)`); Freigaben (je Klasse ein `<select>` PROPOSE/DIRECT, NOTIFY disabled mit Hinweis); Notizen (Liste mit Quelle/Art/Ablauf, Formular anlegen, inline bearbeiten, löschen mit `confirm()`); Vorschläge (Tabelle, offene mit Knöpfen Ja/Nein/Ja und merken); Läufe (Tabelle, Tagessumme Tokens, Knopf „Jetzt laufen lassen" mit Spinner, 429-Text inline). Deutsche Labels für Klassen: HEATING „Heizung", SWITCH „Schalter & Licht", MODE „Modi & Helfer", NOTIFY „Nachrichten", LOCK „Haustür verriegeln"; Status: PENDING „Offen", APPROVED „Angenommen", REJECTED „Abgelehnt", EXPIRED „Verfallen", EXECUTED „Ausgeführt", FAILED „Fehlgeschlagen".

- [ ] **Step 1: Failing Spec** (`admin-agent.component.spec.ts`, `HttpTestingController`, Muster `admin-presence.component.spec.ts`):

```ts
  it('lädt Einstellungen, Freigaben, Notizen, Vorschläge und Läufe beim Start', () => { … flush aller fünf GETs … expect(fixture.nativeElement.querySelectorAll('.admin-agent__policy-row').length).toBe(5); });
  it('sperrt die NOTIFY-Freigabe', () => { … select[name="policy-NOTIFY"] disabled … });
  it('sendet eine Entscheidung und ersetzt die Zeile', () => { … Klick auf "Ja" bei Vorschlag 7 → POST /api/v1/agent/proposals/7/decide mit {decision:'APPROVE'} → Antwort EXECUTED → Zeile zeigt "Ausgeführt" … });
  it('zeigt die 429-Meldung beim manuellen Lauf inline', () => { … POST /runs → flush(…, {status: 429, statusText: 'Too Many Requests'}) → .admin-agent__run-message enthält "Bitte warten" … });
  it('zeigt das Telegram-Banner, wenn nicht konfiguriert', () => { … summary telegramConfigured=false → .admin-agent__banner vorhanden … });
```

- [ ] **Step 2:** `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/admin-agent.component.spec.ts'` ⇒ rot. **Step 3:** Implementierung (Templates mit `@if`/`@for`, `FormsModule`, `signal`s; SCSS nur Layout, Feld- und Knopfstile kommen aus dem `$admin-scopes`-Block). **Step 4:** Spec grün; danach `npx ng test --watch=false --browsers=ChromeHeadless` gesamt — die bekannten drei Baseline-Fails (Memory `frontend-test-baseline`) sind keine Regression. **Step 5:** Commit `feat(frontend): Admin-Seite Hausverstand`

---

### Task 13: Hub-Eintrag im Dashboard

**Files:**
- Create: `frontend/src/app/shared/agent-insight.util.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (Feld `agentInsight: HubInsight | null`, Abruf `agentService.getSummary()` alle 60 s über `interval(...).pipe(startWith(0), switchMap(...))`, Einbau in `rebuildInsights()` nach den Maschinen), `dashboard.component.html` (Insight-Karte ist bereits generisch; Link: bei `!viewMode.isTabletView()` die Karte als `routerLink="/admin/agent"` — dafür ein neues optionales Feld `HubInsight.link?: string` in `shared/hub-insight.model.ts`, im Template `[routerLink]="item.link ?? null"` nur wenn nicht Tablet)
- Test: `frontend/src/app/shared/agent-insight.util.spec.ts`, `dashboard.component.spec.ts` (ein Test: Summary mit 2 offenen ⇒ Karte „Hausverstand" mit „2 Vorschläge offen"; 0 ⇒ keine Karte)

**Interfaces:**
- `buildAgentInsight(summary: AgentSummary | null): HubInsight | null` — `null` bei `null`, inaktiv oder `pendingProposals === 0`; sonst `{ icon: 'psychology', tone: 'secondary', title: 'Hausverstand', text: '1 Vorschlag offen' | 'n Vorschläge offen', link: '/admin/agent' }`.

- [ ] **Step 1: Failing Specs** — Util: 0 ⇒ null; 1 ⇒ Singular; 3 ⇒ Plural; inaktiv ⇒ null. Dashboard: HTTP-Stub für `/api/v1/agent/summary` liefert `{pendingProposals: 2, active: true, telegramConfigured: true}` ⇒ `.lumina__insight-title` enthält „Hausverstand".
- [ ] **Step 2–5:** rot → Implementierung (Fehler beim Summary-Abruf ⇒ letzte Karte bleibt, Muster Kacheln) → Specs grün → Commit `feat(dashboard): Hub-Eintrag fuer offene Vorschlaege des Hausverstands`

---

### Task 14: Deployment-Konfiguration und Dokumentation

**Files:**
- Modify: `docker-compose.yml` (Backend-Env `AGENT_MODEL`, `AGENT_ENABLED`, optional `AGENT_QUIET_HOURS`; `ANTHROPIC_API_KEY` ist vorhanden)
- Modify: `CLAUDE.md` (neuer Abschnitt „Hausverstand (proaktiver KI-Agent)" unter den Integrationen, plus im Telegram-Abschnitt der Hinweis, dass der Key jetzt `ai.anthropic-api-key` heißt)
- Modify: `backend/README.md` falls dort Envs dokumentiert sind (`grep -n ANTHROPIC_API_KEY backend/README.md`)

- [ ] **Step 1:** CLAUDE.md-Abschnitt schreiben (Stil der Bestandsabschnitte, Stichpunkte, „warum"-Fokus). Pflichtinhalt: Paket `ai/` geteilt; Scopes der Tools; Schleuse als einziger Aktionspfad; Freigaben nur per Admin; NOTIFY immer direkt; Leitplanken im Code; Ruhezeit mit `urgent`-Ausnahme; „Ja, und merken" erzeugt Notizen, schaltet **keine** Klasse auf DIRECT; Gedächtnis-Tabellen; `agent_active` getrennt von `agent.enabled`; Weckerliste in Properties; Rollout-Reihenfolge; die Fallen: Trigger auf `unavailable` gibt es hier nicht, aber ein Modell, das `notify` ohne `urgent` in der Ruhezeit ruft, landet trotzdem sofort (NOTIFY ist DIRECT, die Ruhezeit greift nur bei Vorschlägen) — bewusst, weil Nachrichten der Vorschlagskanal selbst sind; Kosten sichtbar in der Admin-Seite.
- [ ] **Step 2:** `docker-compose.yml` Envs ergänzen.
- [ ] **Step 3:** Gesamtbuild `cd backend && mvn -q test` (rote `contextLoads`-Baseline beachten) und `cd frontend && npx ng build --configuration production`.
- [ ] **Step 4:** Commit `docs(agent): Hausverstand in CLAUDE.md und docker-compose`

---

## Self-Review (durchgeführt)

- **Spec-Abdeckung:** Architektur/Komponenten → Tasks 1, 7, 8, 9; Ablauf eines Laufs und Prompt-Regeln → Task 8; Schleuse/Freigaben/Leitplanken/Ruhezeit/Knöpfe → Tasks 3, 5, 6, 10; Persistenz → Task 2; API/Security/Audit → Task 11; Frontend → Tasks 12, 13; Fehlerfälle (API-Ausfall, Zeitbudget, Telegram fehlt, Neustart, Schalter aus) → Tasks 5, 8, 9; Tests → in jedem Task; Rollout/Doku → Task 14. `SummaryResponse.telegramConfigured` wurde in Task 12 ergänzt und ist in Task 11 nachzuziehen (dort vermerkt).
- **Typkonsistenz:** `AgentActionExecutor.isSatisfied` (eingeführt in Task 5, in Task 3 zu ergänzen); `AgentActionJson` (Task 5) wird von Notifier und ProposalService genutzt; `AnthropicMessage.toolResult(id, ToolResult)` (Task 8) ersetzt die Telegram-Hilfsmethode; Tool-Namen `set_heating`, `house_set_switch`, `house_set_mode`, `notify`, `house_lock_door`, `get_temperature_history`, `get_calendar`, `note_create/update/delete` sind eindeutig gegenüber den Telegram-Tools (`set_switch`, `set_mode`, `lock_door`).
- **Review Focus:** 1 → Task 5 `secondDecisionIsRefused` plus `@Transactional`; 2 → Task 9 `wakeDuringRunCausesExactlyOneFollowUp`; 3 → Task 5 `deliverPendingSkipsAlreadySatisfied`; 4 → Task 7 `failingSourceIsReportedNotThrown`; 5 → Task 5 `AgentNotifierTest` nicht konfiguriert.
