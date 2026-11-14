package org.greenalienhouseparty.dto;

/**
 * Readiness result from the data backend, including the field data check.
 *
 * @param ready  whether the service can answer field requests
 * @param checks the individual readiness checks and configured field source
 */
public record ReadinessResponse(Boolean ready, ReadinessChecks checks) {

    /**
     * Details the field-data readiness check and its configured source.
     *
     * @param fields the status of the configured field data
     * @param source the configured source type, such as {@code database} or {@code geojson}
     */
    public record ReadinessChecks(String fields, String source) {
    }
}
