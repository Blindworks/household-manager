package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.security.dto.UserAdminDtos.CreateUserRequest;
import com.household.manager.security.dto.UserAdminDtos.PasswordRequest;
import com.household.manager.security.dto.UserAdminDtos.UpdateUserRequest;
import com.household.manager.security.dto.UserAdminDtos.UserResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reine Unit-Tests (kein MockMvc) — pruefen Mapping und Audit-Verdrahtung von {@link UserAdminController}. */
@ExtendWith(MockitoExtension.class)
class UserAdminControllerTest {

    @Mock
    private AppUserService appUserService;

    @Mock
    private AuditService auditService;

    private UserAdminController controller() {
        return new UserAdminController(appUserService, auditService);
    }

    @Test
    void createLiefertGemapptesUserResponseUndAuditiertMitRolle() {
        AppUser saved = AppUser.builder().id(5L).username("mia").displayName("Mia")
                .passwordHash("hash").role(UserRole.MEMBER).enabled(true).build();
        when(appUserService.create("mia", "Mia", "geheim123", UserRole.MEMBER)).thenReturn(saved);

        UserResponse response = controller().create(new CreateUserRequest("mia", "Mia", "geheim123", UserRole.MEMBER));

        assertThat(response).isEqualTo(UserResponse.from(saved));
        verify(auditService).record("user.create", "mia (MEMBER)");
    }

    @Test
    void updateAuditiertMitPostUpdateZustand() {
        AppUser updated = AppUser.builder().id(7L).username("bene").displayName("Benedikt")
                .passwordHash("hash").role(UserRole.ADMIN).enabled(false).build();
        when(appUserService.update(7L, "Benedikt", UserRole.ADMIN, false)).thenReturn(updated);

        controller().update(7L, new UpdateUserRequest("Benedikt", UserRole.ADMIN, false));

        verify(auditService).record("user.update", "bene (ADMIN, deaktiviert)");
    }

    @Test
    void updateAuditiertAktivenZustandWennEnabled() {
        AppUser updated = AppUser.builder().id(8L).username("alex").displayName("Alex")
                .passwordHash("hash").role(UserRole.MEMBER).enabled(true).build();
        when(appUserService.update(8L, "Alex", UserRole.MEMBER, true)).thenReturn(updated);

        controller().update(8L, new UpdateUserRequest("Alex", UserRole.MEMBER, true));

        verify(auditService).record("user.update", "alex (MEMBER, aktiv)");
    }

    @Test
    void wennCreateWirftWirdNichtAuditiert() {
        when(appUserService.create(any(), any(), any(), any()))
                .thenThrow(new DuplicateEntityException("Benutzername bereits vergeben: mia"));

        assertThatThrownBy(() -> controller().create(new CreateUserRequest("mia", "Mia", "geheim123", UserRole.MEMBER)))
                .isInstanceOf(DuplicateEntityException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void wennUpdateWirftWirdNichtAuditiert() {
        when(appUserService.update(anyLong(), any(), any(), eq(false)))
                .thenThrow(new IllegalStateException("Der letzte aktive Admin kann nicht deaktiviert oder degradiert werden."));

        assertThatThrownBy(() -> controller().update(1L, new UpdateUserRequest("Admin", UserRole.ADMIN, false)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(auditService);
    }

    @Test
    void setPasswordAuditiertNutzerIdOhneDasPasswortZuLoggen() {
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);

        controller().setPassword(3L, new PasswordRequest("neuesGeheimnis1"));

        verify(appUserService).setPassword(3L, "neuesGeheimnis1");
        verify(auditService).record(eq("user.set-password"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).isEqualTo("Nutzer-Id 3");
        assertThat(detailCaptor.getValue()).doesNotContain("neuesGeheimnis1");
    }
}
