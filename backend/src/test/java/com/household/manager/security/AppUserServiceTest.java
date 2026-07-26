package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(repository, new BCryptPasswordEncoder());
        // lenient: nicht jeder Test erreicht den save()-Aufruf (Duplicate-/Guard-Faelle werfen vorher)
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createHashtDasPasswortMitBcrypt() {
        when(repository.existsByUsername("mia")).thenReturn(false);

        AppUser user = service.create("mia", "Mia", "geheim123", UserRole.MEMBER);

        assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo("geheim123");
        assertThat(new BCryptPasswordEncoder().matches("geheim123", user.getPasswordHash())).isTrue();
    }

    @Test
    void createMitVergebenemNamenWirftDuplicate() {
        when(repository.existsByUsername("mia")).thenReturn(true);

        assertThatThrownBy(() -> service.create("mia", "Mia", "x", UserRole.MEMBER))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void letzterAktiverAdminKannNichtDeaktiviertOderDegradiertWerden() {
        AppUser admin = AppUser.builder().id(1L).username("admin").displayName("Admin")
                .passwordHash("x").role(UserRole.ADMIN).enabled(true).build();
        when(repository.findById(1L)).thenReturn(Optional.of(admin));
        when(repository.findAll()).thenReturn(List.of(admin));

        assertThatThrownBy(() -> service.update(1L, "Admin", UserRole.MEMBER, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update(1L, "Admin", UserRole.ADMIN, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bootstrapLegtAdminMitChangeitUndPflichtwechselAn() {
        when(repository.count()).thenReturn(0L);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);

        boolean created = service.bootstrapAdmin();

        assertThat(created).isTrue();
        verify(repository).save(captor.capture());
        AppUser admin = captor.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("changeit", admin.getPasswordHash())).isTrue();
    }

    @Test
    void bootstrapTutNichtsWennNutzerExistieren() {
        when(repository.count()).thenReturn(3L);

        assertThat(service.bootstrapAdmin()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void changeOwnPasswordVerlangtDasAktuellePasswort() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        AppUser user = AppUser.builder().id(1L).username("admin").displayName("Admin")
                .passwordHash(encoder.encode("changeit")).role(UserRole.ADMIN)
                .mustChangePassword(true).build();
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changeOwnPassword("admin", "falsch", "neuesPasswort1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void changeOwnPasswordSetztNeuesPasswortUndLoeschtDasPflichtwechselFlag() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        AppUser user = AppUser.builder().id(1L).username("admin").displayName("Admin")
                .passwordHash(encoder.encode("changeit")).role(UserRole.ADMIN)
                .mustChangePassword(true).build();
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));

        service.changeOwnPassword("admin", "changeit", "neuesPasswort1");

        assertThat(user.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("neuesPasswort1", user.getPasswordHash())).isTrue();
        verify(repository).save(user);
    }
}
