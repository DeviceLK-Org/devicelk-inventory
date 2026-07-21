package com.devicelk.inventory.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Centralised exception handling for the REST layer. Converts domain and
 * validation exceptions into consistent JSON error responses so controllers
 * stay free of try/catch boilerplate.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Maps a missing product to HTTP 404 Not Found. */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(ProductNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", "Not Found");
        body.put("message", ex.getMessage());
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    /**
     * Maps a product whose stock row is absent to HTTP 404 Not Found.
     * <p>
     * Same status as {@link ProductNotFoundException} — the requested resource
     * genuinely is not there, and holding the status steady keeps the existing
     * client contract — but with a message that names the real problem.
     */
    @ExceptionHandler(StockNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStockNotFound(StockNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.NOT_FOUND.value());
        body.put("error", "Not Found");
        body.put("message", ex.getMessage());
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    /**
     * Maps a lost optimistic-lock race on {@code Stock} to HTTP 409 Conflict.
     * <p>
     * Two concurrent adjustments to the same product make the second one fail
     * its version check. That is a retryable conflict, not a server fault, so it
     * must not surface as a 500 — the caller can simply re-read and re-apply.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<String> handleConcurrentModification(ObjectOptimisticLockingFailureException ex) {
        return new ResponseEntity<>("Stock was modified concurrently, please retry.", HttpStatus.CONFLICT);
    }

    /** Maps Bean Validation failures (@Valid) to HTTP 400 Bad Request. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation Failed");
        body.put("fields", fieldErrors);
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT); // Returns HTTP 409 Conflict
    }
}
