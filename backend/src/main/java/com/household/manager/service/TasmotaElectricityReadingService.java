package com.household.manager.service;

import com.household.manager.dto.TasmotaElectricityReadingRequest;
import com.household.manager.dto.TasmotaElectricityReadingResponse;
import com.household.manager.model.entity.TasmotaElectricityReading;
import com.household.manager.repository.TasmotaElectricityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing Tasmota electricity meter readings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityReadingService {

    private final TasmotaElectricityReadingRepository tasmotaReadingRepository;

    /**
     * Create a new Tasmota electricity reading.
     *
     * @param request the Tasmota electricity reading request
     * @return response containing the created reading
     */
    @Transactional
    public TasmotaElectricityReadingResponse createReading(TasmotaElectricityReadingRequest request) {
        log.info("Creating new Tasmota electricity reading at time: {}", request.getReadingTime());

        TasmotaElectricityReading reading = TasmotaElectricityReading.builder()
                .readingTime(request.getReadingTime())
                .posWirkenergieTariflos(request.getPosWirkenergieTariflos())
                .momentaneWirkleistung(request.getMomentaneWirkleistung())
                .build();

        TasmotaElectricityReading saved = tasmotaReadingRepository.save(reading);
        return toResponse(saved);
    }

    /**
     * Get all Tasmota electricity readings ordered by reading time descending.
     *
     * @return list of readings
     */
    @Transactional(readOnly = true)
    public List<TasmotaElectricityReadingResponse> getAllReadings() {
        return tasmotaReadingRepository.findAll(Sort.by(Sort.Direction.DESC, "readingTime")).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get the most recent Tasmota electricity reading.
     *
     * @return latest reading or null if none exist
     */
    @Transactional(readOnly = true)
    public TasmotaElectricityReadingResponse getLatestReading() {
        return tasmotaReadingRepository.findTopByOrderByReadingTimeDesc()
                .map(this::toResponse)
                .orElse(null);
    }

    private TasmotaElectricityReadingResponse toResponse(TasmotaElectricityReading reading) {
        return TasmotaElectricityReadingResponse.builder()
                .id(reading.getId())
                .readingTime(reading.getReadingTime())
                .posWirkenergieTariflos(reading.getPosWirkenergieTariflos())
                .momentaneWirkleistung(reading.getMomentaneWirkleistung())
                .createdAt(reading.getCreatedAt())
                .updatedAt(reading.getUpdatedAt())
                .build();
    }
}
