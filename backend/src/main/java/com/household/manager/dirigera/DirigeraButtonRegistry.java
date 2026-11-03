package com.household.manager.dirigera;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Die Taster des Hubs aus dem letzten erfolgreichen Poll, nur im Speicher. Ein Tastendruck traegt
 * nur die Geraete-Id; Name, Raum und Taste kommen von hier. Nach einem Neustart ist die Liste bis
 * zum ersten Poll leer, Druecke werden dann mit der Geraete-Id als Namen gemeldet.
 */
@Component
public class DirigeraButtonRegistry {

    private volatile Map<String, DirigeraButton> buttons = Map.of();

    public void replaceAll(List<DirigeraButton> current) {
        buttons = current.stream()
                .collect(Collectors.toUnmodifiableMap(DirigeraButton::deviceId, Function.identity(), (a, b) -> a));
    }

    public Optional<DirigeraButton> find(String deviceId) {
        return Optional.ofNullable(buttons.get(deviceId));
    }
}
