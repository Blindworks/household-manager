package com.household.manager.exception;

import com.household.manager.alexa.AlexaException;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.meross.exception.MerossAuthException;
import com.household.manager.meross.exception.MerossException;
import com.household.manager.shelly.ShellyException;
import com.household.manager.tapo.TapoException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

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
