package com.household.manager.audit;

import com.household.manager.audit.AuditEntryDescriber.DescribedEntry;
import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEntryDescriberTest {

    @Mock
    private SmartDeviceRepository deviceRepository;
    @Mock
    private FlowRepository flowRepository;
    @Mock
    private EntityStateRepository entityStateRepository;

    private AuditEntryDescriber describer;

    @BeforeEach
    void setUp() {
        describer = new AuditEntryDescriber(deviceRepository, flowRepository, entityStateRepository);
        SmartDevice kamera = new SmartDevice();
        kamera.setId(29L);
        kamera.setDeviceName("Kamera");
        lenient().when(deviceRepository.findAll()).thenReturn(List.of(kamera));
        Flow nacht = new Flow();
        nacht.setId(13L);
        nacht.setName("Nachtmodus: Geräte aus");
        lenient().when(flowRepository.findAll()).thenReturn(List.of(nacht));
        lenient().when(entityStateRepository.findByEntityId(anyString())).thenReturn(Optional.empty());
    }

    private static AuditLog entry(AuditActorType type, String actor, String action, String detail) {
        return AuditLog.builder().id(1L).timestamp(LocalDateTime.of(2026, 10, 23, 7, 3))
                .actorType(type).actor(actor).action(action).detail(detail).build();
    }

    private DescribedEntry describeOne(AuditLog log) {
        return describer.describe(List.of(log)).get(0);
    }

    @Test
    void flowSwitchesDevice_showsFlowNameDeviceNameAndGermanState() {
        DescribedEntry result = describeOne(entry(AuditActorType.SYSTEM, "FLOW:13", "switch.device", "29 -> off"));

        assertThat(result.actorLabel()).isEqualTo("Flow „Nachtmodus: Geräte aus“");
        assertThat(result.actionLabel()).isEqualTo("Gerät geschaltet");
        assertThat(result.detailLabel()).isEqualTo("Kamera → aus");
    }

    @Test
    void unknownDeviceAndFlow_fallBackToIds() {
        DescribedEntry result = describeOne(entry(AuditActorType.SYSTEM, "FLOW:99", "switch.device", "77 -> on"));

        assertThat(result.actorLabel()).isEqualTo("Flow 99 (gelöscht)");
        assertThat(result.detailLabel()).isEqualTo("Gerät 77 → an");
    }

    @Test
    void entityToggle_usesFriendlyName() {
        EntityState helper = new EntityState();
        helper.setEntityId("input_boolean.manual_nachtmodus");
        helper.setFriendlyName("Nachtmodus");
        when(entityStateRepository.findByEntityId("input_boolean.manual_nachtmodus")).thenReturn(Optional.of(helper));

        DescribedEntry result = describeOne(entry(AuditActorType.USER, "admin", "helper.set",
                "input_boolean.manual_nachtmodus -> on"));

        assertThat(result.actorLabel()).isEqualTo("admin");
        assertThat(result.detailLabel()).isEqualTo("Nachtmodus → an");
    }

    @Test
    void flowDeploy_showsFlowName() {
        DescribedEntry result = describeOne(entry(AuditActorType.USER, "admin", "flow.deploy", "Flow 13"));

        assertThat(result.actionLabel()).isEqualTo("Flow deployt");
        assertThat(result.detailLabel()).isEqualTo("„Nachtmodus: Geräte aus“");
    }

    @Test
    void systemActors_getReadableLabels() {
        assertThat(describeOne(entry(AuditActorType.SYSTEM, "AGENT", "agent.action.execute", "x")).actorLabel())
                .isEqualTo("Hausverstand");
        assertThat(describeOne(entry(AuditActorType.TELEGRAM, "TELEGRAM:4711", "switch.toggle", "x")).actorLabel())
                .isEqualTo("Telegram (Chat 4711)");
        assertThat(describeOne(entry(AuditActorType.SYSTEM, "system", "x.y", null)).actorLabel())
                .isEqualTo("System");
    }

    @Test
    void unknownAction_keepsRawActionAndDetail() {
        DescribedEntry result = describeOne(entry(AuditActorType.USER, "admin", "something.new", "irgendwas"));

        assertThat(result.actionLabel()).isEqualTo("something.new");
        assertThat(result.detailLabel()).isEqualTo("irgendwas");
    }

    @Test
    void lookupFailure_neverThrows_andKeepsRawValues() {
        when(deviceRepository.findAll()).thenThrow(new RuntimeException("DB weg"));
        when(flowRepository.findAll()).thenThrow(new RuntimeException("DB weg"));

        DescribedEntry result = describeOne(entry(AuditActorType.SYSTEM, "FLOW:13", "switch.device", "29 -> off"));

        assertThat(result.actorLabel()).isEqualTo("Flow 13 (gelöscht)");
        assertThat(result.detailLabel()).isEqualTo("Gerät 29 → aus");
    }

    @Test
    void namesAreLoadedOncePerCall() {
        describer.describe(List.of(
                entry(AuditActorType.SYSTEM, "FLOW:13", "switch.device", "29 -> off"),
                entry(AuditActorType.SYSTEM, "FLOW:13", "switch.device", "29 -> on")));

        verify(deviceRepository, times(1)).findAll();
        verify(flowRepository, times(1)).findAll();
    }
}
