# Telegram-KI-Assistent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Telegram-Bot im Spring-Backend, der Nutzernachrichten per Claude API (Tool-Use) versteht — Zustände abfragen, Schalter/Modi schalten, Nuki nur verriegeln — plus Flow-Node `telegram-send` für Push-Nachrichten.

**Architecture:** Neues Paket `com.household.manager.telegram` mit Long-Polling gegen die Telegram-Bot-API, einem Agent-Loop gegen die Anthropic Messages API und einer Tool-Registry, deren Tools die bestehenden Services direkt aufrufen. Spec: `docs/superpowers/specs/2026-07-24-telegram-ki-assistent-design.md`.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Lombok, RestTemplate (`RestTemplateBuilder`, Muster `NukiApiClient`), Jackson, JUnit 5 + Mockito + MockRestServiceServer. Keine neuen Dependencies, keine DB-Änderung.

**Build-Umgebung (WICHTIG, Bash-Tool):** Vor jedem Maven-Aufruf aus `backend/`:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Bekannte, vorbestehende Fehlschläge (Test-DB fehlt lokal, ignorieren): `HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`.

**Konventionen:** Deutsche Javadoc/Kommentare (nur „warum"), `@RequiredArgsConstructor`/`@Slf4j` via Lombok, Tests mit `@ExtendWith(MockitoExtension.class)`, AAA-Muster.

---

## Dateiübersicht

| Datei | Verantwortung |
|---|---|
| `telegram/TelegramProperties.java` | Konfiguration (Token, Allowlist, Modell, Limits) |
| `telegram/TelegramException.java` | Laufzeitfehler der Integration, Token nie im Text |
| `telegram/dto/TelegramUpdate.java` u.a. | Minimal-DTOs der Bot-API |
| `telegram/TelegramApiClient.java` | `getUpdates` (Long-Polling) + `sendMessage` |
| `telegram/AnthropicMessage.java` | Nachricht im Anthropic-Format (Rolle + Content-Blöcke) |
| `telegram/AnthropicResponse.java` | Antwort: stopReason + Content-Blöcke |
| `telegram/AnthropicApiClient.java` | POST `/v1/messages` inkl. Tools |
| `telegram/tools/AgentTool.java` | Interface: name/description/inputSchema/execute |
| `telegram/tools/*Tool.java` | 9 Tools als Spring-Beans (Wrapper um bestehende Services) |
| `telegram/TelegramToolRegistry.java` | Tool-Definitionen + Ausführung mit Fehlerkapselung |
| `telegram/TelegramConversationStore.java` | Kurzzeitgedächtnis pro Chat (in-memory, TTL) |
| `telegram/TelegramAgentService.java` | Agent-Loop (tool_use → execute → tool_result → end_turn) |
| `telegram/TelegramNotificationService.java` | Fire-and-forget-Versand (Flows, Antworten) |
| `telegram/TelegramPollingService.java` | Long-Polling-Thread, Allowlist, Backoff |
| `flowengine/nodes/TelegramSendNodeHandler.java` | Flow-Node `telegram-send` |

---

### Task 1: TelegramProperties + application.properties

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramProperties.java`
- Modify: `backend/src/main/resources/application.properties` (ans Ende, nach dem nuki-Block)
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramPropertiesTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramPropertiesTest {

    private TelegramProperties configured() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("123:abc");
        props.setAnthropicApiKey("sk-test");
        props.setAllowedChatIds(List.of(42L));
        return props;
    }

    @Test
    void configuredOnlyWithTokenKeyAndAllowlist() {
        assertTrue(configured().isConfigured());
    }

    @Test
    void notConfiguredWhenAnythingMissing() {
        TelegramProperties noToken = configured();
        noToken.setBotToken(" ");
        assertFalse(noToken.isConfigured());

        TelegramProperties noKey = configured();
        noKey.setAnthropicApiKey("");
        assertFalse(noKey.isConfigured());

        TelegramProperties noChats = configured();
        noChats.setAllowedChatIds(List.of());
        assertFalse(noChats.isConfigured());

        TelegramProperties disabled = configured();
        disabled.setEnabled(false);
        assertFalse(disabled.isConfigured());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TelegramPropertiesTest
```

Erwartet: Kompilierfehler (Klasse existiert nicht).

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.telegram;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Konfiguration des Telegram-KI-Assistenten. Secrets nur per Env, nie loggen. */
@Configuration
@ConfigurationProperties(prefix = "telegram")
@Data
public class TelegramProperties {

    private boolean enabled = true;
    @ToString.Exclude
    private String botToken = "";
    /** Nur diese Chat-IDs dürfen mit dem Bot sprechen; alle anderen werden ignoriert. */
    private List<Long> allowedChatIds = List.of();
    @ToString.Exclude
    private String anthropicApiKey = "";
    private String model = "claude-haiku-4-5-20251001";
    private String telegramBaseUrl = "https://api.telegram.org";
    private String anthropicBaseUrl = "https://api.anthropic.com";
    private int maxTokens = 1024;
    /** Obergrenze der Tool-Runden pro Nutzernachricht (Endlosschleifen-Schutz). */
    private int maxToolIterations = 8;
    private int historyMaxMessages = 20;
    private long historyTtlMinutes = 30;
    private int pollTimeoutSeconds = 30;
    private int httpTimeoutMs = 10000;
    private long errorBackoffMs = 5000;

    /** True, wenn Bot-Token, Anthropic-Key und mindestens ein erlaubter Chat gesetzt sind. */
    public boolean isConfigured() {
        return enabled
                && botToken != null && !botToken.isBlank()
                && anthropicApiKey != null && !anthropicApiKey.isBlank()
                && allowedChatIds != null && !allowedChatIds.isEmpty();
    }
}
```

In `application.properties` ans Ende anhängen:

```properties

# Telegram-KI-Assistent
telegram.enabled=${TELEGRAM_ENABLED:true}
telegram.bot-token=${TELEGRAM_BOT_TOKEN:}
telegram.allowed-chat-ids=${TELEGRAM_ALLOWED_CHAT_IDS:}
telegram.anthropic-api-key=${ANTHROPIC_API_KEY:}
telegram.model=${TELEGRAM_AGENT_MODEL:claude-haiku-4-5-20251001}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=TelegramPropertiesTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram/TelegramProperties.java backend/src/main/resources/application.properties backend/src/test/java/com/household/manager/telegram/TelegramPropertiesTest.java
git commit -m "feat(telegram): Konfiguration des Telegram-KI-Assistenten"
```

---

### Task 2: Telegram-DTOs + TelegramApiClient

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramException.java`
- Create: `backend/src/main/java/com/household/manager/telegram/dto/TelegramChat.java`
- Create: `backend/src/main/java/com/household/manager/telegram/dto/TelegramMessage.java`
- Create: `backend/src/main/java/com/household/manager/telegram/dto/TelegramUpdate.java`
- Create: `backend/src/main/java/com/household/manager/telegram/dto/TelegramUpdatesResponse.java`
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramApiClient.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramApiClientTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TelegramApiClientTest {

    private TelegramApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("123:SECRET");
        client = new TelegramApiClient(props, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void getUpdatesParsesUpdatesAndIgnoresUnknownFields() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=7&timeout=30"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":8,"unknown":1,
                          "message":{"text":"hallo","date":123,"chat":{"id":42,"type":"private"}}}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<TelegramUpdate> updates = client.getUpdates(7, 30);

        assertEquals(1, updates.size());
        assertEquals(8, updates.get(0).updateId());
        assertEquals(42, updates.get(0).message().chat().id());
        assertEquals("hallo", updates.get(0).message().text());
    }

    @Test
    void sendMessagePostsChatIdAndText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.chat_id").value(42))
                .andExpect(jsonPath("$.text").value("hi"))
                .andRespond(withSuccess("{\"ok\":true}", org.springframework.http.MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> client.sendMessage(42, "hi"));
    }

    @Test
    void sendMessageTruncatesOverlongText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.hasLength(4096)))
                .andRespond(withSuccess("{\"ok\":true}", org.springframework.http.MediaType.APPLICATION_JSON));

        client.sendMessage(42, "x".repeat(5000));
    }

    @Test
    void errorsNeverLeakTheBotToken() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=0&timeout=30"))
                .andRespond(withServerError());

        TelegramException ex = assertThrows(TelegramException.class, () -> client.getUpdates(0, 30));
        assertFalse(ex.getMessage().contains("SECRET"));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=TelegramApiClientTest
