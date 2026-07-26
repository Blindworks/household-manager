package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import com.household.manager.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository repository;

    @Test
    void schreibtEintragMitAufgeloestemAktor() {
        AuditService service = new AuditService(repository, new AuditActorResolver());

        service.record("switch.toggle", "switch.meross_abc");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(captor.getValue().getAction()).isEqualTo("switch.toggle");
        assertThat(captor.getValue().getDetail()).isEqualTo("switch.meross_abc");
    }

    @Test
    void auditFehlerBrechenDieFachlicheAktionNicht() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));
        AuditService service = new AuditService(repository, new AuditActorResolver());

        assertThatCode(() -> service.record("nuki.lock", "123")).doesNotThrowAnyException();
    }

    @Test
    void expliziterAktorWirdUebernommen() {
        AuditService service = new AuditService(repository, new AuditActorResolver());

        service.record(AuditActor.user("bene"), "auth.login", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo(AuditActorType.USER);
        assertThat(captor.getValue().getActor()).isEqualTo("bene");
    }
}
