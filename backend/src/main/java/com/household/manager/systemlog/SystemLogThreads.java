package com.household.manager.systemlog;

import java.util.Set;

/**
 * Namen der Threads, die das Systemprotokoll selbst betreibt. Der Log-Appender ignoriert alles, was auf ihnen
 * geloggt wird: faellt die DB aus, wuerden deren Warnungen (Hibernate/Pool) sonst als neue Protokollzeilen
 * in dieselbe Queue zurueckfliessen.
 */
public final class SystemLogThreads {

    public static final String WRITER = "system-log-writer";
    public static final String JOB_MONITOR = "system-job-monitor";
    public static final String PENDING_RUNS = "flow-pending-runs";

    private static final Set<String> OWN = Set.of(WRITER, JOB_MONITOR, PENDING_RUNS);

    private SystemLogThreads() {
    }

    public static boolean isOwnThread(String threadName) {
        return threadName != null && OWN.contains(threadName);
    }
}
