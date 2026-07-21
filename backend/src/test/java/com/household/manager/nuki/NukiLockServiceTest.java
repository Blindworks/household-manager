package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import com.household.manager.nuki.dto.NukiSmartlockStateDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NukiLockServiceTest {

    @Mock
    private NukiApiClient apiClient;
    @Mock
    private NukiPollingService pollingService;
    @InjectMocks
    private NukiLockService service;

    @Test
    void listLocksMapsToResponse() {
        when(apiClient.listSmartlocks()).thenReturn(List.of(
                new NukiSmartlockDto(17958143231L, "Haustür",
                        new NukiSmartlockStateDto(1, 2, false, 85))));

        List<NukiLockResponse> locks = service.listLocks();

        assertEquals(1, locks.size());
        NukiLockResponse lock = locks.get(0);
        assertEquals(17958143231L, lock.smartlockId());
        assertEquals("Haustür", lock.name());
        assertEquals("locked", lock.state());
        assertEquals("off", lock.doorState());
        assertEquals(85, lock.batteryCharge());
        assertFalse(lock.batteryCritical());
    }

    @Test
    void listLocksHandlesMissingState() {
        when(apiClient.listSmartlocks()).thenReturn(List.of(
                new NukiSmartlockDto(1L, "Kaputt", null)));

        NukiLockResponse lock = service.listLocks().get(0);
        assertEquals("unknown", lock.state());
        assertNull(lock.doorState());
        assertNull(lock.batteryCharge());
        assertFalse(lock.batteryCritical());
    }

    @Test
    void executeActionSendsCodeAndRefreshesState() {
        service.executeAction(42L, NukiLockAction.LOCK);

        InOrder inOrder = inOrder(apiClient, pollingService);
        inOrder.verify(apiClient).sendAction(42L, 2);
        inOrder.verify(pollingService).poll();
    }

    @Test
    void executeActionSkipsPollWhenSendFails() {
        doThrow(new NukiException("down", null)).when(apiClient).sendAction(42L, 2);

        assertThrows(NukiException.class, () -> service.executeAction(42L, NukiLockAction.LOCK));

        verifyNoInteractions(pollingService);
    }

    @Test
    void actionCodesMatchNukiApi() {
        assertEquals(1, NukiLockAction.UNLOCK.getApiCode());
        assertEquals(2, NukiLockAction.LOCK.getApiCode());
        assertEquals(3, NukiLockAction.UNLATCH.getApiCode());
    }
}
