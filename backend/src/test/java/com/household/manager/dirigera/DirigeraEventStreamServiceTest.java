package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dirigera.client.DirigeraCertificateMismatchException;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirigeraEventStreamServiceTest {

    private static final DirigeraHubSettings PAIRED = new DirigeraHubSettings("192.168.1.159", "tok", "abc", "hub_1");
    private static final long INTERVAL_MS = 20_000;
    private static final String TOP = "4ab740b2-efb5-4301-8bf5-63c702722938_1";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-03T17:00:00Z"));
    private DirigeraSettingsService settings;
    private DirigeraHttpClient http;
    private DirigeraButtonRegistry registry;
    private EntityStateService entityStates;
    private SystemJobMonitor jobMonitor;
    private DirigeraPollingService pollingService;
    private DirigeraEventStreamService service;
    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<WebSocket.Listener> listeners = new ArrayList<>();

    @BeforeEach
    void setUp() {
        settings = mock(DirigeraSettingsService.class);
        http = mock(DirigeraHttpClient.class);
        registry = new DirigeraButtonRegistry();
        entityStates = mock(EntityStateService.class);
        jobMonitor = mock(SystemJobMonitor.class);
        pollingService = mock(DirigeraPollingService.class);
        service = newService(true);
        when(settings.get()).thenReturn(PAIRED);
        when(http.openEventStream(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            WebSocket ws = mock(WebSocket.class);
            when(ws.sendPing(any())).thenReturn(CompletableFuture.completedFuture(ws));
            sockets.add(ws);
            listeners.add(inv.getArgument(3));
            return CompletableFuture.completedFuture(ws);
        });
    }

    private DirigeraEventStreamService newService(boolean enabled) {
        return new DirigeraEventStreamService(settings, http, new DirigeraEventParser(new ObjectMapper()),
                new DirigeraEntityMapper(), registry, entityStates, pollingService, jobMonitor, clock, enabled, INTERVAL_MS);
    }

    @Test
    void ohneKopplungKeineVerbindungUndInaktiv() {
        when(settings.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", null, null, null));

        service.supervise();

        verify(http, never()).openEventStream(anyString(), anyString(), anyString(), any());
        verify(jobMonitor).reportInactive("nicht gekoppelt");
    }

    @Test
    void abgeschaltetVerbindetNicht() {
        newService(false).supervise();

        verify(http, never()).openEventStream(anyString(), anyString(), anyString(), any());
    }

    @Test
    void gekoppeltVerbindetMitGepinntemZertifikatUndToken() {
        service.supervise();

        verify(http).openEventStream("192.168.1.159", "abc", "tok", listeners.get(0));
        verify(jobMonitor, never()).reportFailure(anyString());
        assertThat(service.isConnected()).isTrue();
    }

    @Test
    void bestehendeVerbindungWirdNurAngepingt() {
        service.supervise();
        clock.advance(Duration.ofSeconds(20));
        service.supervise();

        verify(http, times(1)).openEventStream(anyString(), anyString(), anyString(), any());
        verify(sockets.get(0)).sendPing(any(ByteBuffer.class));
    }

    @Test
    void neuesTokenBautDieVerbindungNeuAuf() {
        service.supervise();
        when(settings.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", "neu", "abc", "hub_1"));

        service.supervise();

        verify(sockets.get(0)).abort();
        verify(http).openEventStream("192.168.1.159", "abc", "neu", listeners.get(1));
    }

    @Test
    void entkoppelnSchliesstDieVerbindung() {
        service.supervise();
        when(settings.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", null, null, null));

        service.supervise();

        verify(sockets.get(0)).abort();
        assertThat(service.isConnected()).isFalse();
    }

    @Test
    void vomHubGeschlossenWirdBeimNaechstenMalNeuVerbunden() {
        service.supervise();
        listeners.get(0).onClose(sockets.get(0), 1006, "weg");
        assertThat(service.isConnected()).isFalse();

        service.supervise();

        verify(http, times(2)).openEventStream(anyString(), anyString(), anyString(), any());
    }

    @Test
    void fehlerDerVerbindungWirdNeuVerbunden() {
        service.supervise();
        listeners.get(0).onError(sockets.get(0), new IOException("reset"));

        service.supervise();

        verify(http, times(2)).openEventStream(anyString(), anyString(), anyString(), any());
    }

    @Test
    void stilleVerbindungGiltAlsTotUndWirdErsetzt() {
        service.supervise();
        clock.advance(Duration.ofMillis(3 * INTERVAL_MS + 1));

        service.supervise();

        verify(sockets.get(0)).abort();
        verify(http, times(2)).openEventStream(anyString(), anyString(), anyString(), any());
    }

    @Test
    void pongHaeltDieVerbindungAmLeben() {
        service.supervise();
        clock.advance(Duration.ofMillis(2 * INTERVAL_MS));
        listeners.get(0).onPong(sockets.get(0), ByteBuffer.allocate(0));
        clock.advance(Duration.ofMillis(2 * INTERVAL_MS));

        service.supervise();

        verify(sockets.get(0), never()).abort();
    }

    @Test
    void scheiternderAufbauIstEineStoerung() {
        when(http.openEventStream(anyString(), anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("connection refused")));

        assertThatCode(service::supervise).doesNotThrowAnyException();

        verify(jobMonitor).reportFailure(org.mockito.ArgumentMatchers.contains("connection refused"));
        assertThat(service.isConnected()).isFalse();
    }

    @Test
    void falschesZertifikatVerlangtNeukoppeln() {
        when(http.openEventStream(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DirigeraCertificateMismatchException("anders", null));

        service.supervise();

        verify(jobMonitor).reportFailure("Zertifikat geändert – neu koppeln");
    }

    @Test
    void tastendruckWirdAlsEreignisGemeldet() throws IOException {
        registry.replaceAll(List.of(new DirigeraButton(TOP, "Button Treppenhaus", "Treppenhaus", "BILRESA dual button", "top", 100)));
        service.supervise();

        listeners.get(0).onText(sockets.get(0), fixture("remote-press-event.json"), true);

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.dirigera_4ab740b2efb543018bf563c702722938_1_action");
        assertThat(captor.getValue().state()).isEqualTo("single");
        assertThat(captor.getValue().friendlyName()).isEqualTo("Button Treppenhaus oben");
        verify(sockets.get(0)).request(1);
    }

    @Test
    void nachrichtInTeilenWirdErstAmEndeAusgewertet() throws IOException {
        service.supervise();
        String json = fixture("remote-press-event.json");

        listeners.get(0).onText(sockets.get(0), json.substring(0, 50), false);
        verify(entityStates, never()).reportEvent(any());
        listeners.get(0).onText(sockets.get(0), json.substring(50), true);

        verify(entityStates).reportEvent(any());
    }

    @Test
    void untereTasteMeldetIhreEigeneEntitaet() throws IOException {
        service.supervise();

        listeners.get(0).onText(sockets.get(0), fixture("remote-press-bottom-event.json"), true);

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.dirigera_4ab740b2efb543018bf563c702722938_2_action");
        assertThat(captor.getValue().state()).isEqualTo("double");
    }

    @Test
    void fensterEreignisGehtAnDenPollingDienst() throws IOException {
        service.supervise();

        listeners.get(0).onText(sockets.get(0), fixture("contact-open-event.json"), true);

        verify(pollingService).applyContactChange(new DirigeraContactChange(
                "07ddd369-cdff-4593-a0f5-c995dfcea5f2_1", true, Instant.parse("2026-11-03T17:35:17Z"), true));
        verify(entityStates, never()).reportEvent(any());
    }

    @Test
    void zustandsaenderungenErzeugenKeinEreignis() throws IOException {
        service.supervise();

        listeners.get(0).onText(sockets.get(0), fixture("device-state-changed-event.json"), true);

        verify(entityStates, never()).reportEvent(any());
    }

    @Test
    void fehlerBeimMeldenReisstDieVerbindungNichtAb() throws IOException {
        service.supervise();
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(entityStates).reportEvent(any());

        assertThatCode(() -> listeners.get(0).onText(sockets.get(0), fixture("remote-press-event.json"), true))
                .doesNotThrowAnyException();

        verify(sockets.get(0)).request(1);
    }

    @Test
    void nachrichtEinerAltenVerbindungWirdIgnoriert() throws IOException {
        service.supervise();
        when(settings.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", "neu", "abc", "hub_1"));
        service.supervise();

        listeners.get(0).onText(sockets.get(0), fixture("remote-press-event.json"), true);

        verify(entityStates, never()).reportEvent(any());
    }

    private static String fixture(String name) throws IOException {
        try (var in = DirigeraEventStreamServiceTest.class.getResourceAsStream("/dirigera/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
