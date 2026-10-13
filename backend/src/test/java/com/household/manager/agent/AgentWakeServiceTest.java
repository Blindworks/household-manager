package com.household.manager.agent;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.exception.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWakeServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** 16:05 UTC = 18:05 Berliner Sommerzeit. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T16:05:00Z"), BERLIN);

    private final AgentProperties properties = new AgentProperties();
    private final AgentSettingsService settings = mock(AgentSettingsService.class);
    private final AgentRunService runService = mock(AgentRunService.class);
    private final AgentTokenBudget tokenBudget = mock(AgentTokenBudget.class);
    private final ManualScheduler debounce = new ManualScheduler();
    private ExecutorService executor = new DirectExecutorService();
    private AgentWakeService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setWakeEntities(List.of("binary_sensor.presence_household", "sensor.sun"));
        properties.setWakeDebounceMs(180_000);
        properties.setManualRunMinIntervalMs(60_000);
        when(settings.isActive()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        debounce.shutdownNow();
        executor.shutdownNow();
    }

    private AgentWakeService service() {
        if (service == null) {
            service = new AgentWakeService(properties, settings, runService, executor, debounce, tokenBudget, CLOCK);
        }
        return service;
    }

    private static EntityStateChangedEvent event(String entityId) {
        return new EntityStateChangedEvent(entityId, "off", "on", Map.of(), LocalDateTime.now(CLOCK));
    }

    @Test
    void inactiveSwitchDropsWake() {
        when(settings.isActive()).thenReturn(false);

        service().onEntityStateChanged(event("binary_sensor.presence_household"));
        debounce.fire();

        verifyNoInteractions(runService);
    }

    @Test
    void disabledPropertyDropsWakeWithoutScheduling() {
        properties.setEnabled(false);

        service().onEntityStateChanged(event("binary_sensor.presence_household"));

        assertFalse(debounce.isScheduled());
        verifyNoInteractions(runService);
    }

    @Test
    void unlistedEntityIsIgnored() {
        service().onEntityStateChanged(event("sensor.irgendwas"));

        assertFalse(debounce.isScheduled());
        verifyNoInteractions(runService);
    }

    @Test
    void exhaustedBudgetDropsWake() {
        when(tokenBudget.isExhausted()).thenReturn(true);

        service().onEntityStateChanged(event("binary_sensor.presence_household"));
        debounce.fire();

        verifyNoInteractions(runService);
    }

    @Test
    void runNowRefusedWhenBudgetExhausted() {
        when(tokenBudget.isExhausted()).thenReturn(true);

        assertThrows(TooManyRequestsException.class, () -> service().runNow());
        verifyNoInteractions(runService);

        when(tokenBudget.isExhausted()).thenReturn(false);
        service().runNow();

        verify(runService).run(new AgentTrigger(AgentTriggerType.MANUAL, List.of()));
    }

    @Test
    void listedEntityStartsDebouncedRunWithReason() {
        service().onEntityStateChanged(event("binary_sensor.presence_household"));

        assertTrue(debounce.isScheduled());
        assertEquals(180_000, debounce.lastDelayMs());
        verifyNoInteractions(runService);

        debounce.fire();

        verify(runService).run(argThat(t -> t.type() == AgentTriggerType.WAKE
                && t.details().equals(List.of("binary_sensor.presence_household: off → on um 18:05"))));
    }

    @Test
    void twoWakesWithinDebounceBecomeOneRun() {
        service().onEntityStateChanged(event("binary_sensor.presence_household"));
        service().onEntityStateChanged(event("sensor.sun"));

        assertEquals(1, debounce.scheduleCount());
        debounce.fire();

        verify(runService, times(1)).run(argThat(t -> t.type() == AgentTriggerType.WAKE
                && t.details().size() == 2
                && t.details().get(1).startsWith("sensor.sun")));
    }

    @Test
    void wakeAfterFiredWindowStartsNewWindow() {
        service().onEntityStateChanged(event("binary_sensor.presence_household"));
        debounce.fire();
        service().onEntityStateChanged(event("sensor.sun"));

        assertTrue(debounce.isScheduled());
        assertEquals(2, debounce.scheduleCount());
    }

    @Test
    void tickSubmitsScheduledRun() {
        service().tick();

        verify(runService).run(new AgentTrigger(AgentTriggerType.SCHEDULED, List.of()));
    }

    @Test
    void tickWithInactiveSwitchDoesNothing() {
        when(settings.isActive()).thenReturn(false);

        service().tick();

        verifyNoInteractions(runService);
    }

    @Test
    void tickNeverThrowsWhenSettingsFail() {
        when(settings.isActive()).thenThrow(new RuntimeException("db weg"));

        service().tick();

        verifyNoInteractions(runService);
    }

    @Test
    void wakeDuringRunCausesExactlyOneFollowUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch twoRuns = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(runService.run(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            twoRuns.countDown();
            return null;
        });

        service().tick();
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        // Zwei getrennte Weckfenster feuern, waehrend der erste Lauf noch blockiert.
        service().onEntityStateChanged(event("binary_sensor.presence_household"));
        debounce.fire();
        service().onEntityStateChanged(event("sensor.sun"));
        debounce.fire();

        releaseFirst.countDown();
        assertTrue(twoRuns.await(5, TimeUnit.SECONDS));
        awaitIdle();

        ArgumentCaptor<AgentTrigger> captor = ArgumentCaptor.forClass(AgentTrigger.class);
        verify(runService, times(2)).run(captor.capture());
        AgentTrigger followUp = captor.getAllValues().get(1);
        assertEquals(AgentTriggerType.WAKE, followUp.type());
        assertEquals(2, followUp.details().size());
        assertTrue(followUp.details().get(0).startsWith("binary_sensor.presence_household"));
        assertTrue(followUp.details().get(1).startsWith("sensor.sun"));
    }

    @Test
    void scheduledTickDuringRunCausesFollowUpLabelledFolgelauf() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch twoRuns = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(runService.run(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            twoRuns.countDown();
            return null;
        });

        service().tick();
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        service().tick();
        releaseFirst.countDown();
        assertTrue(twoRuns.await(5, TimeUnit.SECONDS));
        awaitIdle();

        ArgumentCaptor<AgentTrigger> captor = ArgumentCaptor.forClass(AgentTrigger.class);
        verify(runService, times(2)).run(captor.capture());
        assertEquals(new AgentTrigger(AgentTriggerType.WAKE, List.of("Folgelauf")), captor.getAllValues().get(1));
    }

    @Test
    void followUpIsDroppedWhenSwitchedOffDuringRun() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runService.run(any())).thenAnswer(inv -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });

        service().tick();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        service().tick();
        when(settings.isActive()).thenReturn(false);
        release.countDown();
        awaitIdle();

        verify(runService, times(1)).run(any());
    }

    @Test
    void runNowRunsSynchronouslyAsManual() {
        service().runNow();

        verify(runService).run(new AgentTrigger(AgentTriggerType.MANUAL, List.of()));
        assertFalse(service().isRunning());
    }

    @Test
    void runNowRespectsMinInterval() {
        service().runNow();

        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class, () -> service().runNow());
        assertEquals("Bitte warten — letzter manueller Lauf vor 0 s.", ex.getMessage());
        verify(runService, times(1)).run(any());
    }

    @Test
    void runNowRefusesWhenSwitchedOff() {
        when(settings.isActive()).thenReturn(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service().runNow());
        assertEquals("Hausverstand ist ausgeschaltet.", ex.getMessage());
        verifyNoInteractions(runService);
    }

    @Test
    void runNowRefusesWhenDisabledByProperty() {
        properties.setEnabled(false);

        assertThrows(IllegalStateException.class, () -> service().runNow());
        verifyNoInteractions(runService);
    }

    @Test
    void runNowRefusesWhileRunActive() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(runService.run(any())).thenAnswer(inv -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });

        service().tick();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        TooManyRequestsException ex = assertThrows(TooManyRequestsException.class, () -> service().runNow());
        assertEquals("Es läuft gerade ein Lauf.", ex.getMessage());

        release.countDown();
        awaitIdle();
        // Die Ablehnung hat den Mindestabstand nicht verbraucht: jetzt geht der manuelle Lauf.
        service().runNow();
        verify(runService).run(new AgentTrigger(AgentTriggerType.MANUAL, List.of()));
    }

    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service().isRunning()) {
            assertTrue(System.nanoTime() < deadline, "Lauf wurde nicht beendet");
            Thread.sleep(5);
        }
        // Alles, was bis hierher eingereiht war, ist abgearbeitet.
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    /** Fuehrt jede Aufgabe sofort auf dem Aufrufer-Thread aus. */
    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** Haelt den eingeplanten Debounce-Runnable fest, statt ihn nach Ablauf der Frist auszufuehren. */
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> scheduled = new ArrayList<>();
        private int scheduleCount;
        private long lastDelayMs;

        ManualScheduler() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduled.add(command);
            scheduleCount++;
            lastDelayMs = unit.toMillis(delay);
            return super.schedule(() -> { }, 1, TimeUnit.DAYS);
        }

        void fire() {
            List<Runnable> due;
            synchronized (this) {
                due = new ArrayList<>(scheduled);
                scheduled.clear();
            }
            due.forEach(Runnable::run);
        }

        synchronized boolean isScheduled() { return !scheduled.isEmpty(); }
        synchronized int scheduleCount() { return scheduleCount; }
        synchronized long lastDelayMs() { return lastDelayMs; }
    }
}
