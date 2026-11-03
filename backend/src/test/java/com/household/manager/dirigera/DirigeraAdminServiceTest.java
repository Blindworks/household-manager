package com.household.manager.dirigera;

import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraDtos.ButtonView;
import com.household.manager.dirigera.DirigeraDtos.HubView;
import com.household.manager.dirigera.DirigeraEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirigeraAdminServiceTest {

    private static final String RELATION_ID = "abc-123";

    private DirigeraSensorRepository sensorRepository;
    private EntityTileVisibilityRepository tileRepository;
    private EntityStateService entityStateService;
    private AuditService auditService;
    private DirigeraButtonRegistry buttons;
    private DirigeraEventStreamService eventStream;
    private DirigeraSettingsService settingsService;
    private DirigeraPollingService pollingService;
    private DirigeraAdminService service;

    @BeforeEach
    void setUp() {
        sensorRepository = mock(DirigeraSensorRepository.class);
        tileRepository = mock(EntityTileVisibilityRepository.class);
        entityStateService = mock(EntityStateService.class);
        auditService = mock(AuditService.class);
        buttons = new DirigeraButtonRegistry();
        eventStream = mock(DirigeraEventStreamService.class);
        settingsService = mock(DirigeraSettingsService.class);
        pollingService = mock(DirigeraPollingService.class);
        service = new DirigeraAdminService(settingsService, mock(DirigeraPairingService.class),
                pollingService, eventStream, buttons, sensorRepository, tileRepository, entityStateService,
                auditService);
    }

    @Test
    void tasterListeZeigtJeTasteNameStellungUndLetztenDruck() {
        buttons.replaceAll(List.of(
                new DirigeraButton("b_2", "Button Treppenhaus", null, "BILRESA dual button", "bottom", 100),
                new DirigeraButton("b_1", "Button Treppenhaus", "Treppenhaus", "BILRESA dual button", "top", 100)));
        when(entityStateService.getByEntityId(DirigeraEntityMapper.buttonEntityId("b_1"))).thenReturn(Optional.of(
                EntityState.builder().state("double").lastChanged(LocalDateTime.of(2026, 11, 3, 18, 52, 55)).build()));
        when(entityStateService.getByEntityId(DirigeraEntityMapper.buttonEntityId("b_2"))).thenReturn(Optional.empty());

        List<ButtonView> views = service.buttons();

        // oben vor unten, auch wenn der Hub sie andersherum listet
        assertThat(views).extracting(ButtonView::deviceId).containsExactly("b_1", "b_2");
        ButtonView top = views.get(0);
        assertThat(top.entityId()).isEqualTo("event.dirigera_b_1_action");
        assertThat(top.position()).isEqualTo("top");
        assertThat(top.room()).isEqualTo("Treppenhaus");
        assertThat(top.batteryPercent()).isEqualTo(100);
        assertThat(top.lastAction()).isEqualTo("double");
        assertThat(top.lastPressedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 18, 52, 55));
        // noch nie gedrueckt: keine Aktion, kein Zeitpunkt
        assertThat(views.get(1).lastAction()).isNull();
        assertThat(views.get(1).lastPressedAt()).isNull();
    }

    @Test
    void tasterSortierenNachNamenDannStellung() {
        buttons.replaceAll(List.of(
                new DirigeraButton("z_1", "Zimmer", null, null, "top", null),
                new DirigeraButton("a_2", "Abstellraum", null, null, "bottom", null),
                new DirigeraButton("a_1", "Abstellraum", null, null, "top", null)));
        when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());

        assertThat(service.buttons()).extracting(ButtonView::deviceId).containsExactly("a_1", "a_2", "z_1");
    }

    @Test
    void hubZeigtObDerEreignisstromVerbundenIst() {
        when(settingsService.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", "t", "c", "h"));
        when(pollingService.lastStatus()).thenReturn(new DirigeraPollingService.PollStatus(null, null));
        when(eventStream.isConnected()).thenReturn(true);

        assertThat(service.hub().eventStreamConnected()).isTrue();
    }

    @Test
    void loeschenEinesBekanntenSensorsWirft409() {
        when(sensorRepository.findById(1L)).thenReturn(Optional.of(sensor(true)));

        assertThatThrownBy(() -> service.deleteSensor(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(sensorRepository, never()).delete(any());
        verify(entityStateService, never()).deleteByEntityId(any());
    }

    @Test
    void loeschenEntferntEntitaetenTileRegelnUndZeile() {
        DirigeraSensor sensor = sensor(false);
        when(sensorRepository.findById(1L)).thenReturn(Optional.of(sensor));
        List<String> entityIds = Arrays.stream(Measurement.values())
                .map(m -> DirigeraEntityMapper.entityId(RELATION_ID, m)).toList();
        List<EntityTileVisibility> rules = List.of(new EntityTileVisibility());
        when(tileRepository.findByEntityIdIn(entityIds)).thenReturn(rules);

        service.deleteSensor(1L);

        verify(tileRepository).deleteAll(rules);
        entityIds.forEach(id -> verify(entityStateService).deleteByEntityId(id));
        verify(sensorRepository).delete(sensor);
        verify(auditService).record("dirigera.sensor.delete", "Wohnzimmer");
    }

    @Test
    void loeschenEinesKontaktsEntferntDieKontaktEntitaet() {
        DirigeraSensor sensor = sensor(false);
        sensor.setKind(DirigeraSensorKind.CONTACT);
        when(sensorRepository.findById(1L)).thenReturn(Optional.of(sensor));
        String contactId = DirigeraEntityMapper.contactEntityId(RELATION_ID);

        service.deleteSensor(1L);

        verify(tileRepository).findByEntityIdIn(List.of(contactId));
        verify(entityStateService).deleteByEntityId(contactId);
        verify(sensorRepository).delete(sensor);
    }

    @Test
    void sensorViewTraegtArtUndOffenZustand() {
        DirigeraSensor sensor = sensor(true);
        sensor.setKind(DirigeraSensorKind.CONTACT);
        sensor.setLastOpen(true);
        when(sensorRepository.findAll()).thenReturn(List.of(sensor));

        assertThat(service.sensors()).singleElement().satisfies(view -> {
            assertThat(view.kind()).isEqualTo(DirigeraSensorKind.CONTACT);
            assertThat(view.open()).isTrue();
        });
    }

    @Test
    void hubViewEnthaeltKeinenToken() {
        List<String> components = Arrays.stream(HubView.class.getRecordComponents())
                .map(c -> c.getName()).toList();

        assertThat(components).doesNotContain("accessToken", "certSha256");
    }

    private static DirigeraSensor sensor(boolean knownToHub) {
        return DirigeraSensor.builder().id(1L).relationId(RELATION_ID).name("Wohnzimmer")
                .knownToHub(knownToHub).build();
    }
}
