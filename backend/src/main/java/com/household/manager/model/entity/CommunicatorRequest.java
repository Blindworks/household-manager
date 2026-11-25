package com.household.manager.model.entity;

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

/** Protokoll einer Communicator-Anfrage: Kennzahlen als Spalten, Verlauf und Karten als JSON in {@code details}. */
@Entity
@Table(name = "communicator_request")
@Getter
@Setter
@NoArgsConstructor
public class CommunicatorRequest {

    public enum Status {
        OK,
        /** Spracherkennung lief, lieferte aber keinen Text. */
        NOT_UNDERSTOOD,
        SPEECH_ERROR,
        /** Whisper kennt das Modell (noch) nicht. */
        MODEL_MISSING,
        AI_ERROR,
        /** Runden oder Zeit aufgebraucht, ohne dass eine Karte entstand. */
        INCOMPLETE,
        /** Abgeschaltet oder nicht eingerichtet. */
        UNAVAILABLE,
        /** Aufnahme leer oder zu gross. */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asked_at", nullable = false)
    private LocalDateTime askedAt;

    @Column(length = 100)
    private String actor;

    @Column(nullable = false)
    private boolean voice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 1000)
    private String question;

    @Column(length = 500)
    private String error;

    @Column(length = 100)
    private String model;

    @Column(name = "audio_bytes")
    private Integer audioBytes;

    @Column(name = "audio_type", length = 100)
    private String audioType;

    @Column(name = "audio_ms")
    private Integer audioMs;

    @Column(name = "speech_ms")
    private Integer speechMs;

    @Column(name = "total_ms", nullable = false)
    private int totalMs;

    @Column(name = "ai_calls", nullable = false)
    private int aiCalls;

    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    @Column(name = "tool_failures", nullable = false)
    private int toolFailures;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private int cacheReadTokens;

    @Column(name = "cache_write_tokens", nullable = false)
    private int cacheWriteTokens;

    /** Gelesene Endpunkte (Pfadmuster), eine Zeile je Aufruf. */
    @Column(length = 2000)
    private String endpoints;

    @Column(columnDefinition = "LONGTEXT")
    private String details;
}
