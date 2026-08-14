package com.household.manager.system;

import com.household.manager.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Startet das System (alle Compose-Container) ueber den Rebooter-Sidecar neu.
 * Der Audit-Eintrag steht bewusst VOR dem Sidecar-Aufruf: nach dem Neustart
 * waere der Request-Kontext (und damit der Aktor) weg.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemRebootService {

    private final RebooterProperties properties;
    private final RebooterClient rebooterClient;
    private final AuditService auditService;

    public void reboot() {
        if (isBlank(properties.getBaseUrl()) || isBlank(properties.getToken())) {
            throw new IllegalStateException(
                    "Reboot ist nicht konfiguriert (REBOOTER_URL/REBOOTER_TOKEN fehlen).");
        }
        auditService.record("system.reboot", "alle Container");
        log.info("System-Neustart angefordert");
        rebooterClient.triggerReboot();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
