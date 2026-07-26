package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;

/** Aktor eines Audit-Eintrags. */
public record AuditActor(AuditActorType type, String name) {

    public static AuditActor user(String username) {
        return new AuditActor(AuditActorType.USER, username);
    }

    public static AuditActor service(String tokenName) {
        return new AuditActor(AuditActorType.SERVICE, tokenName);
    }

    public static AuditActor system() {
        return new AuditActor(AuditActorType.SYSTEM, "system");
    }

    public static AuditActor telegram(long chatId) {
        return new AuditActor(AuditActorType.TELEGRAM, "TELEGRAM:" + chatId);
    }

    public static AuditActor flow(long flowId) {
        return new AuditActor(AuditActorType.SYSTEM, "FLOW:" + flowId);
    }
}
