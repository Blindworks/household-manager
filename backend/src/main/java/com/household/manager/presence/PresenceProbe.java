package com.household.manager.presence;

import java.time.Duration;
import java.util.List;

/**
 * Prueft, ob ein Host im WLAN auf TCP ueberhaupt reagiert. Anders als
 * {@code TcpPortProbe} unterscheidet diese Probe "abgelehnt" von "still":
 * ein RST (Connection refused) beweist Anwesenheit genauso wie ein offener Port.
 */
public interface PresenceProbe {

    /**
     * @param timeoutPerPort muss positiv sein und deutlich unter dem Kernel-Connect-Timeout
     *                       liegen (Sekundenbereich): 0 hiesse "unendlich", und ein Timeout
     *                       oberhalb des SYN-Retry-Fensters wuerde einen OS-Timeout als
     *                       ConnectException und damit faelschlich als Antwort werten
     */
    ProbeResult probe(String host, List<Integer> ports, Duration timeoutPerPort);
}
