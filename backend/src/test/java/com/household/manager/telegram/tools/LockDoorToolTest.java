package com.household.manager.telegram.tools;

import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LockDoorToolTest {

    @Mock
    private NukiLockService nukiLockService;

    private LockDoorTool tool() {
        return new LockDoorTool(nukiLockService);
    }

    private NukiLockResponse lock(long id) {
        return new NukiLockResponse(id, "Haustuer", "unlocked", "closed", 80, false);
    }

    @Test
    void locksTheOnlyLockWithoutExplicitId() throws Exception {
        when(nukiLockService.listLocks()).thenReturn(List.of(lock(17L)));

        tool().execute(Map.of());

        verify(nukiLockService).executeAction(17L, NukiLockAction.LOCK);
    }

    @Test
    void locksExplicitSmartlockId() throws Exception {
        tool().execute(Map.of("smartlockId", "17"));

        verify(nukiLockService).executeAction(17L, NukiLockAction.LOCK);
    }

    @Test
    void neverCallsAnyOtherActionThanLock() throws Exception {
        tool().execute(Map.of("smartlockId", "17"));

        verify(nukiLockService).executeAction(anyLong(), eq(NukiLockAction.LOCK));
        verify(nukiLockService, never()).executeAction(anyLong(), eq(NukiLockAction.UNLOCK));
        verify(nukiLockService, never()).executeAction(anyLong(), eq(NukiLockAction.UNLATCH));
    }

    @Test
    void ambiguousWithoutIdWhenMultipleLocks() {
        when(nukiLockService.listLocks()).thenReturn(List.of(lock(1L), lock(2L)));

        assertThrows(IllegalArgumentException.class, () -> tool().execute(Map.of()));
        verify(nukiLockService, never()).executeAction(anyLong(), any());
    }
}
