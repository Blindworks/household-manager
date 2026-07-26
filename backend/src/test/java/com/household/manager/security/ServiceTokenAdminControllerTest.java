package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.security.dto.UserAdminDtos.CreateTokenRequest;
import com.household.manager.security.dto.UserAdminDtos.CreatedTokenResponse;
import com.household.manager.security.dto.UserAdminDtos.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reine Unit-Tests (kein MockMvc) — pruefen Mapping und Audit-Verdrahtung von {@link ServiceTokenAdminController}. */
@ExtendWith(MockitoExtension.class)
class ServiceTokenAdminControllerTest {

    @Mock
    private ServiceTokenService serviceTokenService;

    @Mock
    private AuditService auditService;

    private ServiceTokenAdminController controller() {
        return new ServiceTokenAdminController(serviceTokenService, auditService);
    }

    @Test
    void createLiefertKlartextAusCreatedTokenUndAuditiertOhneKlartext() {
        ServiceToken token = ServiceToken.builder().id(9L).name("tablet")
                .tokenHash("hash").role(UserRole.KIOSK).enabled(true).build();
        ServiceTokenService.CreatedToken created = new ServiceTokenService.CreatedToken(token, "hm_geheimerKlartext");
        when(serviceTokenService.create("tablet", UserRole.KIOSK)).thenReturn(created);

        CreatedTokenResponse response = controller().create(new CreateTokenRequest("tablet", UserRole.KIOSK));

        assertThat(response.token()).isEqualTo("hm_geheimerKlartext");
        assertThat(response.info()).isEqualTo(TokenResponse.from(token));

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("token.create"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).isEqualTo("tablet (KIOSK)");
        assertThat(detailCaptor.getValue()).doesNotContain("hm_geheimerKlartext");
    }

    @Test
    void revokeAuditiertMitTokenNamen() {
        ServiceToken revoked = ServiceToken.builder().id(9L).name("tablet")
                .tokenHash("hash").role(UserRole.KIOSK).enabled(false).build();
        when(serviceTokenService.revoke(9L)).thenReturn(revoked);

        controller().revoke(9L);

        verify(auditService).record("token.revoke", "tablet");
    }
}
