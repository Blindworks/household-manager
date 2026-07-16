package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Ein Abholtermin, wie ihn Kachel und Einstellungsseite anzeigen. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WasteCollectionEventResponse {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private String label;

    /** 0 = heute, 1 = morgen. Serverseitig berechnet, damit die Kachel nicht rechnen muss. */
    private long daysUntil;
}