```

Erwartet: Kompilierfehler.

- [ ] **Step 3: Implementierung**

`TelegramException.java`:

```java
package com.household.manager.telegram;

/** Laufzeitfehler der Telegram-Integration. Meldungstexte dürfen nie den Bot-Token enthalten. */
public class TelegramException extends RuntimeException {

    public TelegramException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

DTOs (je eine Datei, Paket `com.household.manager.telegram.dto`):

```java
package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramChat(long id) {
}
```

```java
package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramMessage(TelegramChat chat, String text) {
}
```

```java
package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(@JsonProperty("update_id") long updateId, TelegramMessage message) {
}
```

```java
package com.household.manager.telegram.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdatesResponse(boolean ok, List<TelegramUpdate> result) {
}
```

`TelegramApiClient.java`:

```java
package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import com.household.manager.telegram.dto.TelegramUpdatesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dünner HTTP-Client für die Telegram-Bot-API: nur getUpdates (Long-Polling)
 * und sendMessage. Der Read-Timeout liegt bewusst über dem Poll-Timeout,
 * sonst bricht jeder leere Long-Poll mit einem Timeout ab.
 */
@Component
@Slf4j
public class TelegramApiClient {

    /** Harte Obergrenze der Telegram-Bot-API für Nachrichtentexte. */
    static final int MAX_MESSAGE_LENGTH = 4096;

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;

    public TelegramApiClient(TelegramProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofSeconds(properties.getPollTimeoutSeconds() + 10L))
                .build();
    }

    public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) {
        String url = apiUrl("getUpdates") + "?offset=" + offset + "&timeout=" + timeoutSeconds;
        try {
            TelegramUpdatesResponse response = restTemplate.getForObject(url, TelegramUpdatesResponse.class);
            return response != null && response.result() != null ? response.result() : List.of();
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram getUpdates fehlgeschlagen: " + masked(ex), ex);
        }
    }

    public void sendMessage(long chatId, String text) {
        String payload = text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
        try {
            restTemplate.postForObject(apiUrl("sendMessage"),
                    Map.of("chat_id", chatId, "text", payload), String.class);
        } catch (RestClientException ex) {
            throw new TelegramException("Telegram sendMessage fehlgeschlagen: " + masked(ex), ex);
        }
    }

    private String apiUrl(String method) {
        return properties.getTelegramBaseUrl() + "/bot" + properties.getBotToken() + "/" + method;
    }

    /** RestClient-Fehlertexte enthalten die URL — und damit den Token. */
    private String masked(RestClientException ex) {
        String message = String.valueOf(ex.getMessage());
        return message.replace(properties.getBotToken(), "***");
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=TelegramApiClientTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): HTTP-Client fuer die Telegram-Bot-API"
```

---

### Task 3: AnthropicMessage/AnthropicResponse + AnthropicApiClient

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/AnthropicMessage.java`
- Create: `backend/src/main/java/com/household/manager/telegram/AnthropicResponse.java`
- Create: `backend/src/main/java/com/household/manager/telegram/AnthropicApiClient.java`
- Test: `backend/src/test/java/com/household/manager/telegram/AnthropicApiClientTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnthropicApiClientTest {

    private AnthropicApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        TelegramProperties props = new TelegramProperties();
        props.setAnthropicApiKey("sk-test");
        props.setModel("claude-haiku-4-5-20251001");
        client = new AnthropicApiClient(props, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void sendsModelSystemMessagesAndToolsWithAuthHeaders() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(header("x-api-key", "sk-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$.system").value("Du bist ein Assistent"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.tools[0].name").value("list_switches"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[{"type":"text","text":"Hallo!"}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage("Du bist ein Assistent",
                List.of(AnthropicMessage.user("hi")),
                List.of(Map.of("name", "list_switches", "description", "d", "input_schema", Map.of())));

        assertEquals("end_turn", response.stopReason());
        assertEquals("Hallo!", response.text());
    }

    @Test
    void parsesToolUseBlocks() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("""
                        {"stop_reason":"tool_use","content":[
                          {"type":"text","text":"Ich schaue nach."},
                          {"type":"tool_use","id":"tu_1","name":"list_switches","input":{"limit":5}}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage("s", List.of(AnthropicMessage.user("hi")), List.of());

        assertEquals("tool_use", response.stopReason());
        assertEquals(1, response.toolUseBlocks().size());
        assertEquals("tu_1", response.toolUseBlocks().get(0).get("id"));
        assertEquals("list_switches", response.toolUseBlocks().get(0).get("name"));
        assertEquals(Map.of("limit", 5), response.toolUseBlocks().get(0).get("input"));
    }

    @Test
    void apiErrorBecomesTelegramException() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withServerError());

        assertThrows(TelegramException.class,
                () -> client.createMessage("s", List.of(AnthropicMessage.user("hi")), List.of()));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=AnthropicApiClientTest
```

- [ ] **Step 3: Implementierung**

`AnthropicMessage.java`:

```java
package com.household.manager.telegram;

import java.util.List;
import java.util.Map;

/**
 * Eine Nachricht im Anthropic-Messages-Format. Content-Blöcke bleiben bewusst
 * rohe Maps (text / tool_use / tool_result), damit kein polymorphes
 * Jackson-Mapping nötig ist.
 */
public record AnthropicMessage(String role, List<Map<String, Object>> content) {

    public static AnthropicMessage user(String text) {
        return new AnthropicMessage("user", List.of(Map.of("type", "text", "text", text)));
    }

    public static AnthropicMessage assistant(List<Map<String, Object>> content) {
        return new AnthropicMessage("assistant", content);
    }

    public static AnthropicMessage toolResults(List<Map<String, Object>> results) {
        return new AnthropicMessage("user", results);
    }
}
```

`AnthropicResponse.java`:

```java
package com.household.manager.telegram;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Antwort der Messages-API: stop_reason plus rohe Content-Blöcke. */
public record AnthropicResponse(String stopReason, List<Map<String, Object>> content) {

    public boolean wantsToolUse() {
        return "tool_use".equals(stopReason);
    }

    public List<Map<String, Object>> toolUseBlocks() {
        return content.stream().filter(block -> "tool_use".equals(block.get("type"))).toList();
    }

    /** Alle Textblöcke zusammengefügt (die finale Antwort an den Nutzer). */
    public String text() {
        return content.stream()
                .filter(block -> "text".equals(block.get("type")))
                .map(block -> String.valueOf(block.get("text")))
                .collect(Collectors.joining("\n"))
                .trim();
    }
}
```

`AnthropicApiClient.java`:

```java
package com.household.manager.telegram;

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

/** HTTP-Client für die Anthropic Messages API (Tool-Use). */
@Component
public class AnthropicApiClient {

    private static final String API_VERSION = "2023-06-01";
    /** Antworten der Claude API brauchen deutlich länger als lokale Aufrufe. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final TelegramProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicApiClient(TelegramProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    public AnthropicResponse createMessage(String system, List<AnthropicMessage> messages,
                                           List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("system", system);
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
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
                throw new TelegramException("Claude API lieferte keine Antwort", null);
            }
            List<Map<String, Object>> content = objectMapper.convertValue(
                    root.path("content"), new TypeReference<>() {
                    });
            return new AnthropicResponse(root.path("stop_reason").asText(), content);
        } catch (RestClientException ex) {
            throw new TelegramException("Claude API-Aufruf fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=AnthropicApiClientTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Client fuer die Anthropic Messages API"
```

---

### Task 4: AgentTool-Interface + TelegramToolRegistry

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/tools/AgentTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/ToolResult.java`
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramToolRegistry.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramToolRegistryTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import com.household.manager.telegram.tools.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelegramToolRegistryTest {

    private AgentTool tool(String name, String result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public String execute(Map<String, Object> input) {
                if (result == null) {
                    throw new IllegalStateException("kaputt");
                }
                return result;
            }
        };
    }

    @Test
    void buildsAnthropicToolDefinitions() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", "ok")));

        List<Map<String, Object>> defs = registry.toolDefinitions();

        assertEquals(1, defs.size());
        assertEquals("a", defs.get(0).get("name"));
        assertEquals("test", defs.get(0).get("description"));
        assertNotNull(defs.get(0).get("input_schema"));
    }

    @Test
    void executeReturnsToolOutput() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", "ergebnis")));

        ToolResult result = registry.execute("a", Map.of());

        assertFalse(result.error());
        assertEquals("ergebnis", result.content());
    }

    @Test
    void unknownToolIsAnErrorResultNotAnException() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of());

        ToolResult result = registry.execute("gibts_nicht", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("gibts_nicht"));
    }

    @Test
    void toolExceptionIsAnErrorResultNotAnException() {
        TelegramToolRegistry registry = new TelegramToolRegistry(List.of(tool("a", null)));

        ToolResult result = registry.execute("a", Map.of());

        assertTrue(result.error());
        assertTrue(result.content().contains("kaputt"));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=TelegramToolRegistryTest
```

- [ ] **Step 3: Implementierung**

`tools/AgentTool.java`:

```java
package com.household.manager.telegram.tools;

import java.util.Map;

/**
 * Ein Werkzeug des Telegram-KI-Assistenten: Definition (Name, Beschreibung,
 * JSON-Schema) plus Ausführung. Ein Spring-Bean pro Tool; die Registry
 * sammelt alle Beans ein.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON-Schema der Eingabe im Anthropic-Format ("type": "object", ...). */
    Map<String, Object> inputSchema();

    /**
     * Führt das Tool aus. Rückgabe ist der Text/JSON-String für den
     * tool_result-Block; Exceptions fängt die Registry und meldet sie als
     * Fehler-Result an das Modell.
     */
    String execute(Map<String, Object> input) throws Exception;
}
```

`ToolResult.java`:

```java
package com.household.manager.telegram;

/** Ergebnis einer Tool-Ausführung für den tool_result-Block. */
public record ToolResult(String content, boolean error) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(message, true);
    }
}
```

`TelegramToolRegistry.java`:

```java
package com.household.manager.telegram;

import com.household.manager.telegram.tools.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sammelt alle {@link AgentTool}-Beans ein und kapselt ihre Ausführung:
 * Fehler werden nie geworfen, sondern als Fehler-Result zurückgegeben,
 * damit das Modell sinnvoll darauf antworten kann.
 */
@Component
@Slf4j
public class TelegramToolRegistry {

    private final Map<String, AgentTool> tools;

    public TelegramToolRegistry(List<AgentTool> agentTools) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        agentTools.forEach(tool -> byName.put(tool.name(), tool));
        this.tools = byName;
    }

    /** Tool-Definitionen im Format der Anthropic Messages API. */
    public List<Map<String, Object>> toolDefinitions() {
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "input_schema", tool.inputSchema()))
                .toList();
    }

    public ToolResult execute(String name, Map<String, Object> input) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
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

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=TelegramToolRegistryTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Tool-Interface und Registry fuer den Agent"
```

---

### Task 5: Lese-Tools (Schalter, Entitäten, Verbraucher, Zähler, Modi, Schloss-Status)

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/tools/ListSwitchesTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/GetEntityStatesTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/ListPowerConsumersTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/GetMeterReadingsTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/ListModesTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/GetLockStatusTool.java`
- Test: `backend/src/test/java/com/household/manager/telegram/tools/GetEntityStatesToolTest.java`

Alle Tools serialisieren ihre Antwort per Jackson-`ObjectMapper` (Feld im Tool, `new ObjectMapper()`); kompakte Auswahl an Feldern statt kompletter DTOs, damit die Token-Kosten klein bleiben.

- [ ] **Step 1: Failing Test schreiben (exemplarisch für das komplexeste Lese-Tool)**

```java
package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetEntityStatesToolTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private EntityStateResponseMapper mapper;

    private EntityState entity(String entityId, String state) {
        EntityState e = new EntityState();
        e.setEntityId(entityId);
        e.setState(state);
        return e;
    }

    @Test
    void filtersByQueryOverEntityIdAndDisplayName() throws Exception {
        EntityState wohnzimmer = entity("sensor.zigbee_wz_temperature", "21.5");
        EntityState bad = entity("sensor.zigbee_bad_temperature", "23");
        when(entityStateService.find(isNull(), isNull())).thenReturn(List.of(wohnzimmer, bad));
        when(mapper.displayName(wohnzimmer)).thenReturn("Temperatur Wohnzimmer");
        when(mapper.displayName(bad)).thenReturn("Temperatur Bad");

        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);
        String result = tool.execute(Map.of("query", "wohnzimmer"));

        assertTrue(result.contains("Temperatur Wohnzimmer"));
        assertFalse(result.contains("Temperatur Bad"));
    }

    @Test
    void filtersByDomain() throws Exception {
        EntityState sensor = entity("sensor.x", "1");
        when(entityStateService.find(eq(EntityDomain.SENSOR), isNull())).thenReturn(List.of(sensor));
        lenient().when(mapper.displayName(sensor)).thenReturn("X");

        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);
        String result = tool.execute(Map.of("domain", "sensor"));

        assertTrue(result.contains("sensor.x"));
    }

    @Test
    void unknownDomainIsAHelpfulError() {
        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);

        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("domain", "quatsch")));
        assertTrue(ex.getMessage().toLowerCase().contains("domain"));
    }
}
```

(`EntityState` hat `@Setter` + `@NoArgsConstructor` — die Testfabrik funktioniert so, verifiziert.)

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=GetEntityStatesToolTest
```

- [ ] **Step 3: Implementierung aller sechs Lese-Tools**

`GetEntityStatesTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fragt Entitätszustände ab — Temperaturen, Sensoren, Präsenz, Luftqualität usw. */
@Component
@RequiredArgsConstructor
public class GetEntityStatesTool implements AgentTool {

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_entity_states";
    }

    @Override
    public String description() {
        return "Fragt Zustaende aller Entitaeten ab (Temperaturen, Sensoren, Praesenz, "
                + "Luftqualitaet, Schloesser). Optional filterbar nach domain und "
                + "Suchbegriff (query, matcht Name und Entity-ID).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "domain", Map.of("type", "string",
                                "description", "Optional: z.B. sensor, switch, light, lock, binary_sensor"),
                        "query", Map.of("type", "string",
                                "description", "Optional: Suchbegriff, z.B. 'wohnzimmer'")));
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        EntityDomain domain = parseDomain(input.get("domain"));
        String query = input.get("query") != null
                ? String.valueOf(input.get("query")).toLowerCase(Locale.ROOT) : null;

        List<Map<String, Object>> states = entityStateService.find(domain, null).stream()
                .filter(entity -> matches(entity, query))
                .map(entity -> Map.<String, Object>of(
                        "entityId", entity.getEntityId(),
                        "name", mapper.displayName(entity),
                        "state", String.valueOf(entity.getState())))
                .toList();
        return json.writeValueAsString(states);
    }

    private boolean matches(EntityState entity, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return entity.getEntityId().toLowerCase(Locale.ROOT).contains(query)
                || mapper.displayName(entity).toLowerCase(Locale.ROOT).contains(query);
    }

    private EntityDomain parseDomain(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            return EntityDomain.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unbekannte domain '" + raw + "'. Gueltig: "
                    + Arrays.toString(EntityDomain.values()));
        }
    }
}
```

`ListSwitchesTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.SwitchQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Listet alle schaltbaren Entitäten (Lichter, Steckdosen) mit Zustand. */
@Component
@RequiredArgsConstructor
public class ListSwitchesTool implements AgentTool {

    private final SwitchQueryService switchQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_switches";
    }

    @Override
    public String description() {
        return "Listet alle schaltbaren Geraete (Lichter, Steckdosen) mit entityId, "
                + "Name, Zustand (on/off), Verfuegbarkeit und aktueller Leistung in Watt. "
                + "Immer zuerst aufrufen, um die entityId fuer set_switch zu finden.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        List<Map<String, Object>> switches = switchQueryService.listSwitches(null).stream()
                .map(sw -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<String, Object>();
                    entry.put("entityId", sw.entityId());
                    entry.put("name", sw.displayName());
                    entry.put("state", sw.state());
                    entry.put("available", sw.available());
                    entry.put("powerWatts", sw.powerWatts());
                    return entry;
                })
                .toList();
        return json.writeValueAsString(switches);
    }
}
```

`ListPowerConsumersTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.PowerConsumerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Aktuelle Stromverbraucher, größte zuerst. */
@Component
@RequiredArgsConstructor
public class ListPowerConsumersTool implements AgentTool {

    private final PowerConsumerQueryService powerConsumerQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_power_consumers";
    }

    @Override
    public String description() {
        return "Listet die aktuellen Stromverbraucher mit Leistung in Watt, "
                + "groesster Verbraucher zuerst.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(powerConsumerQueryService.listConsumers(null));
    }
}
```

`GetMeterReadingsTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.MeterType;
import com.household.manager.service.MeterReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Letzte Zählerstände je Zählertyp (Strom, Gas, Wasser). */
@Component
@RequiredArgsConstructor
public class GetMeterReadingsTool implements AgentTool {

    private final MeterReadingService meterReadingService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_meter_readings";
    }

    @Override
    public String description() {
        return "Liefert den letzten Zaehlerstand je Zaehlertyp (ELECTRICITY, GAS, WATER).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        List<Map<String, Object>> readings = new ArrayList<>();
        for (MeterType type : MeterType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("meterType", type.name());
            try {
                var latest = meterReadingService.getLatestReading(type);
                entry.put("value", latest.getReadingValue());
                entry.put("date", String.valueOf(latest.getReadingDate()));
            } catch (Exception ex) {
                entry.put("value", null);
                entry.put("info", "keine Daten");
            }
            readings.add(entry);
        }
        return json.writeValueAsString(readings);
    }
}
```

(Getter verifiziert: `MeterReadingResponse` hat die Felder `readingValue`/`readingDate` mit Lombok `@Data`.)

`ListModesTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.HouseModeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Haus-Modi (z. B. Abwesend, Nacht) mit Zustand. */
@Component
@RequiredArgsConstructor
public class ListModesTool implements AgentTool {

    private final HouseModeQueryService houseModeQueryService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "list_modes";
    }

    @Override
    public String description() {
        return "Listet die Haus-Modi mit entityId, Name und Zustand (on/off). "
                + "Zuerst aufrufen, um die entityId fuer set_mode zu finden.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(houseModeQueryService.listModes());
    }
}
```

`GetLockStatusTool.java`:

```java
package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.nuki.NukiLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Zustand der Nuki-Schlösser (verriegelt? Tür offen? Batterie?). */
@Component
@RequiredArgsConstructor
public class GetLockStatusTool implements AgentTool {

    private final NukiLockService nukiLockService;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_lock_status";
    }

    @Override
    public String description() {
        return "Zustand der Tuerschloesser: verriegelt/entriegelt, Tuersensor, Batterie.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        return json.writeValueAsString(nukiLockService.listLocks());
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein; danach kompiliert das ganze Modul**

```bash
mvn test -Dtest=GetEntityStatesToolTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Lese-Tools fuer den KI-Assistenten"
```

---

### Task 6: Schreib-Tools (set_switch, set_mode, lock_door)

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/tools/SetSwitchTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/SetModeTool.java`
- Create: `backend/src/main/java/com/household/manager/telegram/tools/LockDoorTool.java`
- Test: `backend/src/test/java/com/household/manager/telegram/tools/SetSwitchToolTest.java`
- Test: `backend/src/test/java/com/household/manager/telegram/tools/SetModeToolTest.java`
- Test: `backend/src/test/java/com/household/manager/telegram/tools/LockDoorToolTest.java`

**Sicherheitsanker dieses Tasks:** `LockDoorTool` ruft im Code fest `NukiLockAction.LOCK` auf — es gibt keinen Aktions-Parameter. `SetModeTool` toggelt nur entityIds, die `HouseModeQueryService.listModes()` tatsächlich als Modus liefert (sonst könnte das Modell beliebige `input_boolean`s toggeln).

- [ ] **Step 1: Failing Tests schreiben**

`SetSwitchToolTest.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetSwitchToolTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private SwitchCommandService switchCommandService;

    private EntityState entity(String state) {
        EntityState e = new EntityState();
        e.setEntityId("switch.meross_x");
        e.setState(state);
        return e;
    }

    private SetSwitchTool tool() {
        return new SetSwitchTool(entityStateService, switchCommandService);
    }

    @Test
    void togglesWhenStateDiffers() throws Exception {
        when(entityStateService.getByEntityId("switch.meross_x")).thenReturn(Optional.of(entity("off")));

        String result = tool().execute(Map.of("entityId", "switch.meross_x", "state", "on"));

        verify(switchCommandService).toggle("switch.meross_x");
        assertTrue(result.contains("on"));
    }

    @Test
    void skipsToggleWhenAlreadyInDesiredState() throws Exception {
        when(entityStateService.getByEntityId("switch.meross_x")).thenReturn(Optional.of(entity("on")));

        String result = tool().execute(Map.of("entityId", "switch.meross_x", "state", "on"));

        verify(switchCommandService, never()).toggle(any());
        assertTrue(result.contains("bereits"));
    }

    @Test
    void unknownEntityFails() {
        when(entityStateService.getByEntityId("switch.nix")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "switch.nix", "state", "on")));
    }

    @Test
    void invalidStateFails() {
        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "switch.meross_x", "state", "an")));
    }
}
```

`SetModeToolTest.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetModeToolTest {

    @Mock
    private HouseModeQueryService houseModeQueryService;
    @Mock
    private ManualEntityService manualEntityService;

    private SetModeTool tool() {
        return new SetModeTool(houseModeQueryService, manualEntityService);
    }

    private ModeResponse mode(String entityId, String state) {
        return ModeResponse.builder().entityId(entityId).displayName("Modus").state(state).build();
    }

    @Test
    void togglesAKnownModeToDesiredState() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "off")));

        tool().execute(Map.of("entityId", "input_boolean.mode_night", "state", "on"));

        verify(manualEntityService).toggle("input_boolean.mode_night");
    }

    @Test
    void refusesEntityThatIsNoMode() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "off")));

        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "input_boolean.irgendwas", "state", "on")));
        verifyNoInteractions(manualEntityService);
    }

    @Test
    void skipsWhenAlreadyInDesiredState() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "on")));

        String result = tool().execute(Map.of("entityId", "input_boolean.mode_night", "state", "on"));

        verify(manualEntityService, never()).toggle(any());
        assertTrue(result.contains("bereits"));
    }
}
```

`LockDoorToolTest.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockDoorToolTest {

    @Mock
    private NukiLockService nukiLockService;

    private LockDoorTool tool() {
        return new LockDoorTool(nukiLockService);
    }

    private NukiLockResponse lock(long id) {
        return new NukiLockResponse(id, "Haustuer", "unlocked", "closed", 80, false);
    }

    @Test
    void locksTheOnlyLockWithoutExplicitId() throws Exception {
        when(nukiLockService.listLocks()).thenReturn(List.of(lock(17L)));

        tool().execute(Map.of());

        verify(nukiLockService).executeAction(17L, NukiLockAction.LOCK);
    }

    @Test
    void locksExplicitSmartlockId() throws Exception {
        tool().execute(Map.of("smartlockId", "17"));

        verify(nukiLockService).executeAction(17L, NukiLockAction.LOCK);
    }

    @Test
    void neverCallsAnyOtherActionThanLock() throws Exception {
        tool().execute(Map.of("smartlockId", "17"));

        verify(nukiLockService).executeAction(anyLong(), eq(NukiLockAction.LOCK));
        verify(nukiLockService, never()).executeAction(anyLong(), eq(NukiLockAction.UNLOCK));
        verify(nukiLockService, never()).executeAction(anyLong(), eq(NukiLockAction.UNLATCH));
    }

    @Test
    void ambiguousWithoutIdWhenMultipleLocks() {
        when(nukiLockService.listLocks()).thenReturn(List.of(lock(1L), lock(2L)));

        assertThrows(IllegalArgumentException.class, () -> tool().execute(Map.of()));
        verify(nukiLockService, never()).executeAction(anyLong(), any());
    }
}
```

(Konstruktor-Reihenfolge von `NukiLockResponse` verifiziert gegen `NukiLockService.toResponse`: smartlockId, name, lockState, doorState, batteryCharge, batteryCritical.)

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```bash
mvn test -Dtest='SetSwitchToolTest,SetModeToolTest,LockDoorToolTest'
```

- [ ] **Step 3: Implementierung**

`SetSwitchTool.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Schaltet eine Entität gezielt auf on/off. Der Umweg über den Ist-Zustand ist
 * nötig, weil der SwitchCommandService nur toggeln kann — sonst würde
 * "schalte an" ein bereits eingeschaltetes Gerät ausschalten.
 */
@Component
@RequiredArgsConstructor
public class SetSwitchTool implements AgentTool {

    private static final String STATE_ON = "on";

    private final EntityStateService entityStateService;
    private final SwitchCommandService switchCommandService;

    @Override
    public String name() {
        return "set_switch";
    }

    @Override
    public String description() {
        return "Schaltet ein Geraet (Licht, Steckdose) ein oder aus. Die entityId "
                + "vorher mit list_switches ermitteln.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-ID aus list_switches"),
                        "state", Map.of("type", "string", "enum", java.util.List.of("on", "off"))),
                "required", java.util.List.of("entityId", "state"));
    }

    @Override
    public String execute(Map<String, Object> input) {
        String entityId = requireString(input, "entityId");
        String desired = requireString(input, "state");
        if (!"on".equals(desired) && !"off".equals(desired)) {
            throw new IllegalArgumentException("state muss 'on' oder 'off' sein");
        }

        EntityState entity = entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte entityId: " + entityId));

        boolean isOn = STATE_ON.equals(entity.getState());
        boolean wantOn = "on".equals(desired);
        if (isOn == wantOn) {
            return entityId + " ist bereits " + desired;
        }
        switchCommandService.toggle(entityId);
        return entityId + " ist jetzt " + desired;
    }

    private String requireString(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " fehlt");
        }
        return String.valueOf(value);
    }
}
```

`SetModeTool.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Setzt einen Haus-Modus. Toggelt nur entityIds, die die Modus-Abfrage kennt —
 * sonst koennte das Modell beliebige input_booleans schalten.
 */
@Component
@RequiredArgsConstructor
public class SetModeTool implements AgentTool {

    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;

    @Override
    public String name() {
        return "set_mode";
    }

    @Override
    public String description() {
        return "Aktiviert oder deaktiviert einen Haus-Modus. Die entityId vorher "
                + "mit list_modes ermitteln.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "entityId", Map.of("type", "string", "description", "Entity-ID aus list_modes"),
                        "state", Map.of("type", "string", "enum", List.of("on", "off"))),
                "required", List.of("entityId", "state"));
    }

    @Override
    public String execute(Map<String, Object> input) {
        String entityId = String.valueOf(input.get("entityId"));
        String desired = String.valueOf(input.get("state"));

        ModeResponse mode = houseModeQueryService.listModes().stream()
                .filter(m -> m.entityId().equals(entityId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kein Haus-Modus: " + entityId));

        if (desired.equals(mode.state())) {
            return entityId + " ist bereits " + desired;
        }
        manualEntityService.toggle(entityId);
        return entityId + " ist jetzt " + desired;
    }
}
```

`LockDoorTool.java`:

```java
package com.household.manager.telegram.tools;

import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Verriegelt ein Nuki-Schloss. SICHERHEITSANKER: Die Aktion ist im Code fest
 * auf LOCK verdrahtet — Entriegeln/Tuer oeffnen existiert fuer den
 * Telegram-Assistenten bewusst nicht (Spec 2026-07-24, Prompt-Injection-Schutz).
 */
@Component
@RequiredArgsConstructor
public class LockDoorTool implements AgentTool {

    private final NukiLockService nukiLockService;

    @Override
    public String name() {
        return "lock_door";
    }

    @Override
    public String description() {
        return "Verriegelt die Tuer (nur abschliessen — aufschliessen ist ueber "
                + "diesen Weg nicht moeglich). Ohne smartlockId wird das einzige "
                + "vorhandene Schloss verriegelt.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "smartlockId", Map.of("type", "string",
                                "description", "Optional, aus get_lock_status; noetig bei mehreren Schloessern")));
    }

    @Override
    public String execute(Map<String, Object> input) {
        long smartlockId = resolveSmartlockId(input.get("smartlockId"));
        nukiLockService.executeAction(smartlockId, NukiLockAction.LOCK);
        return "Schloss " + smartlockId + " wird verriegelt.";
    }

    private long resolveSmartlockId(Object raw) {
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                return Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("smartlockId muss numerisch sein: " + raw);
            }
        }
        List<NukiLockResponse> locks = nukiLockService.listLocks();
        if (locks.size() != 1) {
            throw new IllegalArgumentException(
                    "smartlockId noetig — es gibt " + locks.size() + " Schloesser (siehe get_lock_status)");
        }
        return locks.get(0).smartlockId();
    }
}
```

- [ ] **Step 4: Tests ausführen — müssen grün sein**

```bash
mvn test -Dtest='SetSwitchToolTest,SetModeToolTest,LockDoorToolTest'
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Schreib-Tools; Nuki hart auf Verriegeln begrenzt"
```

---

### Task 7: TelegramConversationStore

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramConversationStore.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramConversationStoreTest.java`

Es werden nur Nutzertext und finale Assistententext-Antwort gespeichert — keine tool_use/tool_result-Blöcke. Das hält die Historie klein und garantiert, dass beim Kürzen nie eine halbe Tool-Sequenz übrig bleibt (die API lehnt tool_use ohne folgendes tool_result ab).

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConversationStoreTest {

    private final Instant start = Instant.parse("2026-07-24T10:00:00Z");

    private TelegramProperties props(int maxMessages, long ttlMinutes) {
        TelegramProperties p = new TelegramProperties();
        p.setHistoryMaxMessages(maxMessages);
        p.setHistoryTtlMinutes(ttlMinutes);
        return p;
    }

    @Test
    void storesExchangesAsPlainTextPairs() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), clock);

        store.appendExchange(1L, "Licht an?", "Ist an.");

        List<AnthropicMessage> history = store.history(1L);
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void expiresAfterTtl() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), clock);
        store.appendExchange(1L, "a", "b");

        clock.advance(Duration.ofMinutes(31));

        assertTrue(store.history(1L).isEmpty());
    }

    @Test
    void trimsToMaxMessagesKeepingTheNewest() {
        MutableClock clock = new MutableClock(start);
        TelegramConversationStore store = new TelegramConversationStore(props(4, 30), clock);
        store.appendExchange(1L, "u1", "a1");
        store.appendExchange(1L, "u2", "a2");
        store.appendExchange(1L, "u3", "a3");

        List<AnthropicMessage> history = store.history(1L);

        assertEquals(4, history.size());
        assertEquals("u2", history.get(0).content().get(0).get("text"));
    }

    @Test
    void chatsAreIsolated() {
        TelegramConversationStore store = new TelegramConversationStore(props(20, 30), new MutableClock(start));
        store.appendExchange(1L, "a", "b");

        assertTrue(store.history(2L).isEmpty());
    }

    /** Verstellbare Uhr für TTL-Tests. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=TelegramConversationStoreTest
```

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kurzzeitgedächtnis pro Chat, damit Rückfragen ("und im Schlafzimmer?")
 * funktionieren. Bewusst nur Nutzertext + finale Antwort (keine Tool-Blöcke):
 * klein, und beim Kürzen kann nie eine halbe Tool-Sequenz übrig bleiben.
 * In-memory — ein Neustart vergisst laufende Gespräche, das ist ok.
 */
@Component
public class TelegramConversationStore {

    private record Conversation(List<AnthropicMessage> messages, Instant lastActivity) {
    }

    private final TelegramProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Conversation> conversations = new ConcurrentHashMap<>();

    @Autowired
    public TelegramConversationStore(TelegramProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    TelegramConversationStore(TelegramProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public List<AnthropicMessage> history(long chatId) {
        Conversation conversation = conversations.get(chatId);
        if (conversation == null || isExpired(conversation)) {
            conversations.remove(chatId);
            return List.of();
        }
        return List.copyOf(conversation.messages());
    }

    public void appendExchange(long chatId, String userText, String assistantText) {
        List<AnthropicMessage> messages = new ArrayList<>(history(chatId));
        messages.add(AnthropicMessage.user(userText));
        messages.add(new AnthropicMessage("assistant",
                List.of(java.util.Map.of("type", "text", "text", assistantText))));

        int max = properties.getHistoryMaxMessages();
        if (messages.size() > max) {
            messages = new ArrayList<>(messages.subList(messages.size() - max, messages.size()));
        }
        conversations.put(chatId, new Conversation(messages, clock.instant()));
    }

    private boolean isExpired(Conversation conversation) {
        Duration ttl = Duration.ofMinutes(properties.getHistoryTtlMinutes());
        return conversation.lastActivity().plus(ttl).isBefore(clock.instant());
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=TelegramConversationStoreTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Kurzzeitgedaechtnis pro Chat"
```

---

### Task 8: TelegramAgentService (Agent-Loop)

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramAgentService.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramAgentServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramAgentServiceTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private TelegramToolRegistry toolRegistry;

    private TelegramProperties props;
    private TelegramConversationStore store;
    private TelegramAgentService service;

    @BeforeEach
    void setUp() {
        props = new TelegramProperties();
        props.setMaxToolIterations(3);
        store = new TelegramConversationStore(props);
        service = new TelegramAgentService(props, anthropicApiClient, toolRegistry, store);
        lenient().when(toolRegistry.toolDefinitions()).thenReturn(List.of());
    }

    private AnthropicResponse endTurn(String text) {
        return new AnthropicResponse("end_turn", List.of(Map.of("type", "text", "text", text)));
    }

    private AnthropicResponse toolUse(String id, String tool) {
        return new AnthropicResponse("tool_use", List.of(
                Map.of("type", "tool_use", "id", id, "name", tool, "input", Map.of())));
    }

    @Test
    void plainAnswerWithoutTools() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(endTurn("Hallo!"));

        assertEquals("Hallo!", service.handleUserMessage(1L, "hi"));
    }

    @Test
    void executesToolsAndFeedsResultsBack() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(toolUse("tu_1", "list_switches"))
                .thenReturn(endTurn("2 Lampen sind an."));
        when(toolRegistry.execute("list_switches", Map.of())).thenReturn(ToolResult.ok("[...]"));

        String answer = service.handleUserMessage(1L, "Was ist an?");

        assertEquals("2 Lampen sind an.", answer);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AnthropicMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(anthropicApiClient, times(2)).createMessage(anyString(), captor.capture(), anyList());
        List<AnthropicMessage> secondCall = captor.getAllValues().get(1);
        AnthropicMessage toolResultMessage = secondCall.get(secondCall.size() - 1);
        assertEquals("user", toolResultMessage.role());
        assertEquals("tool_result", toolResultMessage.content().get(0).get("type"));
        assertEquals("tu_1", toolResultMessage.content().get(0).get("tool_use_id"));
    }

    @Test
    void stopsAfterMaxIterations() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(toolUse("tu_1", "list_switches"));
        when(toolRegistry.execute(anyString(), anyMap())).thenReturn(ToolResult.ok("x"));

        String answer = service.handleUserMessage(1L, "loop");

        verify(anthropicApiClient, times(3)).createMessage(anyString(), anyList(), anyList());
        assertFalse(answer.isBlank());
    }

    @Test
    void apiErrorYieldsFriendlyMessage() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenThrow(new TelegramException("kaputt", null));

        String answer = service.handleUserMessage(1L, "hi");

        assertFalse(answer.isBlank());
        assertFalse(answer.contains("kaputt"));
    }

    @Test
    void successfulExchangeLandsInHistory() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(endTurn("Antwort"));

        service.handleUserMessage(1L, "Frage");

        assertEquals(2, store.history(1L).size());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=TelegramAgentServiceTest
```

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Der Agent-Loop des Telegram-Assistenten: Nutzernachricht + Historie an die
 * Claude API, angefragte Tools ausführen, Ergebnisse zurückschleifen, bis eine
 * finale Antwort steht. Wirft nie — jede Störung wird zu einer freundlichen
 * Chat-Antwort, Details landen im Log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramAgentService {

    static final String ERROR_REPLY =
            "Ich konnte das gerade nicht verarbeiten. Versuch es bitte gleich noch einmal.";
    static final String LOOP_LIMIT_REPLY =
            "Das war mir zu viele Schritte auf einmal — bitte formuliere es etwas konkreter.";

    private static final String SYSTEM_PROMPT = """
            Du bist der Assistent des Household-Manager-Smart-Home-Systems und antwortest \
            per Telegram. Antworte auf Deutsch, kurz und chat-tauglich (keine Markdown-Tabellen). \
            Nutze die Tools, um Zustände abzufragen und zu schalten; rate nie und behaupte \
            keine Aktion, die kein Tool bestätigt hat. Nenne Geräte bei ihren Anzeigenamen, \
            nicht bei Entity-IDs. Die Haustür kannst du ausschließlich verriegeln — Entriegeln \
            oder Öffnen ist technisch nicht möglich; lehne solche Bitten kurz ab.""";

    private final TelegramProperties properties;
    private final AnthropicApiClient anthropicApiClient;
    private final TelegramToolRegistry toolRegistry;
    private final TelegramConversationStore conversationStore;

    public String handleUserMessage(long chatId, String text) {
        try {
            List<AnthropicMessage> messages = new ArrayList<>(conversationStore.history(chatId));
            messages.add(AnthropicMessage.user(text));

            for (int iteration = 0; iteration < properties.getMaxToolIterations(); iteration++) {
                AnthropicResponse response = anthropicApiClient.createMessage(
                        SYSTEM_PROMPT, messages, toolRegistry.toolDefinitions());

                if (!response.wantsToolUse()) {
                    String answer = response.text();
                    if (answer.isBlank()) {
                        answer = ERROR_REPLY;
                    }
                    conversationStore.appendExchange(chatId, text, answer);
                    return answer;
                }

                messages.add(AnthropicMessage.assistant(response.content()));
                messages.add(AnthropicMessage.toolResults(executeTools(response)));
            }
            log.warn("Agent-Loop für Chat {} nach {} Iterationen abgebrochen",
                    chatId, properties.getMaxToolIterations());
            return LOOP_LIMIT_REPLY;
        } catch (Exception ex) {
            log.error("Telegram-Agent fehlgeschlagen für Chat {}: {}", chatId, ex.getMessage(), ex);
            return ERROR_REPLY;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executeTools(AnthropicResponse response) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> block : response.toolUseBlocks()) {
            String toolName = String.valueOf(block.get("name"));
            Map<String, Object> input = block.get("input") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            ToolResult result = toolRegistry.execute(toolName, input);

            Map<String, Object> resultBlock = new HashMap<>();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", block.get("id"));
            resultBlock.put("content", result.content());
            if (result.error()) {
                resultBlock.put("is_error", true);
            }
            results.add(resultBlock);
        }
        return results;
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```bash
mvn test -Dtest=TelegramAgentServiceTest
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Agent-Loop mit Claude-Tool-Use"
```

---

### Task 9: TelegramNotificationService + TelegramPollingService

**Files:**
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramNotificationService.java`
- Create: `backend/src/main/java/com/household/manager/telegram/TelegramPollingService.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramNotificationServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/telegram/TelegramPollingServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`TelegramNotificationServiceTest.java`:

```java
package com.household.manager.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationServiceTest {

    @Mock
    private TelegramApiClient apiClient;

    private TelegramProperties props(List<Long> chatIds) {
        TelegramProperties p = new TelegramProperties();
        p.setBotToken("t");
        p.setAnthropicApiKey("k");
        p.setAllowedChatIds(chatIds);
        return p;
    }

    @Test
    void broadcastsToAllAllowedChats() {
        TelegramNotificationService service =
                new TelegramNotificationService(props(List.of(1L, 2L)), apiClient);

        service.sendToAllowedChats("Waschmaschine fertig");

        verify(apiClient).sendMessage(1L, "Waschmaschine fertig");
        verify(apiClient).sendMessage(2L, "Waschmaschine fertig");
    }

    @Test
    void oneFailingChatDoesNotStopTheOthers() {
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(1L, "x");
        TelegramNotificationService service =
                new TelegramNotificationService(props(List.of(1L, 2L)), apiClient);

        assertDoesNotThrow(() -> service.sendToAllowedChats("x"));
        verify(apiClient).sendMessage(2L, "x");
    }

    @Test
    void unconfiguredIntegrationSendsNothing() {
        TelegramProperties unconfigured = props(List.of());
        TelegramNotificationService service = new TelegramNotificationService(unconfigured, apiClient);

        service.sendToAllowedChats("x");
        service.sendTo(5L, "x");

        verifyNoInteractions(apiClient);
    }
}
```

`TelegramPollingServiceTest.java` (testet die Update-Behandlung ohne Thread):

```java
package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramChat;
import com.household.manager.telegram.dto.TelegramMessage;
import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramPollingServiceTest {

    @Mock
    private TelegramApiClient apiClient;
    @Mock
    private TelegramAgentService agentService;

    private TelegramPollingService service() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("t");
        props.setAnthropicApiKey("k");
        props.setAllowedChatIds(List.of(42L));
        return new TelegramPollingService(props, apiClient, agentService);
    }

    private TelegramUpdate update(long chatId, String text) {
        return new TelegramUpdate(1, new TelegramMessage(new TelegramChat(chatId), text));
    }

    @Test
    void allowedChatGetsAnAgentReply() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");

        service().handleUpdate(update(42L, "hallo"));

        verify(apiClient).sendMessage(42L, "Hi!");
    }

    @Test
    void foreignChatIsIgnoredCompletely() {
        service().handleUpdate(update(99L, "lass mich rein"));

        verifyNoInteractions(agentService);
        verify(apiClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void updatesWithoutTextAreIgnored() {
        service().handleUpdate(new TelegramUpdate(1, null));
        service().handleUpdate(update(42L, null));

        verifyNoInteractions(agentService);
    }

    @Test
    void sendFailureDoesNotPropagate() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(42L, "Hi!");

        assertDoesNotThrow(() -> service().handleUpdate(update(42L, "hallo")));
    }
}
```

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```bash
mvn test -Dtest='TelegramNotificationServiceTest,TelegramPollingServiceTest'
```

- [ ] **Step 3: Implementierung**

`TelegramNotificationService.java`:

```java
package com.household.manager.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fire-and-forget-Versand von Telegram-Nachrichten (Flow-Node, künftige
 * Benachrichtigungen). Wirft nie — ein Sendefehler darf keinen Flow abbrechen
 * (Verhalten analog Alexa-Ansagen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramNotificationService {

    private final TelegramProperties properties;
    private final TelegramApiClient apiClient;

    /** Sendet an alle erlaubten Chats; Fehler einzelner Chats stoppen die anderen nicht. */
    public void sendToAllowedChats(String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return;
        }
        properties.getAllowedChatIds().forEach(chatId -> sendTo(chatId, text));
    }

    public void sendTo(long chatId, String text) {
        if (!properties.isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Nachricht verworfen");
            return;
        }
        try {
            apiClient.sendMessage(chatId, text);
        } catch (Exception ex) {
            log.warn("Telegram-Nachricht an Chat {} fehlgeschlagen: {}", chatId, ex.getMessage());
        }
    }
}
```

`TelegramPollingService.java`:

```java
package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-Polling gegen die Telegram-Bot-API in einem eigenen Thread
 * ({@code @Scheduled} passt nicht, weil getUpdates bis zu 30 s blockiert).
 * Startet nur, wenn die Integration vollständig konfiguriert ist; bei
 * Telegram-Ausfall Backoff statt Absturz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramPollingService {

    private final TelegramProperties properties;
    private final TelegramApiClient apiClient;
    private final TelegramAgentService agentService;

    private volatile boolean running;
    private ExecutorService executor;
    private long nextOffset;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isConfigured()) {
            log.info("Telegram-Assistent nicht konfiguriert (Token/Key/Chat-IDs fehlen) — Polling aus");
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telegram-polling");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::pollLoop);
        log.info("Telegram-Polling gestartet ({} erlaubte Chats)", properties.getAllowedChatIds().size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                for (TelegramUpdate update : apiClient.getUpdates(nextOffset, properties.getPollTimeoutSeconds())) {
                    nextOffset = update.updateId() + 1;
                    handleUpdate(update);
                }
            } catch (Exception ex) {
                log.warn("Telegram-Polling gestört, warte {} ms: {}",
                        properties.getErrorBackoffMs(), ex.getMessage());
                sleepBackoff();
            }
        }
    }

    /** Package-private für Tests (Verarbeitung ohne Polling-Thread). */
    void handleUpdate(TelegramUpdate update) {
        if (update.message() == null || update.message().text() == null
                || update.message().chat() == null) {
            return;
        }
        long chatId = update.message().chat().id();
        if (!properties.getAllowedChatIds().contains(chatId)) {
            log.warn("Telegram-Nachricht von nicht erlaubtem Chat {} ignoriert", chatId);
            return;
        }
        String reply = agentService.handleUserMessage(chatId, update.message().text());
        try {
            apiClient.sendMessage(chatId, reply);
        } catch (Exception ex) {
            log.warn("Antwort an Chat {} konnte nicht gesendet werden: {}", chatId, ex.getMessage());
        }
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(properties.getErrorBackoffMs());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 4: Tests ausführen — müssen grün sein**

```bash
mvn test -Dtest='TelegramNotificationServiceTest,TelegramPollingServiceTest'
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/telegram backend/src/test/java/com/household/manager/telegram
git commit -m "feat(telegram): Long-Polling mit Allowlist und Push-Versand"
```

---

### Task 10: Flow-Node `telegram-send`

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/TelegramSendNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/TelegramSendNodeHandlerTest.java`

Muster: `AlexaAnnounceNodeHandler` (gleiche Platzhalter, gleiche Struktur). Da `TelegramNotificationService` nie wirft, braucht der Handler keinen eigenen try/catch.

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramSendNodeHandlerTest {

    @Mock
    private TelegramNotificationService notificationService;

    private TelegramSendNodeHandler handler() {
        return new TelegramSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(notificationService).sendToAllowedChats("switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitChatIdSendsOnlyThere() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "chatId", "42"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(notificationService).sendTo(42L, "hi");
    }

    @Test
    void validateRequiresMessageAndNumericChatId() {
        assertFalse(handler().validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("message", "x", "chatId", "abc"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x", "chatId", "42"))).isEmpty());
    }

    @Test
    void typeAndPortsMatchCatalogExpectations() {
        assertEquals("telegram-send", handler().type());
        assertEquals(1, handler().outputPorts());
        assertFalse(handler().fields().isEmpty());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
mvn test -Dtest=TelegramSendNodeHandlerTest
```

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.telegram.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: Telegram-Nachricht an die erlaubten Chats (oder einen
 * bestimmten Chat). Platzhalter: {entityId}, {newState}, {oldState}.
 * Sendefehler schluckt der NotificationService — der Flow läuft weiter.
 */
@Component
@RequiredArgsConstructor
public class TelegramSendNodeHandler implements NodeHandler {

    private final TelegramNotificationService notificationService;

    @Override
    public String type() {
        return "telegram-send";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("message").isEmpty()) {
            errors.add("message fehlt");
        }
        config.string("chatId").ifPresent(chatId -> {
            try {
                Long.parseLong(chatId.trim());
            } catch (NumberFormatException ex) {
                errors.add("chatId muss numerisch sein");
            }
        });
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String text = render(config.string("message").orElse(""), message);
        config.string("chatId")
                .map(String::trim)
                .filter(chatId -> !chatId.isEmpty())
                .ifPresentOrElse(
                        chatId -> notificationService.sendTo(Long.parseLong(chatId), text),
                        () -> notificationService.sendToAllowedChats(text));
        return NodeResult.single(message);
    }

    private String render(String template, FlowMessage message) {
        return template
                .replace("{entityId}", stringValue(message, "entityId"))
                .replace("{newState}", stringValue(message, "newState"))
                .replace("{oldState}", stringValue(message, "oldState"));
    }

    private String stringValue(FlowMessage message, String key) {
        Object value = message.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("message", "Nachricht", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field("chatId", "Chat-ID (leer = alle erlaubten)", NodeFieldType.STRING, false));
    }
}
```

- [ ] **Step 4: Tests ausführen — inkl. Katalog-Test, der alle Handler prüft**

```bash
mvn test -Dtest='TelegramSendNodeHandlerTest,NodeCatalogFieldsTest'
```

Erwartet: beide grün. Falls `NodeCatalogFieldsTest` Anforderungen an neue Handler stellt (z. B. Labels), dort nachlesen und erfüllen.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/TelegramSendNodeHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/TelegramSendNodeHandlerTest.java
git commit -m "feat(flowengine): telegram-send-Node fuer Push-Nachrichten"
```

---

### Task 11: Deployment-Konfiguration + Doku + Gesamtlauf

**Files:**
- Modify: `docker-compose.yml` (backend-Service, `environment`-Block)
- Modify: `CLAUDE.md` (neuer Abschnitt unter „Smart Device Integrations")
- Modify: `docs/flows/flow-import-format.md` (falls dort Node-Typen aufgezählt werden: `telegram-send` ergänzen — Datei zuerst lesen)

- [ ] **Step 1: docker-compose.yml erweitern**

Im `environment`-Block des backend-Services ergänzen (Stil an vorhandene Einträge wie `NUKI_API_TOKEN` anpassen — Datei zuerst lesen):

```yaml
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN:-}
      TELEGRAM_ALLOWED_CHAT_IDS: ${TELEGRAM_ALLOWED_CHAT_IDS:-}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}
```

- [ ] **Step 2: CLAUDE.md ergänzen**

Neuer Abschnitt nach „### Nuki Smart Lock (Web API)":

```markdown
### Telegram-KI-Assistent
- Telegram-Bot direkt im Spring-Backend (`backend/src/main/java/com/household/manager/telegram/`); Long-Polling gegen die Bot-API — keine Portfreigabe nötig
- Sprachverständnis über die Anthropic Messages API mit Tool-Use (`TELEGRAM_AGENT_MODEL`, Default Haiku); Tools sind dünne Wrapper um bestehende Services (Schalter, Entity-States, Verbraucher, Zähler, Modi, Nuki)
- **Sicherheit:** Allowlist über `TELEGRAM_ALLOWED_CHAT_IDS` (fremde Chats werden komplett ignoriert); das Nuki-Tool kann ausschließlich verriegeln — unlock/unlatch existiert im Tool-Vertrag nicht (Code-Garantie, kein Prompt-Schutz)
- Secrets nur per Env: `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`; ohne vollständige Konfiguration startet das Polling nicht
- Gesprächskontext pro Chat in-memory (TTL 30 Min); keine DB-Änderung, kein Frontend
- Push-Richtung: Flow-Node `telegram-send` (Nachricht an alle erlaubten oder einen bestimmten Chat), Platzhalter wie beim Alexa-Node
- Bot-Setup: Bot bei @BotFather anlegen → Token als `TELEGRAM_BOT_TOKEN`; eigene Chat-ID z. B. über @userinfobot ermitteln → `TELEGRAM_ALLOWED_CHAT_IDS`
```

- [ ] **Step 3: Kompletten Backend-Testlauf ausführen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```

Erwartet: alles grün außer den bekannten, vorbestehenden DB-Fails (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`).

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml CLAUDE.md docs/flows/flow-import-format.md
git commit -m "docs: Telegram-KI-Assistent dokumentiert und deploybar konfiguriert"
```

---

## Manuelle Verifikation (nach der Implementierung, mit dem Nutzer)

Nicht Teil der automatisierten Tasks — braucht echte Secrets:

1. Bot bei **@BotFather** anlegen (`/newbot`) → Token notieren.
2. Eigene Chat-ID ermitteln (Nachricht an den Bot senden, dann `https://api.telegram.org/bot<TOKEN>/getUpdates` im Browser öffnen — `message.chat.id`).
3. `TELEGRAM_BOT_TOKEN`, `TELEGRAM_ALLOWED_CHAT_IDS`, `ANTHROPIC_API_KEY` setzen, Backend starten.
4. Smoke-Tests im Chat: „Welche Lampen sind an?" → „Schalte <Gerät> an" → „Ist die Tür zu?" → „Schließe ab" → Bitte um Entriegeln (muss abgelehnt werden).
5. Testflow mit `telegram-send` per Flow-MCP anlegen (`flow_inject` zum Auslösen) und Push prüfen.
