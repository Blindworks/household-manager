package com.household.manager.entitystate;

import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Baut den Leistungsverlauf eines Verbrauchers fuer den Graph-Dialog.
 */
@Service
@RequiredArgsConstructor
public class PowerHistoryService {

    private final EntityPowerHistoryRepository historyRepository;
    private final PowerConsumerQueryService consumerQueryService;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /**
     * @return der Verlauf, oder leer wenn die entityId unbekannt ist oder nicht
     *         zu einem Verbraucher gehoert (der Controller macht daraus ein 404)
     */
    @Transactional(readOnly = true)
    public Optional<PowerHistoryResponse> getHistory(String entityId, PowerRange range) {
        return consumerQueryService.findConsumer(entityId).map(entity -> {
            LocalDateTime now = LocalDateTime.now();
            List<TimeValue> points = historyRepository
                    .findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                            entityId, now.minusDays(range.getDays()), now)
                    .stream()
                    .map(this::toPoint)
                    .toList();
            return new PowerHistoryResponse(
                    entity.getEntityId(),
                    entityStateResponseMapper.displayName(entity),
                    points);
        });
    }

    /** null-Leistung bleibt null: der Graph zeigt dort eine Luecke. */
    private TimeValue toPoint(EntityPowerHistory history) {
        return TimeValue.builder()
                .time(history.getMeasuredAt())
                .value(history.getPowerWatts() == null
                        ? null
                        : BigDecimal.valueOf(history.getPowerWatts()))
                .build();
    }
}
