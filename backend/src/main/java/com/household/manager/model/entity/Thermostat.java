package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ein Heizkoerperthermostat (Tapo KE100) als Kindgeraet eines Tapo-Hubs (H100).
 */
@Entity
@Table(name = "thermostat")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Thermostat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stabile Hardware-Id des Kindgeraets; Schluessel gegenueber dem Hub. */
    @Column(name = "child_device_id", nullable = false, unique = true, length = 100)
    private String childDeviceId;

    @Column(name = "hub_device_id", nullable = false, length = 100)
    private String hubDeviceId;

    /** Kommunikationsadresse des Hubs, von Hand gepflegt. */
    @Column(name = "hub_ip", length = 45)
    private String hubIp;

    /** Anzeigename (Raum). Aenderungen beruehren die Entity-Ids nicht, die haengen an der DB-Id. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
