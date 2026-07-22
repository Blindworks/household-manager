package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Historieneintrag einer Gesichtserkennung (person* null = nur Unbekannte gesehen). */
@Entity
@Table(name = "vision_recognition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionRecognition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recognized_at", nullable = false)
    private LocalDateTime recognizedAt;

    @Column(name = "person_id")
    private Long personId;

    /** Name zum Erkennungszeitpunkt (Snapshot, uebersteht Umbenennen/Loeschen). */
    @Column(name = "person_name", length = 255)
    private String personName;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "unknown_faces", nullable = false)
    private int unknownFaces;

    // columnDefinition explizit — siehe VisionPersonPhoto.photo (longblob vs. MEDIUMBLOB).
    @Lob
    @Column(name = "thumbnail", columnDefinition = "MEDIUMBLOB")
    private byte[] thumbnail;
}
