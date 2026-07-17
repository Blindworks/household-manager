package com.household.manager.entitystate;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Liefert die schaltbaren Entitäten, meistgenutzte zuerst.
 */
@Service
@RequiredArgsConstructor
public class SwitchQueryService {

    private final EntityStateRepository entityStateRepository;
    private final EntityUsageService entityUsageService;
    private final SwitchResponseMapper switchResponseMapper;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /**
     * @param limit maximale Anzahl Einträge; null oder <= 0 liefert alle
     */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit) {
        List<EntityState> switchable = entityStateRepository
                .findByDomainInOrderByEntityIdAsc(SwitchableEntities.SWITCHABLE_DOMAINS).stream()
                .filter(SwitchableEntities::isSwitchable)
                // Haus-Modi haben eine eigene Leiste im Dashboard und die Modus-API.
                .filter(entity -> !HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .toList();

        Map<String, EntityUsage> usage = entityUsageService.usageFor(
                switchable.stream().map(EntityState::getEntityId).toList());

        List<SwitchResponse> switches = switchable.stream()
                .map(entity -> switchResponseMapper.toResponse(entity, usage.get(entity.getEntityId())))
                .sorted(byUsage())
                .toList();

        if (limit != null && limit > 0 && limit < switches.size()) {
            return List.copyOf(switches.subList(0, limit));
        }
        return switches;
    }

    /** Meistgenutzt zuerst; bei Gleichstand zuletzt geschaltet, dann alphabetisch. */
    private Comparator<SwitchResponse> byUsage() {
        Comparator<SwitchResponse> byCount = Comparator.comparingLong(SwitchResponse::toggleCount);
        return byCount.reversed()
                .thenComparing(SwitchResponse::lastToggledAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(response -> response.displayName().toLowerCase(Locale.ROOT));
    }
}
