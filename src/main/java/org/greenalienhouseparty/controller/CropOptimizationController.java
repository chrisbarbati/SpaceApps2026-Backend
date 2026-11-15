package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.planning.CropOptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Java-owned endpoint for generating five-year crop-rotation plans.
 */
@RestController
@Tag(name = "Crop Planning", description = "Crop choices and five-year crop-rotation plans")
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
     * Creates a five-year plan for each submitted field with the active planner.
     *
     * @param request selected fields, current crops, growable crops and optional
     *                planting-ready dates
     * @return five future crop assignments for every field
     */
    @PostMapping("/crop-optimization")
    @Operation(summary = "Generate five-year crop plans",
            description = "Returns a five-year plan for each field from the farmer's growable crops. Give crops by "
                    + "their id from GET /crops (display names are also accepted). The planner field names the "
                    + "implementation used: random-placeholder (default) returns three distinct random rotations "
                    + "with example explanations and tradeoffs when at least two growable crops are supplied "
                    + "(one option for a single crop). The random demo accepts any nonblank current-crop answer, "
                    + "including pasture and unknown. The years field mirrors the first alternative for older clients. "
                    + "dynamic-soil-reliability optimizes soil health and crop reliability from soil, terrain, "
                    + "climate, crop-calendar, regional soil and crop-history data, and adds soil scores, a "
                    + "reliability value from 0 to 1, income, plain-language reasons for each choice and the "
                    + "assumptions made for missing data; mock-demonstration returns varied illustrative plans "
                    + "for demonstrations that are not calculated from field data. Fields are planned one after "
                    + "another and the "
                    + "optimizer can take several seconds per field the first time. "
                    + "The result is not an agronomic recommendation and must be reviewed locally.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Field geometry, current crop, growable crops, and optional planting-ready date.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CropOptimizationRequest.class),
                            examples = @ExampleObject(
                                    name = "valid-field-plan",
                                    summary = "A field with its current crop and growable crops",
                                    value = """
                                            {
                                              "fields": [
                                                {
                                                  "fieldId": "ftw-2025-17TPK_0_0-13020",
                                                  "geometry": {
                                                    "type": "Polygon",
                                                    "coordinates": [[
                                                      [-79.159, 44.349],
                                                      [-79.150, 44.349],
                                                      [-79.150, 44.355],
                                                      [-79.159, 44.355],
                                                      [-79.159, 44.349]
                                                    ]]
                                                  },
                                                  "currentCrop": "corn",
                                                  "growableCrops": ["soybeans", "wheat", "oats"],
                                                  "readyDate": "2027-04-15"
                                                }
                                              ]
                                            }
                                            """))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Five-year plans were created."),
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
