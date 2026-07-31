package com.household.manager.service;

import com.household.manager.dto.TimeValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mittelt eine Messreihe auf feste Zeit-Buckets herunter, damit der Verlaufsgraph
 * eines einzelnen Sensors auch über 30 Tage eine übertragbare und flüssig zeichenbare
 * Punktzahl hat.
 *
 * <p>Bewusst quellen-agnostisch: die Klasse kennt weder Zigbee noch Wetter noch Alexa
 * und ist dadurch ohne Datenbank testbar.
 *
 * <p>Leere Buckets werden ausgelassen statt mit Nullen gefüllt. Eine Funkpause ist bei
 * Temperatursensoren der Normalfall — sie melden nur bei Wertänderung — und darf nicht
 * wie ein Messausfall aussehen.
 */
@Component
public class TemperatureSeriesDownsampler {

    /** Nachkommastellen des gemittelten Werts; mehr täuscht eine Genauigkeit vor, die die Sensoren nicht haben. */
    private static final int SCALE = 2;

    public List<TimeValue> downsample(List<TimeValue> points, TemperatureRange range) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        long bucketSeconds = range.getBucketSeconds();

        Map<LocalDateTime, List<BigDecimal>> buckets = new LinkedHashMap<>();
        for (TimeValue point : points) {
            if (point.getTime() == null || point.getValue() == null) {
                continue;
            }
            buckets.computeIfAbsent(bucketStart(point.getTime(), bucketSeconds), key -> new ArrayList<>())
                    .add(point.getValue());
        }

        List<TimeValue> result = new ArrayList<>(buckets.size());
        buckets.forEach((start, values) -> result.add(TimeValue.builder()
                .time(start)
                .value(average(values))
                .build()));
        result.sort(java.util.Comparator.comparing(TimeValue::getTime));
        return result;
    }

    /**
     * Bucket-Anfang per Abrunden auf ein Vielfaches der Bucket-Länge. Ein Punkt exakt auf
     * der Grenze beginnt damit den folgenden Bucket. {@code floorDiv} statt {@code /},
     * damit Zeitpunkte vor der Epoche nicht in den falschen Bucket kippen.
     */
    private LocalDateTime bucketStart(LocalDateTime time, long bucketSeconds) {
        long seconds = time.toEpochSecond(ZoneOffset.UTC);
        long start = Math.floorDiv(seconds, bucketSeconds) * bucketSeconds;
        return LocalDateTime.ofEpochSecond(start, 0, ZoneOffset.UTC);
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }
}
