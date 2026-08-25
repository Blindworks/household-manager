package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.calendar.CalendarCategoryController;
import com.household.manager.calendar.CalendarCategoryService;
import com.household.manager.calendar.CalendarEventController;
import com.household.manager.calendar.CalendarEventService;
import com.household.manager.controller.SmartDeviceController;
import com.household.manager.controller.SwitchController;
import com.household.manager.controller.TabletPresenceController;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.nuki.NukiController;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.petfood.PetFoodController;
import com.household.manager.petfood.PetFoodService;
import com.household.manager.push.PushController;
import com.household.manager.push.PushNotificationService;
import com.household.manager.push.PushSubscriptionService;
import com.household.manager.push.VapidKeyService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.service.SmartDeviceService;
import com.household.manager.system.SystemController;
import com.household.manager.system.SystemRebootService;
import com.household.manager.tablet.TabletPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc-Slice ueber drei repraesentative Controller + die echte SecurityConfig,
 * um die Rollenmatrix aus SecurityConfig gegen echte HTTP-Requests abzusichern.
 * URLs ohne Controller im Slice (z. B. /v1/flows, /v1/tractive/login) liefern bei
 * erlaubter Rolle 404 statt 403 — das reicht, um zu belegen, dass die
 * Autorisierungsregel durchlaesst (403 waere der Fehlerfall).
 *
 * <p>{@code GlobalExceptionHandler} wird aus dem Slice ausgeschlossen: sein
 * Exception.class-Catch-all faengt sonst Springs NoResourceFoundException fuer
 * Pfade ohne Controller ab und macht daraus einen 500 statt eines 404 — das
 * wuerde die "404 statt 403 belegt, dass die Regel durchlaesst"-Tests unten
 * verfaelschen. Betrifft nur diesen Test-Slice, nicht die echte Anwendung.
 */
@WebMvcTest(controllers = {SwitchController.class, CalendarEventController.class,
        CalendarCategoryController.class, NukiController.class, TabletPresenceController.class,
        HouseholdUserController.class, SystemController.class, PetFoodController.class,
        PushController.class, SmartDeviceController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = com.household.manager.exception.GlobalExceptionHandler.class))
