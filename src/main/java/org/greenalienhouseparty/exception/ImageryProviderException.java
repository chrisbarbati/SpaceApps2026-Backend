package org.greenalienhouseparty.exception;

/**
 * Signals that a satellite image could not be fetched from the imagery provider
 * or was not a usable image, reported to the caller as HTTP 502.
 */
public class ImageryProviderException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message explanation returned to the caller as {@code detail}
     */
    public ImageryProviderException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its underlying cause.
     *
     * @param message explanation returned to the caller as {@code detail}
     * @param cause   the transport failure
     */
    public ImageryProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
