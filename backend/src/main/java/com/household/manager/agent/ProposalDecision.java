package com.household.manager.agent;

/**
 * Antwort auf einen Vorschlag. Die Namen stehen buchstäblich in den Telegram-Callback-Daten
 * ({@code agent:<id>:<decision>}) — umbenennen macht bereits gesendete Knöpfe wirkungslos.
 */
public enum ProposalDecision {
    APPROVE,
    REJECT,
    /** Zustimmen und die Zustimmung als Vorliebe merken, damit der Agent beim nächsten Mal weniger fragt. */
    APPROVE_AND_REMEMBER
}