@Import({SecurityConfig.class, ServiceTokenAuthFilter.class, DisabledUserSessionFilter.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SwitchQueryService switchQueryService;
    @MockitoBean
    private SwitchCommandService switchCommandService;
    @MockitoBean
    private CalendarEventService calendarEventService;
    @MockitoBean
    private CalendarCategoryService calendarCategoryService;
    @MockitoBean
    private NukiLockService nukiLockService;
    @MockitoBean
    private TabletPresenceService tabletPresenceService;
    @MockitoBean
    private SystemRebootService systemRebootService;
    @MockitoBean
    private AppUserService appUserService;
    @MockitoBean
    private ServiceTokenService serviceTokenService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;
    @MockitoBean
    private PetFoodService petFoodService;
    @MockitoBean
    private VapidKeyService vapidKeyService;
    @MockitoBean
    private PushSubscriptionService pushSubscriptionService;
    @MockitoBean
    private PushNotificationService pushNotificationService;
    @MockitoBean
    private CurrentUserService currentUserService;
    @MockitoBean
    private SmartDeviceService smartDeviceService;
    @MockitoBean
    private AuditService auditService;

    /**
     * DisabledUserSessionFilter fragt bei jedem Request mit einem UserDetails-Principal
     * (das setzt auch @WithMockUser) den AppUserRepository nach dem Enabled-Status.
     * Unbestubbt liefert der Mock Optional.empty() -> orElse(true) -> die Session wird
     * geloescht und jeder eigentlich erlaubte Request landet faelschlich bei 401. Ein
     * global aktivierter, immer-enabled Nutzer haelt den Filter aus dem Weg der
     * eigentlichen Rollenmatrix-Tests.
     */
    @BeforeEach
    void appUserIsAlwaysEnabled() {
        lenient().when(appUserRepository.findByUsername(anyString())).thenReturn(Optional.of(
                AppUser.builder().username("user").displayName("Test").passwordHash("x")
                        .role(UserRole.ADMIN).enabled(true).build()));
    }

    @Test
    void anonymBekommt401() throws Exception {
        mockMvc.perform(get("/v1/switches")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymDarfHealthAbfragen() throws Exception {
        // Kein HealthController im Slice: 404 statt 401/403 belegt, dass /v1/health oeffentlich bleibt
        mockMvc.perform(get("/v1/health")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfRebootAusloesen() throws Exception {
        mockMvc.perform(post("/v1/system/reboot").with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfSchalterLesenUndSchalten() throws Exception {
        when(switchQueryService.listSwitches(null, false)).thenReturn(List.of());
        mockMvc.perform(get("/v1/switches")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/switches/switch.x/toggle").with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineKalenderTermineAnlegen() throws Exception {
        mockMvc.perform(post("/v1/calendar/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKalenderTermineAnlegen() throws Exception {
        mockMvc.perform(post("/v1/calendar/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    /**
     * Die Kategorien sind Stammdaten der Kalenderansicht: das Wandtablet muss Namen und
     * Farben lesen duerfen, aendern darf sie nur ADMIN.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKategorienLesen() throws Exception {
        mockMvc.perform(get("/v1/calendar/categories")).andExpect(status().isOk());
    }

    /**
     * /v1/users braucht bewusst keine eigene Regel: das GET faellt auf die generische
     * Regel GET /v1/** -> KIOSK. Dieser Test haelt das fest, damit eine spaetere
     * Umsortierung der Matcher nicht unbemerkt den Zugriff des Wandtablets kappt.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieNutzerlisteLesen() throws Exception {
        when(appUserService.list()).thenReturn(List.of());
        mockMvc.perform(get("/v1/users")).andExpect(status().isOk());
    }

    /**
     * Anlegen, Aendern und Loeschen haengen an je einem eigenen methodenspezifischen
     * Matcher — jede der drei Zeilen braucht ihren eigenen Test, sonst faellt der
     * Wegfall einer davon niemandem auf.
     */
    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKategorienNichtAnlegen() throws Exception {
        mockMvc.perform(post("/v1/calendar/categories")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"color\":\"#4caf50\",\"sortOrder\":1,\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKategorienNichtUmbenennen() throws Exception {
        mockMvc.perform(put("/v1/calendar/categories/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"color\":\"#4caf50\",\"sortOrder\":1,\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKategorienNichtLoeschen() throws Exception {
        mockMvc.perform(delete("/v1/calendar/categories/1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeineFlowsVerwalten() throws Exception {
        mockMvc.perform(get("/v1/flows")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnFlowsVorbei() throws Exception {
        // Kein FlowController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst
        mockMvc.perform(get("/v1/flows")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfNukiNurVerriegeln() throws Exception {
        mockMvc.perform(post("/v1/nuki/locks/1/actions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLATCH\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/nuki/locks/1/actions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"LOCK\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void postOhneCsrfTokenWirdAbgelehnt() throws Exception {
        mockMvc.perform(post("/v1/switches/switch.x/toggle"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenDarfTabletPresenceMelden() throws Exception {
        when(serviceTokenService.authenticate(anyString())).thenReturn(Optional.of(
                ServiceToken.builder().name("tablet").role(UserRole.KIOSK).enabled(true).build()));
        mockMvc.perform(post("/v1/tablet-presence/wandtablet")
                        .header("X-API-Token", "hm_ok")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"present\":true}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void browserSessionOhneServiceAuthorityKommtNichtAnTabletPresence() throws Exception {
        mockMvc.perform(post("/v1/tablet-presence/wandtablet").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"present\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskKommtNichtAnFinanzdaten() throws Exception {
        mockMvc.perform(get("/v1/finance/transactions")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinTractiveLoginAusloesen() throws Exception {
        // Credential-Endpunkt ist ADMIN-only, auch fuer MEMBER gesperrt
        mockMvc.perform(post("/v1/tractive/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnTractiveLoginVorbei() throws Exception {
        // Kein TractiveController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst
        mockMvc.perform(post("/v1/tractive/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Die generische Regel GET /v1/** laesst KIOSK lesen. Nur weil der ADMIN-Block in
     * SecurityConfig davor steht, bleibt die Home-Definition dem Wandtablet verborgen –
     * dieser Test haelt genau diese Reihenfolge fest.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieHomeEinstellungenNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/tractive/home-settings")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfDieHomeEinstellungenLesen() throws Exception {
        // Kein TractiveHomeSettingsController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
        mockMvc.perform(get("/v1/tractive/home-settings")).andExpect(status().isNotFound());
    }

    /**
     * Der erzwungene Abruf schaltet nichts, er zieht nur Daten – ohne diese Regel waere der
     * Aktualisieren-Knopf auf dem Wandtablet tot (403 statt Abruf).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenAbrufErzwingen() throws Exception {
        // Kein TractiveController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
        mockMvc.perform(post("/v1/tractive/pets/refresh").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfFuttervorratLesen() throws Exception {
        mockMvc.perform(get("/v1/pet-food")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinenEinkaufBuchen() throws Exception {
        mockMvc.perform(post("/v1/pet-food/purchases").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cans\": 24}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineBestandskorrekturBuchen() throws Exception {
        mockMvc.perform(post("/v1/pet-food/corrections").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cansRemaining\": 10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfEinkaufBuchen() throws Exception {
        mockMvc.perform(post("/v1/pet-food/purchases").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cans\": 24}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfZielbestandAendern() throws Exception {
        mockMvc.perform(put("/v1/pet-food/target").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetCans\": 60}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfVapidPublicKeyLesen() throws Exception {
        when(vapidKeyService.publicKey()).thenReturn("key");
        mockMvc.perform(get("/v1/push/vapid-public-key")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinePushSubscriptionAnlegen() throws Exception {
        mockMvc.perform(post("/v1/push/subscriptions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\": \"https://x\", \"p256dh\": \"k\", \"auth\": \"a\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfPushSubscriptionAnlegen() throws Exception {
        when(currentUserService.requireUserId()).thenReturn(1L);
        mockMvc.perform(post("/v1/push/subscriptions").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\": \"https://x\", \"p256dh\": \"k\", \"auth\": \"a\"}"))
                .andExpect(status().isOk());
    }

    /**
     * /devices/kasa faellt auf die generische anyRequest -> MEMBER-Regel (kein eigener
     * Matcher in SecurityConfig). Bisher war kein /devices-Pfad ueberhaupt gepinnt.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinKasaGeraetManuellHinzufuegen() throws Exception {
        mockMvc.perform(post("/devices/kasa").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"192.168.1.116\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKasaGeraetManuellHinzufuegen() throws Exception {
        when(smartDeviceService.addKasaDeviceByIp("192.168.1.116")).thenReturn(
                SmartDeviceResponse.builder().id(1L).deviceType("KASA").build());
        mockMvc.perform(post("/devices/kasa").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"192.168.1.116\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * /devices/{id}/address faellt wie /devices/kasa auf die generische anyRequest ->
     * MEMBER-Regel (kein eigener Matcher in SecurityConfig).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfTapoAdresseNichtSetzen() throws Exception {
        mockMvc.perform(put("/devices/7/address").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"192.168.1.114\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfTapoAdresseSetzen() throws Exception {
        when(smartDeviceService.setTapoDeviceAddress(7L, "192.168.1.114")).thenReturn(
                SmartDeviceResponse.builder().id(7L).deviceType("TAPO").build());
        mockMvc.perform(put("/devices/7/address").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"192.168.1.114\"}"))
                .andExpect(status().isOk());
    }

    /**
     * /devices/{id}/light faellt wie /devices/kasa und /devices/{id}/address auf die
     * generische anyRequest -> MEMBER-Regel (kein eigener Matcher in SecurityConfig).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfLichtNichtSetzen() throws Exception {
        mockMvc.perform(put("/devices/7/light").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brightness\": 70}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfLichtSetzen() throws Exception {
        when(smartDeviceService.setLightState(eq(7L), any())).thenReturn(
                SmartDeviceResponse.builder().id(7L).deviceType("TAPO").build());
        mockMvc.perform(put("/devices/7/light").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brightness\": 70}"))
                .andExpect(status().isOk());
    }

    /**
     * Der Luftqualitaets-Serienendpunkt braucht bewusst keine eigene Regel: das GET faellt
     * auf die generische Regel GET /v1/** -> KIOSK. Ohne sie waere die Wandtablet-Ansicht
     * "Luftqualitaet" leer, und zwar ohne sichtbaren Fehler.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieLuftqualitaetsReihenLesen() throws Exception {
        mockMvc.perform(get("/v1/air-quality/series?range=WEEK")).andExpect(status().isNotFound());
    }

    /**
     * Die Verbrauchsreihe des Wandtablets braucht bewusst keine eigene Regel: das GET
     * faellt auf die generische Regel GET /v1/** -> KIOSK. 404 statt 403 belegt, dass
     * die Regel durchlaesst (der Controller steht nicht in diesem Slice).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieVerbrauchsreiheLesen() throws Exception {
        mockMvc.perform(get("/v1/meter-readings/series?range=WEEKS_26"))
                .andExpect(status().isNotFound());
    }

    /**
     * Schreiben bleibt MEMBER (anyRequest-Regel) - das Wandtablet darf Zaehlerstaende
     * lesen, aber keine erfassen.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineAblesungAnlegen() throws Exception {
        mockMvc.perform(post("/v1/meter-readings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meterType\":\"ELECTRICITY\",\"readingValue\":1,"
                                + "\"readingDate\":\"2026-08-21T00:00:00\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Der Netzwerkstatus braucht bewusst keine eigene Regel: das GET faellt auf die
     * generische Regel GET /v1/** -> KIOSK. Kein NetworkController im Slice: 404 statt
     * 403 belegt, dass die Regel durchlaesst.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenNetzwerkstatusLesen() throws Exception {
        mockMvc.perform(get("/v1/network/status")).andExpect(status().isNotFound());
    }

    /**
     * Der Speedtest zieht nur Daten, er schaltet nichts — ohne die KIOSK-Whitelist-Zeile
     * waere der Speedtest-Knopf auf dem Wandtablet tot.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenSpeedtestAusloesen() throws Exception {
        mockMvc.perform(post("/v1/network/speedtest").with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * Netzwerkgeraete pflegen ist ADMIN-only. KIOSK und MEMBER duerfen nicht anlegen —
     * beide Rollen muessen es aus eigenem Test belegen, sonst faellt ein zu laxer
     * Matcher fuer die jeweils andere Rolle niemandem auf.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinNetzwerkgeraetAnlegen() throws Exception {
        mockMvc.perform(post("/v1/network/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinNetzwerkgeraetAnlegen() throws Exception {
        mockMvc.perform(post("/v1/network/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnNetzwerkgeraetePflegenVorbei() throws Exception {
        // Kein NetworkController im Slice: 404 statt 403 belegt, dass die Regeln durchlaessen.
        mockMvc.perform(post("/v1/network/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/v1/network/devices/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/v1/network/devices/1").with(csrf()))
                .andExpect(status().isNotFound());
    }

}
