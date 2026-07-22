package com.household.manager.vision;

/** Fachlicher Fehler der Vision-Integration (Sidecar nicht erreichbar, Foto unbrauchbar, ...). */
public class VisionException extends RuntimeException {

    public VisionException(String message) {
        super(message);
    }

    public VisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
