package org.greenalienhouseparty.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.greenalienhouseparty.client.DataBackendException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;

/**
 * Translates data backend failures into HTTP responses.
 *
 * <p>The upstream status code is propagated and the upstream {@code detail}
 * value is preserved, so validation and provider errors are returned to the
 * frontend in the same shape the data backend produced them.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles {@link DataBackendException} by re-emitting the upstream status
     * code and detail.
     *
     * @param exception the data backend exception
     * @return a response with the upstream status and detail
     */
    @ExceptionHandler(DataBackendException.class)
    public ResponseEntity<Map<String, Object>> handleDataBackendException(DataBackendException exception) {
        log.trace("Entering handleDataBackendException()");
        log.error("Handling data backend failure with HTTP status {}", exception.getStatusCode());
        Map<String, Object> body = new LinkedHashMap<>();
        JsonNode detail = exception.getDetail();
        body.put("detail", detail != null ? detail : exception.getMessage());
        try {
            return ResponseEntity.status(exception.getStatusCode()).body(body);
        } finally {
            log.trace("Exiting handleDataBackendException()");
        }
    }

    /**
     * Translates crop planning validation failures into the API's standard 422
     * error response.
     *
     * @param exception the invalid crop-planning request
     * @return an unprocessable-entity response with a detail message
     */
    @ExceptionHandler(CropOptimizationValidationException.class)
    public ResponseEntity<Map<String, Object>> handleCropOptimizationValidation(
            CropOptimizationValidationException exception) {
        log.trace("Entering handleCropOptimizationValidation()");
        log.error("Handling invalid crop optimization request: {}", exception.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", exception.getMessage());
        try {
            return ResponseEntity.unprocessableEntity().body(body);
        } finally {
            log.trace("Exiting handleCropOptimizationValidation()");
        }
    }

    /**
     * Translates requests rejected by Spring's HTTP binding into the 422
     * validation status used by the Python data API.
     *
     * @param exception the request binding or JSON parsing failure
     * @return an unprocessable-entity response with a detail message
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(Exception exception) {
        log.trace("Entering handleInvalidRequest()");
        log.error("Handling invalid request of type {}", exception.getClass().getSimpleName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", exception.getMessage());
        try {
            return ResponseEntity.unprocessableEntity().body(body);
        } finally {
            log.trace("Exiting handleInvalidRequest()");
        }
    }
}
