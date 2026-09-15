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
import java.util.Map;

/**
 * Liefert die Eintraege der Modus-Leiste in Katalog-Reihenfolge.
 *
 * <p><b>Einzige Definition von „steht in der Modus-Leiste"</b> (Muster
 * {@code TractiveHomeResolver}): Dashboard, Telegram-Tools und der Schnellzugriff
 * ({@code ModeQuickAccessResolver}, {@code ModeQuickAccessService}) fragen alle diese
 * Methode, damit ein Helfer nie im Schnellzugriff steht, ohne in der Leiste zu sein.
 *
 * <p>Welche Helfer drin sind, entscheidet die Kachel-Sichtbarkeit fuer
 * {@link DashboardTiles#MODES}: ohne Regel (AUTO) die Katalog-Modi mit Marker
 * {@code mode}; ALWAYS holt einen gewoehnlichen Helfer dazu, NEVER nimmt einen Modus
 * heraus, WHEN_ON zeigt ihn nur solange er an ist.
 */
@Service
@RequiredArgsConstructor
public class HouseModeQueryService {

    private static final String STATE_ON = "on";

    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;
    private final ModeResponseMapper modeResponseMapper;
    private final EntityTileVisibilityService tileVisibilityService;

    @Transactional(readOnly = true)
    public List<ModeResponse> listModes() {
        Map<String, TileVisibility> rules = tileVisibilityService.tileRules(DashboardTiles.MODES);
        return entityStateRepository
                .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                .stream()
                .filter(entity -> inBar(entity, rules))
                .sorted(Comparator.comparingInt(this::catalogIndex))
                .map(modeResponseMapper::toResponse)
                .toList();
    }

    private boolean inBar(EntityState entity, Map<String, TileVisibility> rules) {
        return switch (rules.getOrDefault(entity.getEntityId(), TileVisibility.AUTO)) {
            case ALWAYS -> true;
            case NEVER -> false;
            case WHEN_ON -> STATE_ON.equals(entity.getState());
            case AUTO -> HouseModes.isMode(
                    entityStateResponseMapper.parseAttributes(entity.getAttributes()));
        };
    }

    /**
     * Katalog-Position eines Modus; unbekannte Marker-Entities und hinzugeholte Helfer
     * landen dahinter (die stabile Sortierung erhält deren alphabetische
     * Repository-Reihenfolge).
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
