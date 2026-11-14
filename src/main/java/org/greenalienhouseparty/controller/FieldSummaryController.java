package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Field environmental summary endpoint, re-exposed from the data backend.
 */
@RestController
@RequestMapping("/field-summary")
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
    @PostMapping
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
}
