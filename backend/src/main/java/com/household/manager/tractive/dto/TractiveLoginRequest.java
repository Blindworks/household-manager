package com.household.manager.tractive.dto;

import jakarta.validation.constraints.NotBlank;

/** Login-Anfrage. Das Passwort wird nur weitergereicht, nie gespeichert oder geloggt. */
public record TractiveLoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
    @Override
    public String toString() {
        return "TractiveLoginRequest[email=" + email + ", password=***]";
    }
}
