package com.household.manager.audit;

/**
 * Aktor-Art für den Filter des Audit-Logs. Feiner als {@link com.household.manager.model.entity.AuditActorType}:
 * Flows und der Hausverstand schreiben als SYSTEM, sind für die Suche aber eigene Arten.
 */
public enum AuditActorKind {
    PERSON,
    SERVICE,
    FLOW,
    AGENT,
    TELEGRAM,
    SYSTEM
}
