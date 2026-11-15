package com.household.manager.grocery.entity;

import com.household.manager.grocery.Chain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Ein Markt einer Kette; nur gewählte Märkte werden beobachtet. */
@Entity
@Table(name = "grocery_market")
@Getter
@Setter
@NoArgsConstructor
public class GroceryMarket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Chain chain;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String address;

    private Double lat;

    private Double lng;

    @Column(length = 40)
    private String region;

    @Column(nullable = false)
    private boolean selected;
}
