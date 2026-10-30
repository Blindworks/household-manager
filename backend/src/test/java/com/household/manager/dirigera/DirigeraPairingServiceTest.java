package com.household.manager.dirigera;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorResolver;
import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraPairingStatus.State;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.dirigera.client.Pkce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirigeraPairingServiceTest {

    private static final String HOST = "192.168.1.50";
    private static final String FP = "aa:bb";
    private static final String GATEWAY_ID = "05f1aba0-b770-479e-994a-f167e187ce32_1";
    private static final AuditActor ADMIN = AuditActor.user("admin");

    private MutableClock clock;
    private DirigeraSettingsService settings;
    private DirigeraHttpClient http;
    private DirigeraDeviceParser parser;
    private DirigeraPollingService polling;
    private AuditService audit;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private DirigeraPairingService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-30T09:00:00Z"));
        settings = mock(DirigeraSettingsService.class);
        http = mock(DirigeraHttpClient.class);
        parser = mock(DirigeraDeviceParser.class);
        polling = mock(DirigeraPollingService.class);
        audit = mock(AuditService.class);
        AuditActorResolver actorResolver = mock(AuditActorResolver.class);
        when(actorResolver.currentActor()).thenReturn(ADMIN);
        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        when(settings.get()).thenReturn(new DirigeraHubSettings(HOST, null, null, null));
        when(http.fetchFingerprint(HOST)).thenReturn(FP);
        when(http.authorize(eq(HOST), eq(FP), anyString())).thenReturn("code-1");

        service = new DirigeraPairingService(settings, http, parser, polling, audit, actorResolver, clock, executor);
    }

    @Test
    void startOhneHostWirftIllegalState() {
        when(settings.get()).thenReturn(new DirigeraHubSettings(null, null, null, null));

        assertThatThrownBy(() -> service.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hub-Adresse");
        verify(http, never()).fetchFingerprint(anyString());
    }

    @Test
    void startLiefertPressButtonMit90Sekunden() {
        DirigeraPairingStatus status = service.start();

        assertThat(status.state()).isEqualTo(State.PRESS_BUTTON);
        assertThat(status.expiresAt()).isEqualTo(clock.instant().plusSeconds(90));
        assertThat(status.error()).isNull();
        assertThat(service.status()).isEqualTo(status);
        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(2L), eq(2L), eq(TimeUnit.SECONDS));
    }

    @Test
    void fehlerVorDemEinplanenWirftUndBleibtIdle() {
        when(http.authorize(eq(HOST), eq(FP), anyString())).thenThrow(new DirigeraException("Hub antwortet mit HTTP 500"));

        assertThatThrownBy(() -> service.start()).isInstanceOf(DirigeraException.class);

        assertThat(service.status().state()).isEqualTo(State.IDLE);
        verify(executor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        // Ein Fehlschlag darf den naechsten Versuch nicht blockieren
        when(http.authorize(eq(HOST), eq(FP), anyString())).thenReturn("code-2");
        assertThat(service.start().state()).isEqualTo(State.PRESS_BUTTON);
    }

    @Test
    void zweiterStartWaehrendPressButtonWirft409() {
        service.start();

        assertThatThrownBy(() -> service.start()).isInstanceOf(PairingInProgressException.class);
        verify(http).fetchFingerprint(HOST);
    }

    @Test
    void tokenNachDrittemVersuchSpeichertPairingUndAudit() {
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of("tok"));
        when(http.getDevices(HOST, FP, "tok")).thenReturn("[devices]");
        when(parser.parse("[devices]")).thenReturn(new DirigeraDevices(GATEWAY_ID, List.of()));
        service.start();
        Runnable tick = capturedTick();

        tick.run();
        tick.run();
        verify(settings, never()).savePairing(anyString(), anyString(), anyString());
        tick.run();

        verify(settings).savePairing("tok", FP, GATEWAY_ID);
        verify(audit).record(ADMIN, "dirigera.pair", HOST);
        verify(future).cancel(false);
        DirigeraPairingStatus status = service.status();
        assertThat(status.state()).isEqualTo(State.PAIRED);
        assertThat(status.error()).isNull();
        assertThat(status.toString()).doesNotContain("tok").doesNotContain(FP);
    }

    @Test
    void verifierPasstZurChallenge() {
        ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), verifier.capture())).thenReturn(Optional.empty());
        service.start();
        verify(http).authorize(eq(HOST), eq(FP), challenge.capture());

        capturedTick().run();

        assertThat(Pkce.challengeOf(verifier.getValue()))
                .isEqualTo(challenge.getValue());
    }

    @Test
    void ohneGatewayIdFailed() {
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString())).thenReturn(Optional.of("tok"));
        when(http.getDevices(HOST, FP, "tok")).thenReturn("[]");
        when(parser.parse("[]")).thenReturn(new DirigeraDevices(null, List.of()));
        service.start();

        capturedTick().run();

        assertThat(service.status().state()).isEqualTo(State.FAILED);
        assertThat(service.status().error()).doesNotContain("tok");
        verify(settings, never()).savePairing(anyString(), anyString(), anyString());
        verify(audit, never()).record(any(AuditActor.class), eq("dirigera.pair"), anyString());
    }

    @Test
    void ohneTokenNach90SekundenFailedMitLetzterAntwort() {
        String longBody = "{\"error\":\"" + "x".repeat(300) + "\"}";
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString())).thenReturn(Optional.empty());
        when(http.lastTokenResponse()).thenReturn(Optional.of(new DirigeraHttpClient.TokenResponse(403, longBody)));
        service.start();
        Runnable tick = capturedTick();

        clock.advance(Duration.ofSeconds(88));
        tick.run();
        assertThat(service.status().state()).isEqualTo(State.PRESS_BUTTON);

        clock.advance(Duration.ofSeconds(2));
        tick.run();

        DirigeraPairingStatus status = service.status();
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.error())
                .isEqualTo("Kein Knopfdruck innerhalb von 90 s (letzte Antwort: 403 " + longBody.substring(0, 200) + ")");
        verify(future).cancel(false);
        verify(settings, never()).savePairing(anyString(), anyString(), anyString());
    }

    @Test
    void timeoutZeigtKeinenTokenAusAlterAntwort() {
        // Nur Verbindungsfehler in diesem Versuch: die letzte Antwort des Clients stammt aus einem
        // frueheren (erfolgreichen) Kopplungsvorgang und darf nicht in den Fehlertext.
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString()))
                .thenThrow(new DirigeraException("Hub nicht erreichbar"));
        when(http.lastTokenResponse()).thenReturn(Optional.of(
                new DirigeraHttpClient.TokenResponse(200, "{\"access_token\":\"geheim\"}")));
        service.start();

        clock.advance(Duration.ofSeconds(90));
        capturedTick().run();

        assertThat(service.status().state()).isEqualTo(State.FAILED);
        assertThat(service.status().error()).doesNotContain("geheim");
    }

    @Test
    void verbindungsfehlerZaehltAlsWarten() {
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString()))
                .thenThrow(new DirigeraException("Hub nicht erreichbar"))
                .thenReturn(Optional.of("tok"));
        when(http.getDevices(HOST, FP, "tok")).thenReturn("[devices]");
        when(parser.parse("[devices]")).thenReturn(new DirigeraDevices(GATEWAY_ID, List.of()));
        service.start();
        Runnable tick = capturedTick();

        tick.run();
        assertThat(service.status().state()).isEqualTo(State.PRESS_BUTTON);
        tick.run();

        assertThat(service.status().state()).isEqualTo(State.PAIRED);
    }

    @Test
    void neuKoppelnUeberschreibtHubIdOhne409() {
        when(settings.get()).thenReturn(new DirigeraHubSettings(HOST, "alt", "old-fp", "anderer-hub_1"));
        when(http.requestToken(eq(HOST), eq(FP), eq("code-1"), anyString())).thenReturn(Optional.of("tok"));
        when(http.getDevices(HOST, FP, "tok")).thenReturn("[devices]");
        when(parser.parse("[devices]")).thenReturn(new DirigeraDevices(GATEWAY_ID, List.of()));

        service.start();
        capturedTick().run();

        verify(settings).savePairing("tok", FP, GATEWAY_ID);
        assertThat(service.status().state()).isEqualTo(State.PAIRED);
    }

    @Test
    void unpairLoeschtUndMarkiertUnavailable() {
        service.unpair();

        verify(settings).clearPairing();
        verify(polling).onUnpaired();
        verify(audit).record("dirigera.unpair", HOST);
    }

    @Test
    void updateHostLehntSchraegstrichAb() {
        assertThatThrownBy(() -> service.updateHost("192.168.1.50/api")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateHost("192.168 .1.50")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateHost("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateHost("a".repeat(254))).isInstanceOf(IllegalArgumentException.class);
        verify(settings, never()).saveHost(anyString());

        service.updateHost(" 192.168.1.51 ");

        verify(settings).saveHost("192.168.1.51");
        verify(audit).record("dirigera.hub.update", "192.168.1.51");
    }

    @Test
    void nachFailedIstNeuerStartMoeglich() {
        when(http.requestToken(eq(HOST), eq(FP), anyString(), anyString())).thenReturn(Optional.empty());
        service.start();
        Runnable first = capturedTick();
        clock.advance(Duration.ofSeconds(90));
        first.run();
        assertThat(service.status().state()).isEqualTo(State.FAILED);

        DirigeraPairingStatus again = service.start();

        assertThat(again.state()).isEqualTo(State.PRESS_BUTTON);
        assertThat(again.error()).isNull();
        assertThat(again.expiresAt()).isEqualTo(clock.instant().plusSeconds(90));
        // Ein verspaeteter Lauf des alten Tasks darf den neuen Versuch nicht beenden
        first.run();
        assertThat(service.status().state()).isEqualTo(State.PRESS_BUTTON);
    }

    @Test
    void shutdownBeendetExecutor() {
        service.shutdown();

        verify(executor).shutdownNow();
    }

    private Runnable capturedTick() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, atLeastOnce())
                .scheduleWithFixedDelay(captor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        return captor.getValue();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
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
