package com.household.manager.service;

import com.household.manager.dto.AirrohrReadingHistoryResponse;
import com.household.manager.model.entity.AirrohrReading;
import com.household.manager.repository.AirrohrReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for querying persisted Airrohr readings.
 */
@Service
@RequiredArgsConstructor
public class AirrohrReadingService {

    private final AirrohrReadingRepository airrohrReadingRepository;

    @Transactional(readOnly = true)
    public List<AirrohrReadingHistoryResponse> getAllReadings() {
        return airrohrReadingRepository.findAll(Sort.by(Sort.Direction.DESC, "readingTime")).stream()
                .map(this::toResponse)
                .toList();
    }

    private AirrohrReadingHistoryResponse toResponse(AirrohrReading reading) {
        return AirrohrReadingHistoryResponse.builder()
                .id(reading.getId())
                .readingTime(reading.getReadingTime())
                .softwareVersion(reading.getSoftwareVersion())
                .ageSeconds(reading.getAgeSeconds())
                .sdsP1(reading.getSdsP1())
                .sdsP2(reading.getSdsP2())
                .createdAt(reading.getCreatedAt())
                .updatedAt(reading.getUpdatedAt())
                .build();
    }
}
