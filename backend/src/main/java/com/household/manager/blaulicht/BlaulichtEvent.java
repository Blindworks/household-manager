package com.household.manager.blaulicht;

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

import java.time.LocalDateTime;

/** Ein einzelner Fall (eine Sammelmeldung ergibt mehrere Zeilen). Zeiten sind Haushaltszeit (Europe/Berlin). */
@Entity
@Table(name = "blaulicht_event")
@Getter
@Setter
@NoArgsConstructor
public class BlaulichtEvent {

    public static final int TITLE_MAX = 300;
    public static final int LOCALITY_MAX = 100;
    public static final int URL_MAX = 500;
    public static final int EXTERNAL_ID_MAX = 191;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BlaulichtSourceKey source;

    /** GUID der Quelle, bei Sammelmeldungen mit {@code #<n>} je Fall. */
    @Column(name = "external_id", nullable = false, length = EXTERNAL_ID_MAX)
    private String externalId;

    /** SHA-256 aus normalisiertem Inhalt und Ort; fängt dieselbe Meldung unter neuer GUID. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BlaulichtCategory category;

    @Column(length = LOCALITY_MAX)
    private String locality;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Tatzeit (bei einem Zeitraum dessen Beginn), sonst null. */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    /** Ende des Tatzeitraums („von … bis …“), sonst null. */
    @Column(name = "occurred_until")
    private LocalDateTime occurredUntil;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(length = URL_MAX)
    private String url;

    @Column(nullable = false)
    private int relevance;

    /** Nachbarort statt Bad Vilbel/Ortsteil (zählt im Briefing nur als „Dazu n Meldungen aus Nachbarorten“). */
    @Column(nullable = false)
    private boolean nearby;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
