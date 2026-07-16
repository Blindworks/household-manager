package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Legt fest, welche Entitäten über die Schalter-API schaltbar sind.
 * <p>
 * Liste und Toggle nutzen dieselbe Regel, damit nie ein Schalter angeboten wird,
 * den der Toggle anschließend ablehnen würde.
 */
public final class SwitchableEntities {

    /** Quellen, deren SWITCH-Entitäten auf ein {@code SmartDevice} abbilden. */
    static final Set<EntitySource> DEVICE_SOURCES =
            EnumSet.of(EntitySource.KASA, EntitySource.TAPO, EntitySource.MEROSS);

    /** Domains, die überhaupt schaltbar sein können — Vorfilter für die Abfrage. */
    public static final List<EntityDomain> SWITCHABLE_DOMAINS =
            List.of(EntityDomain.SWITCH, EntityDomain.INPUT_BOOLEAN);

    private SwitchableEntities() {
    }

    public static boolean isSwitchable(EntityState entity) {
        return switch (entity.getDomain()) {
            case SWITCH -> DEVICE_SOURCES.contains(entity.getSource());
            case INPUT_BOOLEAN -> entity.getSource() == EntitySource.MANUAL;
            default -> false;
        };
    }
}
