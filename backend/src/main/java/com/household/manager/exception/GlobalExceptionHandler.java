package com.household.manager.exception;

import com.household.manager.alexa.AlexaException;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.meross.exception.MerossAuthException;
import com.household.manager.meross.exception.MerossException;
import com.household.manager.nuki.NukiException;
import com.household.manager.shelly.ShellyException;
import com.household.manager.system.RebooterException;
import com.household.manager.tapo.TapoException;
import com.household.manager.tractive.TractiveAuthException;
import com.household.manager.tractive.TractiveException;
import com.household.manager.tractive.TractiveRateLimitException;
import com.household.manager.vision.VisionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the application.
 * Catches and handles exceptions thrown by controllers, providing consistent error responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle validation exceptions from @Valid annotations.
     *
     * @param ex      The validation exception
     * @param request The web request
     * @return Error response with validation details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex, WebRequest request) {

        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            validationErrors.put(fieldName, errorMessage);
        });

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("Input validation failed. Please check your request.")
                .path(request.getDescription(false).replace("uri=", ""))
                .validationErrors(validationErrors)
                .build();

        log.warn("Validation error: {}", validationErrors);
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle resource not found exceptions.
     *
     * @param ex      The resource not found exception
     * @param request The web request
     * @return Error response with 404 status
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handle duplicate entity creation (e.g. a manual helper whose name maps to
     * an already existing entity ID).
     *
     * @param ex      The duplicate entity exception
     * @param request The web request
     * @return Error response with 409 status
     */
    @ExceptionHandler(DuplicateEntityException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEntityException(
            DuplicateEntityException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Duplicate entity: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handle illegal argument exceptions.
     *
     * @param ex      The illegal argument exception
     * @param request The web request
     * @return Error response with 400 status
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(
            IllegalStateException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle request parameters that cannot be converted to the target type
     * (e.g. an invalid enum value for a {@code @RequestParam}).
     *
     * @param ex      The type mismatch exception
     * @param request The web request
     * @return Error response with 400 status
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), ex.getName()))
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Method argument type mismatch: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle missing required request parameters (e.g. a {@code from}/{@code to}
     * query parameter left off a date-range endpoint).
     *
     * @param ex      The missing-parameter exception
     * @param request The web request
     * @return Error response with 400 status
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Required parameter '%s' is missing".formatted(ex.getParameterName()))
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Missing request parameter: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle unreadable request bodies (malformed JSON or invalid enum values in
     * {@code @RequestBody} DTOs, e.g. an unknown Nuki lock action).
     *
     * @param ex      The message-not-readable exception
     * @param request The web request
     * @return Error response with 400 status
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Request body is invalid or malformed.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle device communication exceptions (e.g., local smart plug access).
     *
     * @param ex      The communication exception
     * @param request The web request
     * @return Error response with 502 status
     */
    @ExceptionHandler(KasaCommunicationException.class)
    public ResponseEntity<ErrorResponse> handleKasaCommunicationException(
            KasaCommunicationException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Kasa communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(MerossAuthException.class)
    public ResponseEntity<ErrorResponse> handleMerossAuthException(
            MerossAuthException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Meross auth error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(MerossException.class)
    public ResponseEntity<ErrorResponse> handleMerossException(
            MerossException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Meross communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(ShellyException.class)
    public ResponseEntity<ErrorResponse> handleShellyException(
            ShellyException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Shelly communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(TapoException.class)
    public ResponseEntity<ErrorResponse> handleTapoException(
            TapoException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Tapo communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(NukiException.class)
    public ResponseEntity<ErrorResponse> handleNukiException(
            NukiException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Nuki communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(TractiveAuthException.class)
    public ResponseEntity<ErrorResponse> handleTractiveAuthException(
            TractiveAuthException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Tractive auth error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Muss VOR dem TractiveException-Handler stehen (Spring waehlt sonst zwar ohnehin den
     * spezifischeren, aber die Reihenfolge macht die Absicht sichtbar): ein Rate-Limit ist
     * kein Cloud-Ausfall, sondern eine Bitte um Warten — 429 statt 502.
     */
    @ExceptionHandler(TractiveRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleTractiveRateLimitException(
            TractiveRateLimitException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .error("Too Many Requests")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Tractive rate limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
    }

    @ExceptionHandler(TractiveException.class)
    public ResponseEntity<ErrorResponse> handleTractiveException(
            TractiveException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Tractive communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    /**
     * Handle Alexa integration errors (login/session failures, unreachable
     * inofficial endpoints, invalid announcement requests).
     *
     * @param ex      The Alexa exception
     * @param request The web request
     * @return Error response with 502 status carrying the original message
     */
    @ExceptionHandler(AlexaException.class)
    public ResponseEntity<ErrorResponse> handleAlexaException(
            AlexaException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Alexa communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    /**
     * Handle Vision integration errors (blink-vision-Sidecar nicht erreichbar,
     * Login-/Foto-Probleme bei der Gesichtserkennung).
     *
     * @param ex      The vision exception
     * @param request The web request
     * @return Error response with 502 status carrying the original message
     */
    @ExceptionHandler(VisionException.class)
    public ResponseEntity<ErrorResponse> handleVisionException(
            VisionException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Vision communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    /**
     * Handle Rebooter integration errors (Rebooter-Sidecar nicht erreichbar
     * oder unerwartete Antwort beim System-Neustart).
     *
     * @param ex      The rebooter exception
     * @param request The web request
     * @return Error response with 502 status carrying the original message
     */
    @ExceptionHandler(RebooterException.class)
    public ResponseEntity<ErrorResponse> handleRebooterException(
            RebooterException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Rebooter communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    /**
     * Handle {@link ResponseStatusException} thrown directly by controllers for
     * lightweight input validation (e.g. Muellabfuhr-Settings).
     * <p>
     * Without this handler, the catch-all {@link #handleGlobalException} below would
     * intercept it first (it matches {@code Exception.class}, and the
     * {@code ExceptionHandlerExceptionResolver} that runs {@code @RestControllerAdvice}
     * methods is tried before Spring's built-in {@code ResponseStatusExceptionResolver}),
     * silently turning a deliberate 400 with a useful message into a generic 500.
     *
     * @param ex      The response status exception
     * @param request The web request
     * @return Error response using the status and reason carried by the exception
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex, WebRequest request) {

        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(ex.getStatusCode().value())
                .error(status != null ? status.getReasonPhrase() : "Error")
                .message(ex.getReason())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Response status exception: {}", ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(errorResponse);
    }

    /**
     * Handle client disconnects on streaming (SSE) responses.
     * <p>
     * These occur normally whenever a browser closes a live stream (e.g. the user
     * navigates away). The client is already gone, so there is nothing to send back
     * and no body can be written to a {@code text/event-stream} response. Log at debug
     * instead of polluting the logs with stack traces. Returning {@code void} prevents
     * Spring from attempting to serialize an error body.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleStreamingClientDisconnect(Exception ex) {
        log.debug("Streaming client disconnected: {}", ex.getMessage());
    }

    /**
     * AccessDeniedException aus Methoden-/Controller-Pruefungen (z. B. Nuki-KIOSK-Regel).
     * Ohne diesen Handler wuerde der Catch-all unten daraus einen 500 machen.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Keine Berechtigung fuer diese Aktion.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Fehlgeschlagene Logins (AuthController wirft die AuthenticationException weiter).
     * Bewusst unspezifische Meldung — kein User-Enumeration-Leak.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            org.springframework.security.core.AuthenticationException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Benutzername oder Passwort falsch.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Handle all other unhandled exceptions.
     *
     * @param ex      The exception
     * @param request The web request
     * @return Error response with 500 status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(
            Exception ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred. Please try again later.")
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
