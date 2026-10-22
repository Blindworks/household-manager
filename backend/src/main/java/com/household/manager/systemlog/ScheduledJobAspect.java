package com.household.manager.systemlog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Umschliesst jede {@code @Scheduled}-Methode und meldet den Ausgang an den {@link SystemJobMonitor}.
 * Darf das Verhalten des Jobs nie aendern: Rueckgabewert und Exception gehen unveraendert durch,
 * ein Fehler im Monitor selbst wird verschluckt.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledJobAspect {

    private static final String CGLIB_MARKER = "$$";

    private final SystemJobMonitor monitor;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object monitorJob(ProceedingJoinPoint joinPoint) throws Throwable {
        SystemJobMonitor.RunContext run = enter(joinPoint);
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            exit(run, ex);
            throw ex;
        }
        exit(run, null);
        return result;
    }

    private SystemJobMonitor.RunContext enter(ProceedingJoinPoint joinPoint) {
        try {
            Object target = joinPoint.getTarget();
            Class<?> type = target != null ? target.getClass() : joinPoint.getSignature().getDeclaringType();
            return monitor.beginRun(jobKey(type, joinPoint.getSignature().getName()));
        } catch (Exception ex) {
            log.debug("Job-Monitor: Laufbeginn nicht erfasst", ex);
            return null;
        }
    }

    private void exit(SystemJobMonitor.RunContext run, Throwable failure) {
        try {
            monitor.endRun(run, failure);
        } catch (Exception ex) {
            log.debug("Job-Monitor: Laufende nicht erfasst", ex);
        }
    }

    static String jobKey(Class<?> type, String methodName) {
        String name = type.getSimpleName();
        int marker = name.indexOf(CGLIB_MARKER);
        if (marker > 0) {
            name = name.substring(0, marker);
        }
        return name + "." + methodName;
    }
}
