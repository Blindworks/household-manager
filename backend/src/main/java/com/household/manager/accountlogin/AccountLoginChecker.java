package com.household.manager.accountlogin;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.dirigera.DirigeraPollingService;
import com.household.manager.dirigera.DirigeraSettingsService;
import com.household.manager.tractive.TractiveAuthService;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.vision.VisionProperties;
import com.household.manager.vision.VisionSidecarClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Einzige Definition von „Anmeldung bei Dienst X ist abgelaufen“. Jede Prüfung ist
 * isoliert und wirft nie: ist der Zustand nicht feststellbar (Sidecar nicht
 * erreichbar), liefert sie leer — geraten wird nicht.
 * <p>
 * Tractive kennt kein Refresh-Token, das Ablaufdatum steht in {@code tractive_auth};
 * ab {@link #EXPIRING_WITHIN} davor gilt die Anmeldung als „läuft bald ab“. Blink
 * und Alexa melden nur angemeldet ja/nein (ihr Sidecar), Dirigera gilt als abgelaufen,
 * wenn der Hub den Token ablehnt oder das Zertifikat sich geändert hat.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountLoginChecker {

    static final Duration EXPIRING_WITHIN = Duration.ofDays(3);

    private final TractiveAuthService tractiveAuthService;
    private final VisionSidecarClient visionSidecarClient;
    private final VisionProperties visionProperties;
    private final AlexaSidecarClient alexaSidecarClient;
    private final DirigeraSettingsService dirigeraSettingsService;
    private final DirigeraPollingService dirigeraPollingService;
    private final Clock clock;

    public Optional<AccountLoginStatus> check(AccountService service) {
        try {
            return Optional.of(switch (service) {
                case TRACTIVE -> checkTractive();
                case BLINK -> checkBlink();
                case ALEXA -> AccountLoginStatus.of(service,
                        alexaSidecarClient.getStatus().loggedIn() ? AccountLoginState.OK : AccountLoginState.EXPIRED);
                case DIRIGERA -> checkDirigera();
            });
        } catch (Exception ex) {
            log.debug("Anmeldestatus {} nicht feststellbar: {}", service, ex.getMessage());
            return Optional.empty();
        }
    }

    private AccountLoginStatus checkTractive() {
        TractiveAuthStatusDto status = tractiveAuthService.status();
        if (status.expiresAt() == null) {
            // Keine Token-Zeile: nie angemeldet oder abgemeldet.
            return AccountLoginStatus.of(AccountService.TRACTIVE, AccountLoginState.EXPIRED);
        }
        if (!status.authenticated()) {
            return new AccountLoginStatus(AccountService.TRACTIVE, AccountLoginState.EXPIRED, status.expiresAt());
        }
        boolean soon = status.expiresAt().isBefore(LocalDateTime.now(clock).plus(EXPIRING_WITHIN));
        return new AccountLoginStatus(AccountService.TRACTIVE,
                soon ? AccountLoginState.EXPIRING : AccountLoginState.OK, status.expiresAt());
    }

    private AccountLoginStatus checkBlink() {
        if (!visionProperties.isEnabled()) {
            return AccountLoginStatus.of(AccountService.BLINK, AccountLoginState.NOT_SET_UP);
        }
        return AccountLoginStatus.of(AccountService.BLINK,
                visionSidecarClient.getStatus().loggedIn() ? AccountLoginState.OK : AccountLoginState.EXPIRED);
    }

    private AccountLoginStatus checkDirigera() {
        if (!dirigeraSettingsService.get().isPaired()) {
            return AccountLoginStatus.of(AccountService.DIRIGERA, AccountLoginState.NOT_SET_UP);
        }
        return AccountLoginStatus.of(AccountService.DIRIGERA,
                dirigeraPollingService.needsRepairing() ? AccountLoginState.EXPIRED : AccountLoginState.OK);
    }
}
