package com.household.manager.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Legt beim allerersten Start den Admin "admin" mit Passwort "changeit" an. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserInitializer implements ApplicationRunner {

    private final AppUserService appUserService;

    @Override
    public void run(ApplicationArguments args) {
        if (appUserService.bootstrapAdmin()) {
            log.warn("Initialer Admin 'admin' mit Passwort '{}' angelegt — "
                    + "der Wechsel wird beim ersten Login erzwungen.", AppUserService.BOOTSTRAP_PASSWORD);
        }
    }
}
