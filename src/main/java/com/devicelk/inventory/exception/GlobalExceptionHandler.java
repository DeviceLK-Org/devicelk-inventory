package com.devicelk.inventory.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
     * Same status as {@link ProductNotFoundException}, since the resource really
     * is not there, but with a message naming the actual problem.
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
     * The second of two concurrent adjustments fails its version check. That is a
     * retryable conflict, not a server fault, so it must not surface as a 500.
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

    /**
     * Maps a rejected spec document to HTTP 400 Bad Request.
     * <p>
     * Why {@link InvalidDocumentException} is its own type rather than an
     * {@code IllegalArgumentException}: the handler above maps that to 409, and a
     * bad filename is a validation failure, not a conflict.
     */
    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<String> handleInvalidDocument(InvalidDocumentException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Maps an oversize upload to HTTP 413 Payload Too Large.
     * <p>
     * Raised by Spring's multipart resolver before the controller runs, so the
     * ceiling in {@code spring.servlet.multipart.max-file-size} is the single
     * place the limit is enforced.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return new ResponseEntity<>("The document exceeds the 5MB upload limit.",
                HttpStatus.PAYLOAD_TOO_LARGE);
    }

    /**
     * Maps "a sync is already running" to HTTP 409 Conflict.
     * <p>
     * A conflict rather than a failure: Bedrock allows one ingestion job per data
     * source, and the job in flight will pick up whatever is waiting.
     */
    @ExceptionHandler(SyncInProgressException.class)
    public ResponseEntity<String> handleSyncInProgress(SyncInProgressException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
    }

    /**
     * Maps an AWS failure to HTTP 502 Bad Gateway.
     * <p>
     * 502, not 500: the fault is in a service this one calls out to (typically
     * IAM or credentials), not in this service's own logic.
     */
    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<String> handleStorageFailure(DocumentStorageException ex) {
        return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_GATEWAY);
    }
}
