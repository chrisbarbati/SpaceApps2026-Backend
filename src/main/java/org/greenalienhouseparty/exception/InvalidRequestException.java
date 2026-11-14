package org.greenalienhouseparty.exception;

/**
 * Signals a request this application rejects before calling the data backend,
 * reported to the caller as HTTP 422.
 */
public class InvalidRequestException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message explanation returned to the caller as {@code detail}
     */
    public InvalidRequestException(String message) {
        super(message);
    }
}
