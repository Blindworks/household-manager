package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarCategoryView;
import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.dto.CalendarPersonView;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarCategoryRepository;
import com.household.manager.repository.CalendarEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRUD und Occurrence-Aufloesung des Haushaltskalenders. Die {@link Clock} ist
 * injiziert, damit "heute" in Tests deterministisch ist (Muster WasteCollectionService).
 */
@Service
@Slf4j
public class CalendarEventService {

    private final CalendarEventRepository repository;
    private final CalendarCategoryRepository categoryRepository;
    private final CalendarEventPersonService personService;
    private final RecurrenceExpansionService expansionService;
    private final Clock clock;
    private final AuditService auditService;

    public CalendarEventService(CalendarEventRepository repository,
                                CalendarCategoryRepository categoryRepository,
                                CalendarEventPersonService personService,
                                RecurrenceExpansionService expansionService,
                                Clock clock,
                                AuditService auditService) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.personService = personService;
        this.expansionService = expansionService;
        this.clock = clock;
        this.auditService = auditService;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Maximale Fenstergroesse einer Occurrence-Abfrage (deckt sich mit der Expansions-Kappe). */
    private static final long MAX_WINDOW_DAYS = 366;

    /**
     * Alle Vorkommen im Fenster [from, to]: Serien expandiert, EXDATEs gefiltert,
     * Overrides eingerechnet. Mehrtaegige Termine erscheinen an ihrem Starttag
     * (endDate ist Metadatum, kein Spanning im Raster — bewusste v1-Entscheidung).
     */
    @Transactional(readOnly = true)
    public List<CalendarOccurrenceResponse> getOccurrences(LocalDate from, LocalDate to) {
        validateWindow(from, to);
        LocalDate today = today();
        List<CalendarEvent> all = repository.findAll();
        // Kategorien und Personen einmal fuer die ganze Abfrage laden statt pro Termin
        // nachzuschlagen: der Fensterabruf kostet dadurch eine feste Zahl zusaetzlicher
        // Abfragen, unabhaengig von der Terminanzahl.
        Map<Long, CalendarCategory> categories = categoriesById();
        Map<Long, List<CalendarPersonView>> personsByEvent = personService.viewsByEvent();
        Map<Long, Set<LocalDate>> overriddenDates = all.stream()
                .filter(CalendarEvent::isOverride)
                .collect(Collectors.groupingBy(CalendarEvent::getRecurringParentId,
                        Collectors.mapping(CalendarEvent::getRecurrenceDate, Collectors.toSet())));

        List<CalendarOccurrenceResponse> occurrences = new ArrayList<>();
        for (CalendarEvent event : all) {
            CalendarCategoryView category = categoryView(categories.get(event.getCategoryId()));
            // Bewusst die eigene Zeilen-Id: eine Override-Zeile hat ihre eigenen Personen,
            // auch wenn die Antwort als eventId die Master-Id ausweist.
            List<CalendarPersonView> persons =
                    personsByEvent.getOrDefault(event.getId(), List.of());
            if (event.isOverride() || !event.isRecurring()) {
                if (!event.getStartDate().isBefore(from) && !event.getStartDate().isAfter(to)) {
                    occurrences.add(toOccurrence(
                            event, event.getStartDate(), today, category, persons));
                }
                continue;
            }
            Set<LocalDate> skip = new HashSet<>(event.exdateSet());
            skip.addAll(overriddenDates.getOrDefault(event.getId(), Set.of()));
            for (LocalDate date : expansionService.expand(
                    event.getRrule(), event.getStartDate(), from, to)) {
                if (!skip.contains(date)) {
                    occurrences.add(toOccurrence(event, date, today, category, persons));
                }
            }
        }
        occurrences.sort(Comparator
                .comparing(CalendarOccurrenceResponse::getOccurrenceDate)
                .thenComparing(o -> o.getStartTime() != null ? o.getStartTime() : LocalTime.MIN));
        return occurrences;
    }

    /** Die naechsten Vorkommen ab jetzt; heute bereits beendete Uhrzeit-Termine fallen raus. */
    @Transactional(readOnly = true)
    public List<CalendarOccurrenceResponse> getUpcoming(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        LocalDate today = today();
        LocalTime now = LocalTime.now(clock);
        return getOccurrences(today, today.plusDays(MAX_WINDOW_DAYS - 1)).stream()
                .filter(occ -> !isAlreadyOver(occ, today, now))
                .limit(limit)
                .toList();
    }

