package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
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
 * Land capability, drainage and soil indicators around a location, read by
 * this application directly from Agriculture and Agri-Food Canada.
 */
@RestController
@Tag(name = "Soil landscape", description = "Land capability, drainage and soil indicators from Agriculture and Agri-Food Canada")
public class SoilLandscapeController {

    private static final Logger log = LoggerFactory.getLogger(SoilLandscapeController.class);

    private final SoilLandscapeService service;

    /**
     * Creates the controller.
     *
     * @param service soil landscape service
     */
    public SoilLandscapeController(SoilLandscapeService service) {
        this.service = service;
    }

    /**
     * Returns land capability, drainage and soil indicators around a point.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return the values found around the point
     */
    @GetMapping("/soil-landscape")
    @Operation(summary = "Land capability, drainage and soil indicators around a point",
            description = "Returns, for a point in Canada: landCapability, the Canada Land Inventory class for "
                    + "agriculture (1 best to 7, or O for organic soils) with its limitations; drainage, the dominant "
                    + "drainage class of the soil landscape; and indicators, each with its newest value, rating, "
                    + "change over the record and a value per census year from 1981 to 2021: erosionRisk (tonnes per "
                    + "hectare per year, lower is better), soilCoverDays (days per year, higher is better), "
                    + "organicCarbonChange (kg of carbon per hectare per year, positive is a gain) and "
                    + "residualSoilNitrogen (kg of nitrogen per hectare, lower is better). Source maps are regional "
                    + "(1:250,000 and 1:1,000,000) and the indicators are modelled from census farming practices, so "
                    + "this is context for the area, not a measurement of the field. Use a field's centroid from "
                    + "/fields for lat and lon. Outside Canada coverage is null and nothing else is returned. "
                    + "Answers are cached for 24 hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The values found around the point."),
            @ApiResponse(responseCode = "422", description = "Missing or out-of-range coordinates."),
            @ApiResponse(responseCode = "502", description = "Agriculture and Agri-Food Canada's soil service could not be reached.")
    })
    public SoilLandscapeResponse landscape(
            @RequestParam @Parameter(description = "Latitude in degrees", example = "44.35") double lat,
            @RequestParam @Parameter(description = "Longitude in degrees", example = "-79.16") double lon) {
        log.trace("Entering landscape()");
        log.info("Handling GET /soil-landscape");
        try {
            return service.landscape(lat, lon);
        } finally {
            log.trace("Exiting landscape()");
        }
    }
}
