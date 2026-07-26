package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiActionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NukiControllerKioskRuleTest {

    @Mock
    private NukiLockService lockService;

    private Authentication authWithRoles(String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(
                "wer", null, AuthorityUtils.createAuthorityList(roles));
    }

    @Test
    void kioskDarfVerriegeln() {
        NukiController controller = new NukiController(lockService);

        assertThatCode(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.LOCK), authWithRoles("ROLE_KIOSK")))
                .doesNotThrowAnyException();
        verify(lockService).executeAction(1L, NukiLockAction.LOCK);
    }

    @Test
    void kioskDarfNichtEntsperren() {
        NukiController controller = new NukiController(lockService);

        assertThatThrownBy(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.UNLATCH), authWithRoles("ROLE_KIOSK")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(lockService);
    }

    @Test
    void memberDarfEntsperren() {
        NukiController controller = new NukiController(lockService);

        assertThatCode(() -> controller.executeAction(1L,
                new NukiActionRequest(NukiLockAction.UNLOCK), authWithRoles("ROLE_MEMBER")))
                .doesNotThrowAnyException();
        verify(lockService).executeAction(1L, NukiLockAction.UNLOCK);
    }
}
