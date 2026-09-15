package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDeviceManagementServiceTest {

    private static final ZigbeeBridgeResponse OK = new ZigbeeBridgeResponse("x", "t", true, null);

    @Mock private ZigbeeBridgeRequestService requestService;
    @Mock private AuditService auditService;

    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC));
    private ZigbeeDeviceManagementService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeDeviceManagementService(requestService, registry, auditService);
        registry.replaceDevices(List.of(new ZigbeeBridgeDevice("0x1", "Motion Büro", "EndDevice", "Battery",
                true, true, "SNZB-03", "SONOFF", "Motion", 1)));
    }

    @Test
    void anlernenNutztDasZweierFormat() {
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", false, null, true));
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.permitJoin(240);

        verify(requestService).send("permit_join", Map.of("time", 240));
        verify(auditService).record("zigbee.permit-join.open", "240 s");
    }

    @Test
    void anlernenNutztDasEinerFormatBeiAlterVersion() {
        registry.updateInfo(new ZigbeeBridgeInfo("1.42.0", false, null, true));
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.permitJoin(60);
        service.closePermitJoin();

        verify(requestService).send("permit_join", Map.of("value", true, "time", 60));
        verify(requestService).send("permit_join", Map.of("value", false, "time", 0));
        verify(auditService).record("zigbee.permit-join.close", "");
    }

    @Test
    void anlernenOhneInfoNutztDasZweierFormat() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.closePermitJoin();

        verify(requestService).send("permit_join", Map.of("time", 0));
    }

    @Test
    void anlerndauerWirdGeprueft() {
        assertThatThrownBy(() -> service.permitJoin(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.permitJoin(255)).isInstanceOf(IllegalArgumentException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void umbenennenSendetAltUndNeuUndAuditiert() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.rename("0x1", "  Bewegung Büro ");

        verify(requestService).send("device/rename", Map.of("from", "Motion Büro", "to", "Bewegung Büro"));
        verify(auditService).record("zigbee.device.rename", "Motion Büro -> Bewegung Büro");
    }

    @Test
    void umbenennenLehntUngueltigeNamenVorDemSendenAb() {
        assertThatThrownBy(() -> service.rename("0x1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a+b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a#b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void unbekannteIeeeAdresseIst404() {
        assertThatThrownBy(() -> service.interview("0xfehlt")).isInstanceOf(ResourceNotFoundException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void interviewUndKonfigurierenAdressierenUeberDieIeee() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.interview("0x1");
        service.configure("0x1");

        verify(requestService).send("device/interview", Map.of("id", "0x1"));
        verify(requestService).send("device/configure", Map.of("id", "0x1"));
        verify(auditService).record("zigbee.device.interview", "Motion Büro (0x1)");
        verify(auditService).record("zigbee.device.configure", "Motion Büro (0x1)");
    }

    @Test
    void entfernenMitForceFlag() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.remove("0x1", true);

        verify(requestService).send("device/remove", Map.of("id", "0x1", "force", true));
        verify(auditService).record("zigbee.device.remove", "Motion Büro (0x1), force=true");
    }

    @Test
    void fehlgeschlageneAktionErzeugtKeinenAuditEintrag() {
        when(requestService.send(anyString(), any())).thenThrow(new ZigbeeBridgeRejectedException("nope"));

        assertThatThrownBy(() -> service.remove("0x1", false)).isInstanceOf(ZigbeeBridgeRejectedException.class);

        verify(auditService, never()).record(anyString(), anyString());
    }
}
