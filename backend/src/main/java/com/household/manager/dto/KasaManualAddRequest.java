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
     */
    @NotBlank(message = "IP address is required")
    @Pattern(
            regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$",
            message = "IP address must be a valid IPv4 address"
    )
    private String ip;
}
