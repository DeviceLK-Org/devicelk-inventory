package com.devicelk.inventory.exception;

/**
 * S3 or Bedrock could not be reached, or refused the operation.
 * <p>
 * Translated into <b>502 Bad Gateway</b> rather than 500. The distinction is not
 * cosmetic: 500 sends whoever is reading the logs looking for a bug in this
 * service, while 502 says the failure is in a dependency it calls out to —
 * usually credentials, IAM policy, or an AWS outage.
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
