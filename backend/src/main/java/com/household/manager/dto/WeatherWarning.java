package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Eine amtliche DWD-Wetterwarnung. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherWarning {

    private Long warnId;
    private String event;
    private Integer level;
    private String headline;
    private String description;
    private String instruction;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime start;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime end;
}
