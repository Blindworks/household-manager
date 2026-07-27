package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.repository.CalendarCategoryRepository;
import com.household.manager.repository.CalendarEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verwaltung der Kalender-Kategorien. Der Schluessel entsteht einmal beim Anlegen und
 * bleibt danach unangetastet — er ist der Vertrag zur Flow-Engine.
 */
@Service
@RequiredArgsConstructor
public class CalendarCategoryService {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final CalendarCategoryRepository repository;
    private final CalendarEventRepository eventRepository;
    private final CalendarCategoryKeyGenerator keyGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CalendarCategoryResponse> list() {
        return repository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(CalendarCategoryResponse::of)
                .toList();
    }

    @Transactional
    public CalendarCategoryResponse create(CalendarCategoryRequest request) {
        validate(request);
        Set<String> taken = repository.findAll().stream()
                .map(CalendarCategory::getKey)
                .collect(Collectors.toSet());
        CalendarCategory category = CalendarCategory.builder()
                .key(keyGenerator.generate(request.name(), taken))
                .name(request.name().trim())
                .color(request.color())
                .icon(blankToNull(request.icon()))
                .sortOrder(request.sortOrder())
                .active(request.active())
                .build();
        CalendarCategoryResponse response = CalendarCategoryResponse.of(repository.save(category));
        auditService.record("calendar-category.create", response.name());
        return response;
    }

    @Transactional
    public CalendarCategoryResponse update(Long id, CalendarCategoryRequest request) {
        validate(request);
        CalendarCategory category = findOrThrow(id);
        // Der Schluessel wird bewusst nicht neu berechnet.
        category.setName(request.name().trim());
        category.setColor(request.color());
        category.setIcon(blankToNull(request.icon()));
        category.setSortOrder(request.sortOrder());
        category.setActive(request.active());
        CalendarCategoryResponse response = CalendarCategoryResponse.of(repository.save(category));
        auditService.record("calendar-category.update", response.name());
        return response;
    }

    /**
     * Loescht nur, solange kein Termin die Kategorie nutzt. Der Fremdschluessel wuerde das
     * ohnehin verhindern — die Pruefung hier liefert die verstaendliche Meldung samt Anzahl,
     * damit die Admin-Seite das Deaktivieren als Ausweg anbieten kann.
     */
    @Transactional
    public void delete(Long id) {
        CalendarCategory category = findOrThrow(id);
        long inUse = eventRepository.countByCategoryId(id);
        if (inUse > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Die Kategorie wird von %d Termin(en) genutzt und kann nicht geloescht werden."
                            .formatted(inUse));
        }
        repository.delete(category);
        auditService.record("calendar-category.delete", category.getName());
    }

    private CalendarCategory findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Die Kategorie %d existiert nicht.".formatted(id)));
    }

    private void validate(CalendarCategoryRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf nicht leer sein.");
        }
        if (request.name().trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf hoechstens 100 Zeichen lang sein.");
        }
        if (request.color() == null || !HEX_COLOR.matcher(request.color()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Farbe muss ein Hex-Wert wie #4caf50 sein.");
        }
        if (request.icon() != null && request.icon().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Iconname darf hoechstens 50 Zeichen lang sein.");
        }
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
