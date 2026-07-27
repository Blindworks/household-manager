package com.household.manager.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.dto.CalendarCategoryView;
import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CalendarEventControllerTest {

    @Mock
    private CalendarEventService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders.standaloneSetup(new CalendarEventController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private CalendarOccurrenceResponse occurrence() {
        return CalendarOccurrenceResponse.builder()
                .eventId(1L)
                .occurrenceDate(LocalDate.of(2026, 7, 27))
                .title("Zahnarzt")
                .category(new CalendarCategoryView(3L, "health", "Gesundheit", "#e57373", null))
                .allDay(false)
                .recurring(false)
                .daysUntil(2)
                .build();
    }

    private CalendarEventResponse eventResponse() {
        return CalendarEventResponse.builder()
                .id(1L)
                .title("Zahnarzt")
                .category(new CalendarCategoryView(3L, "health", "Gesundheit", "#e57373", null))
                .allDay(false)
                .startDate(LocalDate.of(2026, 7, 27))
                .recurring(false)
                .build();
    }

    @Test
    void getOccurrencesLiefertDieExpandierteListe() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(service.getOccurrences(from, to)).thenReturn(List.of(occurrence()));

        mockMvc.perform(get("/v1/calendar/events").param("from", "2026-07-01").param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Zahnarzt"))
                .andExpect(jsonPath("$[0].eventId").value(1))
                // Die Kategorie kommt eingebettet mit, damit das Raster ohne Nachschlagen rendert.
                .andExpect(jsonPath("$[0].category.key").value("health"))
                .andExpect(jsonPath("$[0].category.color").value("#e57373"));
    }

    @Test
    void getUpcomingOhneParameterNutztDefaultLimitDrei() throws Exception {
        when(service.getUpcoming(3)).thenReturn(List.of(occurrence()));

        mockMvc.perform(get("/v1/calendar/upcoming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Zahnarzt"));

        verify(service).getUpcoming(3);
    }

    @Test
    void postEventsLiefert201() throws Exception {
        when(service.create(any(CalendarEventRequest.class))).thenReturn(eventResponse());

        mockMvc.perform(post("/v1/calendar/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Zahnarzt","categoryId":3,"allDay":false,
                                 "startDate":"2026-07-27"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Zahnarzt"));
    }

    @Test
    void deleteEventLiefert204() throws Exception {
        mockMvc.perform(delete("/v1/calendar/events/1"))
                .andExpect(status().isNoContent());

        verify(service).delete(1L);
    }

    @Test
    void deleteOccurrenceReichtDasGeparsteDatumDurch() throws Exception {
        mockMvc.perform(delete("/v1/calendar/events/1/occurrences/2026-07-27"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(service).deleteOccurrence(eq(1L), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    void serviceFehlerAls400KommtBeimClientAn() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2020, 1, 1);
        when(service.getOccurrences(from, to)).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Enddatum darf nicht vor dem Startdatum liegen."));

        mockMvc.perform(get("/v1/calendar/events").param("from", "2026-07-01").param("to", "2020-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Das Enddatum darf nicht vor dem Startdatum liegen."));
    }

    @Test
    void getEventLiefertDieStammdaten() throws Exception {
        when(service.getEvent(1L)).thenReturn(eventResponse());

        mockMvc.perform(get("/v1/calendar/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Zahnarzt"));
    }

    @Test
    void putEventReichtIdUndRequestDurch() throws Exception {
        when(service.update(eq(1L), any(CalendarEventRequest.class))).thenReturn(eventResponse());

        mockMvc.perform(put("/v1/calendar/events/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Zahnarzt","categoryId":3,"allDay":false,
                                 "startDate":"2026-07-27"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Zahnarzt"));

        ArgumentCaptor<CalendarEventRequest> requestCaptor = ArgumentCaptor.forClass(CalendarEventRequest.class);
        verify(service).update(eq(1L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTitle()).isEqualTo("Zahnarzt");
        assertThat(requestCaptor.getValue().getCategoryId()).isEqualTo(3L);
    }

    @Test
    void putOccurrenceReichtIdUndDatumDurchUndLiefertDenOccurrenceTyp() throws Exception {
        // Vertrag mit dem Frontend (siehe Quality-Review): eventId zeigt auf die Serie, nicht
        // auf die eigene Id der Override-Zeile.
        CalendarOccurrenceResponse updated = CalendarOccurrenceResponse.builder()
                .eventId(1L)
                .recurrenceDate(LocalDate.of(2026, 7, 27))
                .title("Zahnarzt (verschoben)")
                .category(new CalendarCategoryView(3L, "health", "Gesundheit", "#e57373", null))
                .allDay(false)
                .recurring(true)
                .daysUntil(2)
                .build();
        when(service.updateOccurrence(eq(1L), eq(LocalDate.of(2026, 7, 27)), any(CalendarEventRequest.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/v1/calendar/events/1/occurrences/2026-07-27")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Zahnarzt (verschoben)","categoryId":3,"allDay":false,
                                 "startDate":"2026-07-27"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1))
                .andExpect(jsonPath("$.recurrenceDate").value("2026-07-27"))
                .andExpect(jsonPath("$.title").value("Zahnarzt (verschoben)"));

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(service).updateOccurrence(eq(1L), dateCaptor.capture(), any(CalendarEventRequest.class));
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    void serviceNotFoundAls404KommtBeimClientAn() throws Exception {
        when(service.getEvent(99L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Termin nicht gefunden."));

        mockMvc.perform(get("/v1/calendar/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Termin nicht gefunden."));
    }

    @Test
    void ungueltigesDatumImOccurrencePfadLiefert400() throws Exception {
        mockMvc.perform(delete("/v1/calendar/events/1/occurrences/not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOccurrencesOhneParameterLiefert400() throws Exception {
        mockMvc.perform(get("/v1/calendar/events"))
                .andExpect(status().isBadRequest());
    }
}
