package com.household.manager.dirigera;

import java.util.List;

/** Ergebnis von {@code GET /v1/devices}: Hub-Id (null, falls nicht gelistet) und die Sensoren. */
public record DirigeraDevices(String gatewayId, List<DirigeraSensorSnapshot> sensors) {
}
