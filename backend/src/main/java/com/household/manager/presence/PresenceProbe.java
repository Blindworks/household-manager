package com.household.manager.presence;

import java.time.Duration;
import java.util.List;

/**
 * Prueft, ob ein Host im WLAN auf TCP ueberhaupt reagiert. Anders als
 * {@code TcpPortProbe} unterscheidet diese Probe "abgelehnt" von "still":
 * ein RST (Connection refused) beweist Anwesenheit genauso wie ein offener Port.
 */
public interface PresenceProbe {

    ProbeResult probe(String host, List<Integer> ports, Duration timeoutPerPort);
}
