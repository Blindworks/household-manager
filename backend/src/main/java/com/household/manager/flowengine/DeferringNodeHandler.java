package com.household.manager.flowengine;

/**
 * Aufschiebende Node (z. B. Delay): {@link NodeResult#none()} heisst, der Lauf wartet und
 * wird spaeter per emit fortgesetzt (im Systemprotokoll {@code WAITING}).
 */
public interface DeferringNodeHandler extends NodeHandler {
}
