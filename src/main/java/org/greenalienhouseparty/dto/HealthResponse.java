package org.greenalienhouseparty.dto;

/**
 * Response payload of the data backend's {@code GET /health} liveness check.
 *
 * @param status the reported service status
 */
public record HealthResponse(String status) {
}
