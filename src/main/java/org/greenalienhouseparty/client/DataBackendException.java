package org.greenalienhouseparty.client;

import tools.jackson.databind.JsonNode;

/**
 * Signals that the Python data backend returned a non-successful response or
 * could not be reached at all.
 *
 * <p>The exception carries the HTTP status code to expose to the caller and an
 * optional structured {@code detail} value parsed from the upstream error body,
 * so error responses can be re-serialized faithfully instead of being flattened
 * into a generic message.</p>
 */
public class DataBackendException extends RuntimeException {

    private final int statusCode;

    private final JsonNode detail;

    /**
     * Creates an exception from an upstream status code and structured detail.
     *
     * @param statusCode the HTTP status code to expose to the caller
     * @param detail     the parsed upstream error detail, or {@code null}
     */
    public DataBackendException(int statusCode, JsonNode detail) {
        super(detail != null ? detail.toString() : "HTTP " + statusCode);
        this.statusCode = statusCode;
        this.detail = detail;
    }

    /**
     * Creates an exception from a status code and a plain message.
     *
     * @param statusCode the HTTP status code to expose to the caller
     * @param message    a human-readable description of the failure
     */
    public DataBackendException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.detail = null;
    }

    /**
     * @return the HTTP status code that should be returned to the caller
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * @return the parsed upstream error detail, or {@code null} when only a
     * plain message is available
     */
    public JsonNode getDetail() {
        return detail;
    }
}
