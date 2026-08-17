package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for manually adding a Kasa device by IP address.
 * <p>
 * Used when UDP broadcast discovery cannot reach the device (e.g. the backend running
 * inside a Docker bridge network) but a direct TCP connection to the device works.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KasaManualAddRequest {

    /**
     * IPv4 address of the Kasa device to probe and persist.
     * <p>
     * Octets must not carry leading zeros (e.g. "010"): {@link java.net.InetSocketAddress}
     * refuses to parse those as a literal on JDK 21, so a lax pattern would let a value like
     * "010.1.1.1" through validation only for {@code KasaTcpClient} to treat it as a hostname
     * and attempt a slow DNS lookup, turning what should be a crisp 400 into a confusing 502.
     */
    @NotBlank(message = "IP address is required")
    @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$",
            message = "IP address must be a valid IPv4 address"
    )
    private String ip;
}
