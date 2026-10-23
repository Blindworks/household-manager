package com.household.manager.flowengine;

/**
 * Bedingungs-Node: gibt sie auf einen Port ohne Ziele aus, endet der Lauf dort bewusst
 * (im Systemprotokoll {@code STOPPED}) statt wie eine Aktion ohne Nachfolger.
 */
public interface ConditionNodeHandler extends NodeHandler {
}
