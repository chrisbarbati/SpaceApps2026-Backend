package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Seasonal temperature and precipitation outlook at a location, read by this
 * application directly from Environment and Climate Change Canada.
 */
@RestController
@Tag(name = "Seasonal outlook", description = "Chances of a warmer, cooler, wetter or drier season from CanSIPS")
public class SeasonalOutlookController {

    private static final Logger log = LoggerFactory.getLogger(SeasonalOutlookController.class);

    private final SeasonalOutlookService service;

    /**
     * Creates the controller.
     *
     * @param service seasonal outlook service
     */
    public SeasonalOutlookController(SeasonalOutlookService service) {
        this.service = service;
    }

    /**
     * Returns the outlook for the next nine months at a point.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return three consecutive three-month periods with their chances
     */
    @GetMapping("/seasonal-outlook")
    @Operation(summary = "Seasonal temperature and precipitation outlook",
            description = "Returns, for three consecutive three-month periods starting this month, the chance that "
                    + "temperature and precipitation are above, near or below normal, from the Canadian Seasonal to "
                    + "Inter-annual Prediction System (CanSIPS, about 100 km, updated monthly). With no information "
                    + "each chance is about 33%; mostLikely names a category only when it reaches 40%. Temperature "
                    + "outlooks are more reliable than precipitation outlooks. Use a field's centroid from /fields "
                    + "for lat and lon. This describes the coming season only, not individual days or later years. "
                    + "periods is empty where CanSIPS has no value. Answers are cached for 6 hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The seasonal outlook at the point."),
            @ApiResponse(responseCode = "422", description = "Missing or out-of-range coordinates."),
            @ApiResponse(responseCode = "502", description = "Environment Canada's forecast service could not be reached.")
    })
    public SeasonalOutlookResponse outlook(
            @RequestParam @Parameter(description = "Latitude in degrees", example = "44.35") double lat,
            @RequestParam @Parameter(description = "Longitude in degrees", example = "-79.16") double lon) {
        log.trace("Entering outlook()");
        log.info("Handling GET /seasonal-outlook");
        try {
            return service.outlook(lat, lon);
        } finally {
            log.trace("Exiting outlook()");
        }
    }
}
