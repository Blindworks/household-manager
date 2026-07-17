package com.household.manager.entitystate;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Liefert die Haus-Modi für die Modus-Leiste in Katalog-Reihenfolge. */
@Service
@RequiredArgsConstructor
public class HouseModeQueryService {

    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;
    private final ModeResponseMapper modeResponseMapper;

    @Transactional(readOnly = true)
    public List<ModeResponse> listModes() {
        return entityStateRepository
                .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                .stream()
                .filter(entity -> HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .sorted(Comparator.comparingInt(this::catalogIndex))
                .map(modeResponseMapper::toResponse)
                .toList();
    }

    /**
     * Katalog-Position eines Modus; unbekannte Marker-Entities landen dahinter
     * (die stabile Sortierung erhält deren alphabetische Repository-Reihenfolge).
     */
    private int catalogIndex(EntityState entity) {
        for (int i = 0; i < HouseModes.CATALOG.size(); i++) {
            if (HouseModes.entityId(HouseModes.CATALOG.get(i)).equals(entity.getEntityId())) {
                return i;
            }
        }
        return HouseModes.CATALOG.size();
    }
}
