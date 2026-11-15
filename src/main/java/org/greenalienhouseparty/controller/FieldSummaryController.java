package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Field environmental summary endpoints, re-exposed from the data backend:
 * for a posted outline and for a stored field by identifier.
 */
@RestController
@Tag(name = "Field Summary", description = "Climate, water balance, frost and terrain for a selected field")
public class FieldSummaryController {

    private static final Logger log = LoggerFactory.getLogger(FieldSummaryController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public FieldSummaryController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves an environmental summary for a selected field.
     *
     * @param request the field geometry and optional analysis parameters
     * @return the environmental summary
     */
    @PostMapping("/field-summary")
    @Operation(summary = "Climate, water balance, frost and terrain for a selected field",
            description = "Returns area, centroid, climate indicators, seasonal water balance, "
                    + "frost dates, monthly climatology, data coverage, terrain statistics and source metadata.")
    public FieldSummaryResponse summary(@RequestBody FieldSummaryRequest request) {
        log.trace("Entering summary()");
        log.info("Handling POST /field-summary");
        try {
            return service.fieldSummary(request);
        } finally {
            log.trace("Exiting summary()");
        }
    }

    /**
     * Retrieves the environmental summary of a stored field, with the default
     * years and growing season.
     *
     * @param fieldId       the field identifier
     * @param weatherSource optional weather dataset choice
     * @return the environmental summary, with the field identifier
     */
    @GetMapping("/fields/{field_id}/summary")
    @Operation(summary = "Climate, water balance, frost and terrain for a stored field",
            description = "The same summary as POST /field-summary for a field from GET /fields by its ID, with the "
                    + "default years (2015-2024) and growing season. Use POST /field-summary to choose the period "
                    + "or season.")
    public FieldSummaryResponse storedFieldSummary(
            @PathVariable("field_id") @Parameter(description = "Field identifier") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "auto (default), daymet or power") String weatherSource) {
        log.trace("Entering storedFieldSummary()");
        log.info("Handling GET /fields/{field_id}/summary");
        try {
            return service.fieldSummary(fieldId, weatherSource);
        } finally {
            log.trace("Exiting storedFieldSummary()");
        }
    }
}
