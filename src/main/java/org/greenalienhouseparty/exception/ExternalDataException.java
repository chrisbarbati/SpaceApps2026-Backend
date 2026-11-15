package org.greenalienhouseparty.exception;

/**
 * Raised when an external data source other than the Python data backend
 * cannot be reached or returns something unusable.
 */
public class ExternalDataException extends RuntimeException {

    private final int statusCode;

    /**
     * Creates the exception.
     *
     * @param statusCode HTTP status to answer with, normally 502
     * @param message    explanation safe to show to the caller
     */
    public ExternalDataException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * @return HTTP status to answer with
     */
    public int getStatusCode() {
        return statusCode;
    }
}
