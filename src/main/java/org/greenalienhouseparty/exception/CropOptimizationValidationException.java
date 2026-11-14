package org.greenalienhouseparty.exception;

/**
 * Indicates crop-planning input that does not match the supported application
 * contract.
 */
public class CropOptimizationValidationException extends RuntimeException {

    /**
     * Creates a validation failure with a client-facing explanation.
     *
     * @param message explanation of the invalid planning input
     */
    public CropOptimizationValidationException(String message) {
        super(message);
    }
}
