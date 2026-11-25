package com.household.manager.accountlogin;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.EntityState;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountLoginMonitorTest {

    private static final String TRACTIVE_ID = "binary_sensor.account_login_tractive";
    private static final String BLINK_ID = "binary_sensor.account_login_blink";

    @Mock
    private AccountLoginChecker checker;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private SystemJobMonitor jobMonitor;

    private AccountLoginMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new AccountLoginMonitor(checker, entityStateService, jobMonitor);
        lenient().when(checker.check(any())).thenReturn(Optional.empty());
        lenient().when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());
    }

    private void status(AccountService service, AccountLoginState state, LocalDateTime expiresAt) {
        when(checker.check(service)).thenReturn(Optional.of(new AccountLoginStatus(service, state, expiresAt)));
    }

    private void exists(String entityId) {
        when(entityStateService.getByEntityId(entityId)).thenReturn(Optional.of(EntityState.builder().build()));
    }

    private EntityStateUpdate reported() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        return captor.getValue();
    }

    @Test
    void entityIdIstStabil() {
        assertThat(AccountLoginMonitor.entityId(AccountService.TRACTIVE)).isEqualTo(TRACTIVE_ID);
    }

    @Test
    void angemeldeterDienstMeldetOn() {
        status(AccountService.TRACTIVE, AccountLoginState.OK, LocalDateTime.of(2026, 12, 20, 10, 0));

        monitor.check();

        EntityStateUpdate update = reported();
        assertThat(update.entityId()).isEqualTo(TRACTIVE_ID);
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.attributes()).containsEntry("status", "ok").containsEntry("expiresAt", "2026-12-20T10:00:00");
        verify(jobMonitor, never()).reportFailure(anyString());
    }

    @Test
    void baldAblaufendeAnmeldungBleibtOnMitStatus() {
        status(AccountService.TRACTIVE, AccountLoginState.EXPIRING, LocalDateTime.of(2026, 11, 26, 10, 0));

        monitor.check();

        assertThat(reported().attributes()).containsEntry("status", "expiring");
    }

    @Test
    void abgelaufeneAnmeldungMeldetOffUndStoerung() {
        exists(BLINK_ID);
        status(AccountService.BLINK, AccountLoginState.EXPIRED, null);

        monitor.check();

        assertThat(reported().state()).isEqualTo("off");
        verify(jobMonitor).reportFailure("Nicht angemeldet: Blink");
    }

    @Test
    void nieAngemeldeterDienstBekommtKeineEntitaet() {
        status(AccountService.ALEXA, AccountLoginState.EXPIRED, null);

        monitor.check();

        verify(entityStateService, never()).reportState(any());
        verify(jobMonitor, never()).reportFailure(anyString());
    }

    @Test
    void abgelaufenesTractiveTokenWarntAuchOhneBestehendeEntitaet() {
        status(AccountService.TRACTIVE, AccountLoginState.EXPIRED, LocalDateTime.of(2026, 11, 20, 10, 0));

        monitor.check();

        assertThat(reported().state()).isEqualTo("off");
    }

    @Test
    void abgeschalteterDienstVerliertSeineEntitaet() {
        exists("binary_sensor.account_login_dirigera");
        status(AccountService.DIRIGERA, AccountLoginState.NOT_SET_UP, null);

        monitor.check();

        verify(entityStateService).deleteByEntityId("binary_sensor.account_login_dirigera");
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void nichtFeststellbarerZustandAendertNichts() {
        monitor.check();

        verify(entityStateService, never()).reportState(any());
        verify(entityStateService, never()).deleteByEntityId(anyString());
    }
}
