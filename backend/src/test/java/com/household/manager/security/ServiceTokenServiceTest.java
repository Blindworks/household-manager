package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.ServiceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenServiceTest {

    @Mock
    private ServiceTokenRepository repository;

    @InjectMocks
    private ServiceTokenService service;

    @Test
    void createLiefertKlartextGenauEinmalUndSpeichertNurDenHash() {
        when(repository.existsByName("tablet")).thenReturn(false);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceTokenService.CreatedToken created = service.create("tablet", UserRole.KIOSK);

        assertThat(created.plaintext()).startsWith("hm_").hasSizeGreaterThan(20);
        assertThat(created.token().getTokenHash())
                .isEqualTo(TokenHasher.sha256Hex(created.plaintext()))
                .isNotEqualTo(created.plaintext());
        assertThat(created.token().getRole()).isEqualTo(UserRole.KIOSK);
    }

    @Test
    void createMitVergebenemNamenWirftDuplicate() {
        when(repository.existsByName("tablet")).thenReturn(true);

        assertThatThrownBy(() -> service.create("tablet", UserRole.KIOSK))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void authenticateFindetTokenUeberHashUndAktualisiertLastUsed() {
        ServiceToken token = ServiceToken.builder().name("tablet")
                .tokenHash(TokenHasher.sha256Hex("hm_geheim")).role(UserRole.KIOSK).enabled(true).build();
        when(repository.findByTokenHashAndEnabledTrue(TokenHasher.sha256Hex("hm_geheim")))
                .thenReturn(Optional.of(token));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<ServiceToken> result = service.authenticate("hm_geheim");

        assertThat(result).isPresent();
        assertThat(result.get().getLastUsedAt()).isNotNull();
        verify(repository).save(token);
    }

    @Test
    void authenticateMitUnbekanntemTokenLiefertEmpty() {
        when(repository.findByTokenHashAndEnabledTrue(any())).thenReturn(Optional.empty());

        assertThat(service.authenticate("falsch")).isEmpty();
    }
}
