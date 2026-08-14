package com.devicelk.inventory.exception;

/**
 * The supplied file or document key is not usable as a product spec document.
 * <p>
 * Deliberately <b>not</b> an {@link IllegalArgumentException}. This module's
 * {@link GlobalExceptionHandler} already maps that type to <b>409 Conflict</b>
 * (it signals a duplicate name/brand or a stock-floor breach), so reusing it for
 * a bad filename would show an admin a "conflict" for what is plainly a
 * validation error, and would tell the admin portal to branch the wrong way.
 * Translated into HTTP 400.
 */
public class InvalidDocumentException extends RuntimeException {

    public InvalidDocumentException(String message) {
        super(message);
    }
}
