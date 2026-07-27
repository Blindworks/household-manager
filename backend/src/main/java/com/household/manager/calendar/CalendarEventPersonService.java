package com.household.manager.calendar;

import com.household.manager.dto.CalendarPersonView;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.CalendarEventPerson;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.CalendarEventPersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Die Zuordnung von Terminen zu Haushaltsmitgliedern. Bewusst neben dem
 * {@link CalendarEventService} und nicht in ihm: dieser Block kennt Nutzerkonten, der
 * Kalender nicht — beide reden ausschliesslich ueber Termin-Ids, Nutzer-Ids und fertige
 * Ansichten miteinander.
 *
 * <p>Keine Zeile fuer einen Termin bedeutet "betrifft den ganzen Haushalt". Das ist der
 * Normalfall (z.B. Muellabfuhr), kein fehlender Wert.
 */
@Service
@RequiredArgsConstructor
public class CalendarEventPersonService {

    private final CalendarEventPersonRepository repository;
    private final AppUserRepository userRepository;

    /**
     * Wirft, sobald eine Id keinem Nutzer entspricht — sonst entstuenden stille
     * Geisterzuordnungen. Gehoert vor den ersten Schreibzugriff des Aufrufers.
     */
    @Transactional(readOnly = true)
    public void validate(List<Long> userIds) {
        List<Long> distinct = distinctIds(userIds);
        if (distinct.isEmpty()) {
            return;
        }
        Set<Long> known = userRepository.findAllById(distinct).stream()
                .map(AppUser::getId).collect(Collectors.toSet());
        for (Long userId : distinct) {
            if (!known.contains(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Der Nutzer %d existiert nicht.".formatted(userId));
            }
        }
    }

    /**
     * Bringt die Zuordnungen eines Termins auf den gewuenschten Stand und liefert sie in
     * ihrer Antwortform zurueck, damit der Aufrufer sie nicht ein zweites Mal lesen muss.
     *
     * <p>Bewusst als Differenz und nicht als "alles loeschen, alles neu schreiben":
     * denselben Primaerschluessel innerhalb einer Transaktion zu loeschen und sofort wieder
     * anzulegen waere eine Wette auf Hibernates Flush-Reihenfolge (Inserts laufen vor
     * Deletes) — und genau das passiert beim blossen Umbenennen eines Termins mit
     * gleichbleibenden Personen. Duplikate im Request werden dabei zusammengefasst.
     *
     * <p>Die Id ist immer die der geschriebenen Zeile: eine Override-Zeile hat ihre eigenen
     * Personen, nicht die der Serie.
     */
    @Transactional
    public List<CalendarPersonView> replace(Long eventId, List<Long> userIds) {
        List<Long> wanted = distinctIds(userIds);
        List<CalendarEventPerson> existing = repository.findByCalendarEventId(eventId);
        List<CalendarEventPerson> obsolete = existing.stream()
                .filter(link -> !wanted.contains(link.getUserId()))
                .toList();
        if (!obsolete.isEmpty()) {
            repository.deleteAll(obsolete);
        }
        Set<Long> alreadyLinked = existing.stream()
                .map(CalendarEventPerson::getUserId).collect(Collectors.toSet());
        List<CalendarEventPerson> added = wanted.stream()
                .filter(userId -> !alreadyLinked.contains(userId))
                .map(userId -> CalendarEventPerson.builder()
                        .calendarEventId(eventId).userId(userId).build())
                .toList();
        if (!added.isEmpty()) {
            repository.saveAll(added);
        }
        return views(wanted);
    }

    /** Die Personen eines einzelnen Termins. */
    @Transactional(readOnly = true)
    public List<CalendarPersonView> viewsFor(Long eventId) {
        return views(repository.findByCalendarEventId(eventId).stream()
                .map(CalendarEventPerson::getUserId)
                .toList());
    }

    /**
     * Alle Personen nach Termin-Id. Zwei Abfragen fuer den ganzen Fensterabruf statt zweier
     * pro Termin — die Aufrufseite darf pro Termin nichts mehr nachschlagen.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<CalendarPersonView>> viewsByEvent() {
        Map<Long, AppUser> users = usersById(userRepository.findAll());
        return repository.findAll().stream()
                .collect(Collectors.groupingBy(CalendarEventPerson::getCalendarEventId,
                        Collectors.mapping(CalendarEventPerson::getUserId,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        userIds -> views(userIds, users)))));
    }

    private List<CalendarPersonView> views(List<Long> userIds) {
        return views(userIds, usersById(userRepository.findAllById(userIds)));
    }

    /**
     * Die einzige Stelle, die aus Nutzer-Ids eingebettete Personen macht. Eine Id ohne
     * passenden Nutzer faellt weg statt namenlos ausgeliefert zu werden — der
     * Fremdschluessel schliesst das aus, ein Testdouble aber nicht.
     */
    private List<CalendarPersonView> views(List<Long> userIds, Map<Long, AppUser> usersById) {
        return userIds.stream()
                .map(usersById::get)
                .filter(Objects::nonNull)
                .map(CalendarPersonView::of)
                .toList();
    }

    private Map<Long, AppUser> usersById(List<AppUser> users) {
        return users.stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    /** null-Eintraege fliegen raus: sie wuerden in Spring Data als 500er enden. */
    private List<Long> distinctIds(List<Long> userIds) {
        return userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
    }
}