    /**
     * Heutige Uhrzeit-Termine gelten ab ihrem Ende (bzw. Start, wenn kein Ende) als vorbei.
     * Fehlt sowohl Ende als auch Start (fehlerhafte Altdaten), gilt der Termin defensiv
     * als nicht vorbei — dieser Pfad wird ab Task 8 minuetlich vom Erinnerungs-Scheduler
     * aufgerufen und darf nie eine NullPointerException werfen.
     */
    private boolean isAlreadyOver(CalendarOccurrenceResponse occ, LocalDate today, LocalTime now) {
        if (!occ.getOccurrenceDate().equals(today) || occ.isAllDay()) {
            return false;
        }
        LocalTime end = occ.getEndTime() != null ? occ.getEndTime() : occ.getStartTime();
        return end != null && end.isBefore(now);
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Zeitraum ist ungueltig (from muss vor to liegen).");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Zeitraum darf hoechstens ein Jahr umfassen.");
        }
    }

    private CalendarOccurrenceResponse toOccurrence(CalendarEvent event, LocalDate date,
                                                    LocalDate today,
                                                    CalendarCategoryView category,
                                                    List<CalendarPersonView> persons) {
        boolean override = event.isOverride();
        long durationDays = event.getEndDate() != null
                ? ChronoUnit.DAYS.between(event.getStartDate(), event.getEndDate()) : 0;
        return CalendarOccurrenceResponse.builder()
                .eventId(override ? event.getRecurringParentId() : event.getId())
                .occurrenceDate(date)
                .recurrenceDate(override ? event.getRecurrenceDate()
                        : (event.isRecurring() ? date : null))
                .title(event.getTitle())
                .notes(event.getNotes())
                .category(category)
                .allDay(event.isAllDay())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate() != null ? date.plusDays(durationDays) : null)
                .recurring(event.isRecurring() || override)
                .persons(persons)
                .daysUntil(ChronoUnit.DAYS.between(today, date))
                .build();
    }

    /** Alle Kategorien nach Id — eine Abfrage pro Fensterabruf statt einer pro Termin. */
    private Map<Long, CalendarCategory> categoriesById() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(CalendarCategory::getId, Function.identity()));
    }

    /**
     * Die einzige Stelle, die aus einer Kategorie die eingebettete Ansicht macht — egal ob
     * sie aus der Sammelabfrage oder aus der Validierung stammt. Fehlt sie (der
     * Fremdschluessel schliesst das aus, ein Testdouble aber nicht), liefert die Methode
     * null statt zu werfen — eine fehlende Farbe darf nie den ganzen Monat leeren.
     */
    private CalendarCategoryView categoryView(CalendarCategory category) {
        return category != null ? CalendarCategoryView.of(category) : null;
    }

    @Transactional(readOnly = true)
    public CalendarEventResponse getEvent(Long id) {
        CalendarEvent event = findOrThrow(id);
        return toResponse(event, categoryRepository.findById(event.getCategoryId()).orElse(null),
                personService.viewsFor(event.getId()));
    }

    @Transactional
    public CalendarEventResponse create(CalendarEventRequest request) {
        CalendarCategory category = validateAndResolveCategory(request);
        CalendarEvent saved = repository.save(applyRequest(request, new CalendarEvent()));
        List<CalendarPersonView> persons =
                personService.replace(saved.getId(), request.getPersonUserIds());
        CalendarEventResponse response = toResponse(saved, category, persons);
        auditService.record("calendar.create", request.getTitle());
        return response;
    }

    @Transactional
    public CalendarEventResponse update(Long id, CalendarEventRequest request) {
        CalendarCategory category = validateAndResolveCategory(request);
        CalendarEvent event = findOrThrow(id);
        String oldRrule = event.getRrule();
        String newRrule = normalizeRrule(request.getRrule());
        applyRequest(request, event);
        if (!Objects.equals(oldRrule, newRrule)) {
            // Eine geaenderte (auch entfernte oder neu gesetzte) RRULE definiert die Serie neu:
            // bestehende Einzelausnahmen (Overrides, EXDATEs) sind an die alte Regel gebunden
            // und liessen sich der neuen nicht mehr sinnvoll zuordnen (wie in gaengigen
            // Kalender-Apps). Bleibt die RRULE unveraendert, bleiben Ausnahmen erhalten.
            event.setExdates(null);
            repository.deleteByRecurringParentId(id);
        }
        CalendarEvent saved = repository.save(event);
        List<CalendarPersonView> persons =
                personService.replace(saved.getId(), request.getPersonUserIds());
        CalendarEventResponse response = toResponse(saved, category, persons);
        auditService.record("calendar.update", request.getTitle());
        return response;
    }

    /**
     * Loescht den Termin samt seiner Override-Zeilen. Die Personenzuordnungen raeumt
     * ausschliesslich die Datenbank ab (ON DELETE CASCADE auf {@code calendar_event_id}) —
     * bewusst ohne expliziten Aufruf, denn auch die Zuordnungen der hier geloeschten
     * Override-Zeilen, die von {@code update} bei geaenderter RRULE und die von
     * {@code deleteOccurrence} haengen an derselben Kaskade. Eine Absicherung, die nur
     * einen von vier Pfaden abdeckt, waere ein Versprechen, das sie nicht haelt.
     *
     * <p>Fuer die Override-Zeilen eine Zeile weiter unten steht die Absicherung dagegen zu
     * Recht: Master-Zeilen mit Override-Kindern loescht ausschliesslich diese Methode, dort
     * deckt sie also den einzigen Pfad ab.
     */
    @Transactional
    public void delete(Long id) {
        CalendarEvent event = findOrThrow(id);
        // DB hat ON DELETE CASCADE fuer recurring_parent_id; der explizite Aufruf hier ist
        // bewusste Absicherung (z.B. falls der Fremdschluessel je gelockert wird), kein toter Code.
        repository.deleteByRecurringParentId(id);
        repository.delete(event);
        auditService.record("calendar.delete", "Termin " + id);
    }

    /**
     * Loescht nur dieses Vorkommen: bei Einzelterminen die ganze Zeile, bei Serien
     * EXDATE am Master plus Entfernen eines eventuellen Overrides.
     *
     * <p>Wird die Id einer Override-Zeile selbst uebergeben, muss das trotzdem als
     * "Vorkommen loeschen" behandelt werden: {@code isRecurring()} ist fuer eine
     * Override-Zeile immer false (ihre RRULE wird von {@link #updateOccurrence} bewusst
     * genullt), sodass sie sonst in den Einzeltermin-Zweig liefe - die Zeile wuerde
     * geloescht, aber ohne EXDATE am Master erschiene das unbearbeitete Original der
     * Serie an diesem Tag wieder. Bei einem echten Einzeltermin muss das uebergebene
     * Datum zum Startdatum passen, sonst waere ein falsches/veraltetes Client-Datum eine
     * stille Komplett-Loeschung.
     */
    @Transactional
    public void deleteOccurrence(Long id, LocalDate occurrenceDate) {
        applyOccurrenceDeletion(id, occurrenceDate);
        auditService.record("calendar.delete-occurrence", "Termin " + id + " am " + occurrenceDate);
    }

    private void applyOccurrenceDeletion(Long id, LocalDate occurrenceDate) {
        CalendarEvent event = findOrThrow(id);
        if (event.isOverride()) {
            CalendarEvent master = findOrThrow(event.getRecurringParentId());
            master.addExdate(event.getRecurrenceDate());
            repository.save(master);
            repository.delete(event);
            return;
        }
        if (!event.isRecurring()) {
            if (!event.getStartDate().equals(occurrenceDate)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Das Datum %s entspricht nicht dem Termin.".formatted(occurrenceDate));
            }
            repository.delete(event);
            return;
        }
        repository.findByRecurringParentIdAndRecurrenceDate(id, occurrenceDate)
                .ifPresent(repository::delete);
        event.addExdate(occurrenceDate);
        repository.save(event);
    }

    /**
     * Aendert nur dieses Vorkommen: legt eine Override-Zeile an bzw. aktualisiert sie.
     *
     * <p>Das Datum muss ein echtes Vorkommen der Serie sein - sonst entstuende eine
     * Override-Zeile, die dauerhaft (unabhaengig vom Master) in jeder Fensterabfrage
     * auftaucht, obwohl die Serie das Datum nie erzeugt haette. Die RRULE des Requests
     * wird vor der Validierung neutralisiert, weil sie ohnehin verworfen wird (Overrides
     * sind nie selbst Serien) - sonst koennte eine versehentlich mitgeschickte
     * Master-RRULE das Speichern an einer nie benutzten Regel scheitern lassen. Ein
     * bestehendes EXDATE fuer dieses Datum wird entfernt: der Override gewinnt gegenueber
     * einer frueheren Loeschung, ein Datum darf nicht gleichzeitig geloescht und
     * geaendert sein.
     */
    @Transactional
    public CalendarOccurrenceResponse updateOccurrence(Long id, LocalDate occurrenceDate,
                                                  CalendarEventRequest request) {
        CalendarEvent master = findOrThrow(id);
        if (!master.isRecurring()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nur Serien haben einzelne Vorkommen — Einzeltermine direkt bearbeiten.");
        }
        if (expansionService.expand(master.getRrule(), master.getStartDate(), occurrenceDate, occurrenceDate)
                .isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Datum %s ist kein Vorkommen dieser Serie.".formatted(occurrenceDate));
        }
        request.setRrule(null);
        CalendarCategory category = validateAndResolveCategory(request);
        CalendarEvent override = repository
                .findByRecurringParentIdAndRecurrenceDate(id, occurrenceDate)
                .orElseGet(() -> CalendarEvent.builder()
                        .recurringParentId(id)
                        .recurrenceDate(occurrenceDate)
                        .build());
        applyRequest(request, override);
        override.setRrule(null); // Overrides sind nie selbst Serien
        master.removeExdate(occurrenceDate);
        repository.save(master);
        CalendarEvent saved = repository.save(override);
        // Bewusst die Id der Override-Zeile, nicht die der Serie: das geaenderte Vorkommen
        // hat seine eigenen Personen.
        List<CalendarPersonView> persons =
                personService.replace(saved.getId(), request.getPersonUserIds());
        CalendarOccurrenceResponse response = toOccurrence(saved, occurrenceDate, today(),
                categoryView(category), persons);
        auditService.record("calendar.update-occurrence", "Termin " + id + " am " + occurrenceDate);
        return response;
    }

    private CalendarEvent findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Termin %d existiert nicht.".formatted(id)));
    }

    private CalendarEvent applyRequest(CalendarEventRequest request, CalendarEvent event) {
        event.setTitle(request.getTitle().trim());
        event.setNotes(request.getNotes());
        event.setCategoryId(request.getCategoryId());
        event.setAllDay(request.isAllDay());
        event.setStartDate(request.getStartDate());
        event.setStartTime(request.isAllDay() ? null : request.getStartTime());
        event.setEndTime(request.isAllDay() ? null : request.getEndTime());
        // end_date ist laut Entity/Spec nur fuer mehrtaegige Ganztags-Termine gedacht.
        event.setEndDate(request.isAllDay() ? request.getEndDate() : null);
        event.setRrule(normalizeRrule(request.getRrule()));
        return event;
    }

    /** null/leer wird einheitlich zu null; sonst der unveraenderte RRULE-String. */
    private String normalizeRrule(String rrule) {
        return rrule != null && !rrule.isBlank() ? rrule : null;
    }

    /**
     * Prueft den Request und liefert dabei die geladene Kategorie zurueck. Die Antwort
     * braucht sie ohnehin; so gibt es genau eine Abfrage und genau eine Stelle, die
     * entscheidet, ob eine Kategorie-Id gueltig ist. Laeuft vollstaendig vor dem ersten
     * Schreibzugriff — auch die Personenpruefung.
     */
    private CalendarCategory validateAndResolveCategory(CalendarEventRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Titel darf nicht leer sein.");
        }
        if (request.getTitle().trim().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Titel darf hoechstens 200 Zeichen lang sein.");
        }
        if (request.getCategoryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Kategorie fehlt.");
        }
        CalendarCategory category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Die Kategorie %d existiert nicht.".formatted(request.getCategoryId())));
        if (request.getStartDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Startdatum fehlt.");
        }
        if (!request.isAllDay() && request.getStartTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ein Termin mit Uhrzeit braucht eine Start-Uhrzeit.");
        }
        if (!request.isAllDay() && request.getStartTime() != null && request.getEndTime() != null
                && request.getEndTime().isBefore(request.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Ende darf nicht vor dem Start liegen.");
        }
        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Enddatum darf nicht vor dem Startdatum liegen.");
        }
        if (request.getRrule() != null && !request.getRrule().isBlank()) {
            expansionService.validate(request.getRrule());
            if (!expansionService.hasAnyOccurrence(request.getRrule(), request.getStartDate())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Das Ende der Wiederholung darf nicht vor dem Startdatum liegen.");
            }
        }
        personService.validate(request.getPersonUserIds());
        return category;
    }

    private CalendarEventResponse toResponse(CalendarEvent event, CalendarCategory category,
                                             List<CalendarPersonView> persons) {
        return CalendarEventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .notes(event.getNotes())
                .category(categoryView(category))
                .allDay(event.isAllDay())
                .startDate(event.getStartDate())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate())
                .rrule(event.getRrule())
                .recurring(event.isRecurring())
                .persons(persons)
                .build();
    }
}
