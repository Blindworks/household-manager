package com.household.manager.network;

import com.household.manager.dto.TimeValue;
import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.model.entity.NetworkSpeedtestResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

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

    public record DeviceStatusResponse(Long id, String name, String host, boolean reachable, Instant lastSeenAt) {
    }

    public record SpeedtestSummary(LocalDateTime testedAt, BigDecimal downloadMbps, BigDecimal uploadMbps,
                                    boolean success, String errorMessage) {
        public static SpeedtestSummary from(NetworkSpeedtestResult result) {
            return new SpeedtestSummary(result.getTestedAt(), result.getDownloadMbps(), result.getUploadMbps(),
                    result.isSuccess(), result.getErrorMessage());
        }
    }

    public record StatusResponse(boolean online, Integer latencyMs, boolean gatewayReachable,
                                  LocalDateTime lastCheckedAt, SpeedtestSummary lastSpeedtest,
                                  List<DeviceStatusResponse> devices) {
    }

    public record SpeedtestPoint(LocalDateTime time, BigDecimal downloadMbps, BigDecimal uploadMbps) {
    }

    public record HistoryResponse(List<TimeValue> latency, List<SpeedtestPoint> speedtests) {
    }
}
