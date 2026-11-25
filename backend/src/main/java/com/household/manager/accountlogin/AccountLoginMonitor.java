package com.household.manager.accountlogin;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spiegelt die Anmeldungen bei externen Diensten als
 * {@code binary_sensor.account_login_<dienst>} ({@code on} = angemeldet) und meldet
 * abgelaufene Anmeldungen auf der Job-Tafel. Anlass: Tractive gibt kein
 * Refresh-Token aus, ein abgelaufenes Token ließ die Hunde-Entität still einfrieren.
 * <p>
 * Regeln: ein Dienst, der nie angemeldet war, bekommt keine Entität (kein Dauer-Alarm
 * für ungenutzte Integrationen) — außer Tractive mit vorhandenem, abgelaufenem Token.
 * Ein abgeschalteter oder entkoppelter Dienst ({@link AccountLoginState#NOT_SET_UP})
 * verliert seine Entität. Ist der Zustand nicht feststellbar, bleibt alles stehen.
 * Die Push-Nachricht ist ein Flow auf {@code off} (Wortlaut ohne Redeploy änderbar).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountLoginMonitor {

    static final String ATTR_STATUS = "status";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AccountLoginChecker checker;
    private final EntityStateService entityStateService;
    private final SystemJobMonitor jobMonitor;

    @Scheduled(fixedDelayString = "${account-login.check-interval-ms:120000}", initialDelayString = "90000")
    public void check() {
        List<String> expired = new ArrayList<>();
        for (AccountService service : AccountService.values()) {
            checker.check(service).ifPresent(status -> {
                if (apply(status)) {
                    expired.add(service.label());
                }
            });
        }
        if (!expired.isEmpty()) {
            jobMonitor.reportFailure("Nicht angemeldet: " + String.join(", ", expired));
        }
    }

    /** @return ob der Dienst als abgelaufen gemeldet wurde */
    private boolean apply(AccountLoginStatus status) {
        String entityId = entityId(status.service());
        boolean exists = entityStateService.getByEntityId(entityId).isPresent();
        switch (status.state()) {
            case NOT_SET_UP -> {
                if (exists) {
                    entityStateService.deleteByEntityId(entityId);
                }
                return false;
            }
            case EXPIRED -> {
                if (!exists && status.expiresAt() == null) {
                    // Nie angemeldet gewesen: keine Warnung.
                    return false;
                }
                report(status, "off");
                return true;
            }
            default -> {
                report(status, "on");
                return false;
            }
        }
    }

    private void report(AccountLoginStatus status, String state) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("service", status.service().label());
        attributes.put(ATTR_STATUS, status.state().name().toLowerCase(Locale.ROOT));
        Optional.ofNullable(status.expiresAt())
                .ifPresent(expiresAt -> attributes.put("expiresAt", TIMESTAMP.format(expiresAt)));
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId(status.service()))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.ACCOUNT_LOGIN)
                .sourceRef(status.service().slug())
                .friendlyName(status.service().label() + " angemeldet")
                .state(state)
                .attributes(attributes)
                .build());
    }

    /** Einzige Definition der Entity-Id je Dienst. */
    public static String entityId(AccountService service) {
        return EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.ACCOUNT_LOGIN, service.slug(), null);
    }
}
