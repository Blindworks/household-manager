package com.household.manager.accountlogin;

import java.time.LocalDateTime;

/**
 * Ergebnis einer Anmeldeprüfung.
 *
 * @param expiresAt nur bei Tractive bekannt, sonst null
 */
public record AccountLoginStatus(AccountService service, AccountLoginState state, LocalDateTime expiresAt) {

    public static AccountLoginStatus of(AccountService service, AccountLoginState state) {
        return new AccountLoginStatus(service, state, null);
    }
}
