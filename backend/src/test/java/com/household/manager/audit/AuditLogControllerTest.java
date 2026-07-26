package com.household.manager.audit;

import com.household.manager.audit.AuditLogController.AuditEntryResponse;
import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reine Unit-Tests (kein MockMvc) — pruefen Mapping und Parameter-Durchreichung von {@link AuditLogController}. */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditService auditService;

    private AuditLogController controller() {
        return new AuditLogController(auditService);
    }

    @Test
    void recentMapptEntitiesAufResponseUndReichtLimitUndActorDurch() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 26, 10, 0);
        AuditLog entry = AuditLog.builder().id(1L).timestamp(timestamp).actorType(AuditActorType.USER)
                .actor("bene").action("auth.login").detail(null).build();
        when(auditService.recent(50, "bene")).thenReturn(List.of(entry));

        List<AuditEntryResponse> result = controller().recent(50, "bene");

        assertThat(result).containsExactly(
                new AuditEntryResponse(1L, timestamp, AuditActorType.USER, "bene", "auth.login", null));
        verify(auditService).recent(50, "bene");
    }

    @Test
    void recentOhneActorReichtNullDurch() {
        when(auditService.recent(100, null)).thenReturn(List.of());

        List<AuditEntryResponse> result = controller().recent(100, null);

        assertThat(result).isEmpty();
        verify(auditService).recent(100, null);
    }
}
