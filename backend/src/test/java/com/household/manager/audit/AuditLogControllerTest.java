package com.household.manager.audit;

import com.household.manager.audit.AuditLogController.AuditActionOption;
import com.household.manager.audit.AuditLogController.AuditEntryResponse;
import com.household.manager.audit.AuditLogController.AuditPageResponse;
import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reine Unit-Tests (kein MockMvc) — pruefen Mapping und Parameter-Durchreichung von {@link AuditLogController}. */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditService auditService;
    @Mock
    private SmartDeviceRepository deviceRepository;
    @Mock
    private FlowRepository flowRepository;
    @Mock
    private EntityStateRepository entityStateRepository;

    private AuditLogController controller() {
        return new AuditLogController(auditService,
                new AuditEntryDescriber(deviceRepository, flowRepository, entityStateRepository));
    }

    @Test
    void searchMapptSeiteMitLesbarenFeldernUndReichtFilterDurch() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 26, 10, 0);
        AuditLog entry = AuditLog.builder().id(1L).timestamp(timestamp).actorType(AuditActorType.USER)
                .actor("bene").action("auth.login").detail(null).build();
        when(auditService.search(any(), any(), eq(2), eq(25)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(2, 25), 51));
        LocalDateTime from = LocalDateTime.of(2026, 7, 1, 0, 0);

        AuditPageResponse result = controller().search(from, null, Set.of(AuditActorKind.PERSON),
                Set.of("auth.login"), "bene", 2, 25);

        assertThat(result.content()).containsExactly(new AuditEntryResponse(1L, timestamp, AuditActorType.USER,
                "bene", "auth.login", null, "bene", "Anmeldung", null));
        assertThat(result.totalElements()).isEqualTo(51);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        ArgumentCaptor<AuditLogFilter> filter = ArgumentCaptor.forClass(AuditLogFilter.class);
        verify(auditService).search(filter.capture(), any(), eq(2), eq(25));
        assertThat(filter.getValue()).isEqualTo(new AuditLogFilter(from, null, Set.of(AuditActorKind.PERSON),
                Set.of("auth.login"), "bene"));
    }

    @Test
    void searchKapptSeitengroesseAuf200UndSeiteAufNull() {
        when(auditService.search(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));

        AuditPageResponse result = controller().search(null, null, null, null, null, -3, 5000);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(200);
    }

    @Test
    void searchLehntVonNachBisAb() {
        LocalDateTime later = LocalDateTime.of(2026, 10, 2, 0, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 10, 1, 0, 0);

        assertThatThrownBy(() -> controller().search(later, earlier, null, null, null, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionsTragenBezeichnungUndSindNachBezeichnungSortiert() {
        when(auditService.actions()).thenReturn(List.of("switch.device", "auth.login", "something.new"));

        assertThat(controller().actions()).containsExactly(
                new AuditActionOption("auth.login", "Anmeldung"),
                new AuditActionOption("switch.device", "Gerät geschaltet"),
                new AuditActionOption("something.new", "something.new"));
    }
}
