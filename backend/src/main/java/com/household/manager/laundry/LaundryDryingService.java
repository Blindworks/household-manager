package com.household.manager.laundry;

import com.household.manager.audit.AuditService;
import com.household.manager.repository.LaundryDryingSessionRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/** Lebenslauf eines Waschgangs: Frage, Trocknen, "koennte trocken sein", Ende — hoechstens einer offen. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LaundryDryingService {

    private final LaundryDryingSessionRepository repository;
    private final LaundryClimateReader climateReader;
    private final LaundryEntityReporter reporter;
    private final AuditService auditService;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    /** Zeitpunkt der letzten "neuen Waesche" (Maschine laeuft an); nur im Speicher. */
    private volatile LocalDateTime lastNewWashAt;

    /** Stellt die Frage "Waesche aufgehaengt?" — nur ohne offenen Waschgang und ohne Sperre durch ein Nein. */
    public synchronized void ask(LaundryTrigger trigger, double baselineHumidity) {
        if (openSession().isPresent() || declinedForCurrentWash()) {
            return;
        }
        LaundryDryingSession session = new LaundryDryingSession();
        session.setStatus(LaundrySessionStatus.ASKING);
        session.setTrigger(trigger);
        session.setAskedAt(now());
        session.setBaselineHumidity(baselineHumidity);
        repository.save(session);
        reportCurrentState();
    }

    public synchronized void answerHung(boolean hung) {
        LaundryDryingSession session = openSession()
                .filter(s -> s.getStatus() == LaundrySessionStatus.ASKING)
                .orElseThrow(() -> new LaundryConflictException("Keine offene Frage „Wäsche aufgehängt?“"));
        LocalDateTime now = now();
        if (hung) {
            session.setStatus(LaundrySessionStatus.DRYING);
            session.setStartedAt(now);
            session.setLastAccumulatedAt(now);
            session.setAccumulatedLoad(0.0);
            session.setPeakExcess(0.0);
        } else {
            session.setStatus(LaundrySessionStatus.DECLINED);
            session.setEndedAt(now);
        }
        repository.save(session);
        auditService.record("laundry.answer.hung", hung ? "ja" : "nein");
        reportCurrentState();
    }

    public synchronized void answerDry(boolean dry) {
        LaundryDryingSession session = openSession()
                .filter(s -> s.getStatus() == LaundrySessionStatus.MAYBE_DRY && s.isDryQuestionOpen())
                .orElseThrow(() -> new LaundryConflictException("Keine offene Frage „Ist die Wäsche trocken?“"));
        LocalDateTime now = now();
        double load = loadOf(session);
        session.setDryQuestionOpen(false);
        if (dry) {
            session.setStatus(LaundrySessionStatus.DONE);
            session.setConfirmedLoad(load);
            session.setEndedAt(now);
        } else {
            Double previous = session.getLowerBoundLoad();
            session.setLowerBoundLoad(previous == null ? load : Math.max(previous, load));
            session.setDryQuestionAt(LaundrySessionStepper.outsideQuietHours(now.plus(LaundryRules.REASK_AFTER)));
        }
        repository.save(session);
        auditService.record("laundry.answer.dry", dry ? "ja" : "noch nicht");
        reportCurrentState();
    }

    /** Hub-Karte angetippt: Waesche abgenommen. Vor "koennte trocken sein" ist das keine Probe. */
    public synchronized void markRemoved() {
        LaundryDryingSession session = openSession()
                .filter(s -> s.getStatus() == LaundrySessionStatus.DRYING
                        || s.getStatus() == LaundrySessionStatus.MAYBE_DRY)
                .orElseThrow(() -> new LaundryConflictException("Kein trocknender Waschgang"));
        if (session.getStatus() == LaundrySessionStatus.MAYBE_DRY) {
            session.setConfirmedLoad(loadOf(session));
        }
        session.setStatus(LaundrySessionStatus.DONE);
        session.setDryQuestionOpen(false);
        session.setEndedAt(now());
        repository.save(session);
        auditService.record("laundry.removed", "Waschgang " + session.getId());
        reportCurrentState();
    }

    /** Die Maschine laeuft wieder an: ein frueheres "Nein" sperrt die naechste Frage nicht mehr. */
    public void onNewWash() {
        lastNewWashAt = now();
    }

    /** Minuetlicher Takt; wirft nie. */
    public synchronized void tick() {
        try {
            Optional<LaundryDryingSession> open = openSession();
            if (open.isPresent()) {
                LaundryDryingSession session = open.get();
                LaundrySessionStepper.advance(session, climateReader.current(), now(), requiredLoad());
                repository.save(session);
            }
            reportCurrentState();
        } catch (Exception ex) {
            log.warn("Waeschetrocknung: Takt fehlgeschlagen: {}", ex.getMessage());
            jobMonitor.reportFailure(ex.getMessage());
        }
    }

    public LaundryStateView currentState() {
        double requiredLoad = requiredLoad();
        Optional<LaundryDryingSession> open = openSession();
        if (open.isEmpty()) {
            return new LaundryStateView(LaundryStateView.IDLE, LaundryStateView.PROMPT_NONE,
                    null, null, false, requiredLoad);
        }
        LaundryDryingSession session = open.get();
        String status = session.getStatus().name().toLowerCase(Locale.ROOT);
        if (session.getStatus() == LaundrySessionStatus.ASKING) {
            return new LaundryStateView(status, LaundryStateView.PROMPT_HUNG, null, null, false, requiredLoad);
        }
        String prompt = session.getStatus() == LaundrySessionStatus.MAYBE_DRY && session.isDryQuestionOpen()
                ? LaundryStateView.PROMPT_DRY : LaundryStateView.PROMPT_NONE;
        Optional<KellerClimate> climate = climateReader.current();
        LocalDateTime predicted = climate.map(c -> predictDryAt(session, c, requiredLoad)).orElse(null);
        if (predicted != null && session.getStatus() == LaundrySessionStatus.DRYING
                && LaundrySessionStepper.maybeDry(session, requiredLoad)) {
            // K oder Kurvenkriterium erreicht, die Frage wartet nur auf das Ende der Ruhezeit
            predicted = LaundrySessionStepper.outsideQuietHours(now());
        }
        return new LaundryStateView(status, prompt, session.getStartedAt(), predicted, climate.isEmpty(), requiredLoad);
    }

    /** Gelernter Bedarf K aus den letzten abgeschlossenen Waschgaengen. */
    public double requiredLoad() {
        List<List<DryingSample>> samples = recentSessions().stream()
                .map(LaundryDryingService::samplesOf)
                .toList();
        return DryingCalibration.requiredLoad(samples);
    }

    /** Die letzten zehn abgeschlossenen, tatsaechlich gestarteten Waschgaenge, neueste zuerst. */
    public List<LaundryDryingSession> recentSessions() {
        return repository.findTop10ByStatusInAndStartedAtIsNotNullOrderByAskedAtDesc(LaundrySessionStatus.FINISHED);
    }

    /** Jetzt plus Restzeit, nie nach dem Ende des Waschgangs (Start + 72 h); leer ohne Trockenleistung. */
    private LocalDateTime predictDryAt(LaundryDryingSession session, KellerClimate climate, double requiredLoad) {
        double vpd = DryingPhysics.vaporPressureDeficitKpa(climate.tempC(), climate.humidity());
        OptionalDouble hours = DryingPhysics.remainingHours(requiredLoad, loadOf(session), vpd);
        if (hours.isEmpty()) {
            return null;
        }
        LocalDateTime latest = session.getStartedAt().plus(LaundryRules.SESSION_TIMEOUT);
        double seconds = Math.min(hours.getAsDouble() * 3600.0, LaundryRules.SESSION_TIMEOUT.toSeconds());
        LocalDateTime predicted = now().plusSeconds(Math.round(seconds));
        return predicted.isAfter(latest) ? latest : predicted;
    }

    private static List<DryingSample> samplesOf(LaundryDryingSession session) {
        List<DryingSample> samples = new ArrayList<>();
        if (session.getConfirmedLoad() != null) {
            samples.add(new DryingSample(SampleKind.CONFIRMED, session.getConfirmedLoad()));
        }
        if (session.getWeakLoad() != null) {
            samples.add(new DryingSample(SampleKind.WEAK, session.getWeakLoad()));
        }
        if (session.getLowerBoundLoad() != null) {
            samples.add(new DryingSample(SampleKind.LOWER_BOUND, session.getLowerBoundLoad()));
        }
        return samples;
    }

    /** Sperre: juengster Waschgang abgelehnt und seither keine neue Waesche. */
    private boolean declinedForCurrentWash() {
        LocalDateTime newWashAt = lastNewWashAt;
        return repository.findFirstByOrderByAskedAtDesc()
                .filter(latest -> latest.getStatus() == LaundrySessionStatus.DECLINED)
                .filter(latest -> newWashAt == null || !newWashAt.isAfter(latest.getAskedAt()))
                .isPresent();
    }

    private Optional<LaundryDryingSession> openSession() {
        return repository.findFirstByStatusInOrderByAskedAtDesc(LaundrySessionStatus.OPEN);
    }

    private void reportCurrentState() {
        try {
            reporter.report(currentState());
        } catch (Exception ex) {
            log.warn("Waeschetrocknung: Entitaet nicht gemeldet: {}", ex.getMessage());
        }
    }

    private static double loadOf(LaundryDryingSession session) {
        return session.getAccumulatedLoad() == null ? 0.0 : session.getAccumulatedLoad();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
