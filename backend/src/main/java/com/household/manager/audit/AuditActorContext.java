package com.household.manager.audit;

/**
 * ThreadLocal-Override fuer Aktoren ohne SecurityContext (z. B. der
 * Telegram-Bot). Muss im finally-Block wieder geleert werden.
 */
public final class AuditActorContext {

    private static final ThreadLocal<AuditActor> CURRENT = new ThreadLocal<>();

    private AuditActorContext() {
    }

    public static void set(AuditActor actor) {
        CURRENT.set(actor);
    }

    public static AuditActor get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
