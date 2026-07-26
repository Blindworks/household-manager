package com.household.manager.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Legt beim allerersten Start den Admin an (INITIAL_ADMIN_PASSWORD oder Zufall). */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserInitializer implements ApplicationRunner {

    private final AppUserService appUserService;

    @Value("${auth.initial-admin-password:}")
    private String initialPassword;

    @Override
    public void run(ApplicationArguments args) {
        appUserService.bootstrapAdmin(initialPassword).ifPresent(generated ->
                log.warn("Initialer Admin 'admin' angelegt. Einmal-Passwort: {} — bitte sofort aendern!",
                        generated));
    }
}
