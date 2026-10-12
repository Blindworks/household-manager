package com.household.manager.model.entity;

import com.household.manager.agent.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
/**
 * Freigabe-Richtlinie je Aktionsklasse. Fehlt eine Zeile, gilt PROPOSE (fail-safe).
 * Suffix "Entity", weil {@link ActionPolicy} bereits der Enum-Name ist.
 */
@Entity
@Table(name = "agent_action_policy")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentActionPolicyEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "action_class", nullable = false, length = 20)
    private ActionClass actionClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionPolicy policy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}