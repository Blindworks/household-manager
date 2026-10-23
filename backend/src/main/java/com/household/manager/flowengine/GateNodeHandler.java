package com.household.manager.flowengine;

/**
 * Durchlass-Node (z. B. Rate-Limit): {@link NodeResult#none()} heisst, die Message wurde
 * verworfen (im Systemprotokoll {@code RATE_LIMITED}).
 */
public interface GateNodeHandler extends NodeHandler {
}
