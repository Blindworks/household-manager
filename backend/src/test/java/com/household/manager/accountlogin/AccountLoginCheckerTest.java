package com.household.manager.accountlogin;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.dirigera.DirigeraHubSettings;
import com.household.manager.dirigera.DirigeraPollingService;
import com.household.manager.dirigera.DirigeraSettingsService;
import com.household.manager.tractive.TractiveAuthService;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.vision.VisionProperties;
import com.household.manager.vision.VisionSidecarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountLoginCheckerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T11:00:00Z"), ZoneId.of("Europe/Berlin"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 12, 0);

    @Mock
    private TractiveAuthService tractiveAuthService;
    @Mock
    private VisionSidecarClient visionSidecarClient;
    @Mock
    private AlexaSidecarClient alexaSidecarClient;
    @Mock
    private DirigeraSettingsService dirigeraSettingsService;
    @Mock
    private DirigeraPollingService dirigeraPollingService;

    private VisionProperties visionProperties;
    private AccountLoginChecker checker;

    @BeforeEach
    void setUp() {
        visionProperties = new VisionProperties();
        checker = new AccountLoginChecker(tractiveAuthService, visionSidecarClient, visionProperties,
                alexaSidecarClient, dirigeraSettingsService, dirigeraPollingService, CLOCK);
    }

    private AccountLoginState stateOf(AccountService service) {
        return checker.check(service).orElseThrow().state();
    }

    @Test
    void tractiveMitLangemTokenIstOk() {
        when(tractiveAuthService.status()).thenReturn(new TractiveAuthStatusDto(true, "a@b", NOW.plusDays(10)));
        assertThat(stateOf(AccountService.TRACTIVE)).isEqualTo(AccountLoginState.OK);
    }

    @Test
    void tractiveKurzVorAblaufLaeuftBaldAb() {
        when(tractiveAuthService.status()).thenReturn(new TractiveAuthStatusDto(true, "a@b", NOW.plusDays(2)));
        assertThat(stateOf(AccountService.TRACTIVE)).isEqualTo(AccountLoginState.EXPIRING);
    }

    @Test
    void tractiveMitUnbrauchbaremTokenIstAbgelaufenUndTraegtDasDatum() {
        when(tractiveAuthService.status()).thenReturn(new TractiveAuthStatusDto(false, "a@b", NOW.minusHours(1)));
        AccountLoginStatus status = checker.check(AccountService.TRACTIVE).orElseThrow();
        assertThat(status.state()).isEqualTo(AccountLoginState.EXPIRED);
        assertThat(status.expiresAt()).isEqualTo(NOW.minusHours(1));
    }

    @Test
    void tractiveOhneTokenIstAbgelaufenOhneDatum() {
        when(tractiveAuthService.status()).thenReturn(new TractiveAuthStatusDto(false, null, null));
        AccountLoginStatus status = checker.check(AccountService.TRACTIVE).orElseThrow();
        assertThat(status.state()).isEqualTo(AccountLoginState.EXPIRED);
        assertThat(status.expiresAt()).isNull();
    }

    @Test
    void blinkAbgemeldetIstAbgelaufen() {
        when(visionSidecarClient.getStatus()).thenReturn(new VisionSidecarClient.SidecarStatus(false, false, null, null));
        assertThat(stateOf(AccountService.BLINK)).isEqualTo(AccountLoginState.EXPIRED);
    }

    @Test
    void blinkAbgeschaltetIstNichtEingerichtet() {
        visionProperties.setEnabled(false);
        assertThat(stateOf(AccountService.BLINK)).isEqualTo(AccountLoginState.NOT_SET_UP);
    }

    @Test
    void nichtErreichbarerSidecarLiefertKeinUrteil() {
        when(alexaSidecarClient.getStatus()).thenThrow(new RuntimeException("Connection refused"));
        assertThat(checker.check(AccountService.ALEXA)).isEmpty();
    }

    @Test
    void alexaAngemeldetIstOk() {
        when(alexaSidecarClient.getStatus()).thenReturn(new AlexaSidecarClient.SidecarStatus(true, "Konto"));
        assertThat(stateOf(AccountService.ALEXA)).isEqualTo(AccountLoginState.OK);
    }

    @Test
    void dirigeraNichtGekoppeltIstNichtEingerichtet() {
        when(dirigeraSettingsService.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", null, null, null));
        assertThat(stateOf(AccountService.DIRIGERA)).isEqualTo(AccountLoginState.NOT_SET_UP);
    }

    @Test
    void dirigeraMitAbgelehntemTokenIstAbgelaufen() {
        when(dirigeraSettingsService.get()).thenReturn(new DirigeraHubSettings("192.168.1.159", "token", "sha", "hub"));
        when(dirigeraPollingService.needsRepairing()).thenReturn(true);
        assertThat(stateOf(AccountService.DIRIGERA)).isEqualTo(AccountLoginState.EXPIRED);
    }
}
