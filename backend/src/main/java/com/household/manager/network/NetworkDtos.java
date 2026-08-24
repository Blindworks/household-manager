package com.household.manager.network;

import com.household.manager.model.entity.NetworkDevice;

/** Request-/Response-Records der Netzwerk-Monitoring-API. */
public final class NetworkDtos {

    private NetworkDtos() {
    }

    public record DeviceRequest(String name, String host, Integer tcpPort, Integer sortOrder, Boolean active) {
    }

    public record DeviceAdminResponse(Long id, String name, String host, Integer tcpPort,
                                       int sortOrder, boolean active) {
        public static DeviceAdminResponse from(NetworkDevice device) {
            return new DeviceAdminResponse(device.getId(), device.getName(), device.getHost(),
                    device.getTcpPort(), device.getSortOrder(), device.isActive());
        }
    }
}
