package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.service.CropOptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Java-owned endpoint for generating five-year crop-rotation placeholders.
 */
@RestController
@Tag(name = "Crop Planning", description = "Crop choices and temporary crop-rotation plans")
public class CropOptimizationController {

    private static final Logger log = LoggerFactory.getLogger(CropOptimizationController.class);

    private final CropOptimizationService service;

    /**
     * Creates the controller.
     *
     * @param service crop catalog and planning service
     */
    public CropOptimizationController(CropOptimizationService service) {
        this.service = service;
    }

    /**
     * Creates a five-year temporary plan for each submitted field.
     *
     * @param request selected fields, current crops, growable crops and optional
     *                planting-ready dates
     * @return five future crop assignments for every field
     */
    @PostMapping("/crop-optimization")
    @Operation(summary = "Generate five-year crop plans",
            description = "Returns a random placeholder plan from each farmer's growable crops. "
                    + "The result is not an agronomic recommendation and must be reviewed locally.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Temporary five-year plans were created."),
            @ApiResponse(responseCode = "422", description = "Missing field data or unsupported crop choices.")
    })
    public CropOptimizationResponse optimize(@RequestBody CropOptimizationRequest request) {
        log.trace("Entering optimize()");
        log.info("Handling POST /crop-optimization");
        try {
            return service.optimize(request);
        } finally {
            log.trace("Exiting optimize()");
        }
    }
}
