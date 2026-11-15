package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.AgroWeatherRequest;
import org.greenalienhouseparty.dto.AgroWeatherResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Soil wetness, solar radiation, wind, humidity and reference
 * evapotranspiration around a field, re-exposed from the data backend.
 */
@RestController
@Tag(name = "Agro-weather", description = "NASA POWER soil wetness, radiation, wind, humidity and ET0 around a field")
public class AgroWeatherController {

    private static final Logger log = LoggerFactory.getLogger(AgroWeatherController.class);

    private static final String DESCRIPTION = "Returns NASA POWER values for the roughly 50 km cell around the field, "
            + "daily from 1981 to a few days ago: root-zone and surface soil wetness (0 dry, 1 saturated) for the "
            + "latest days, each calendar month and each growing season; FAO-56 Penman-Monteith reference "
            + "evapotranspiration; and growing-season radiation, wind and humidity. soilWetness.latest ranks the "
            + "latest 30 days against the same 30 days in earlier years (dry at the 20th percentile or lower, wet at "
            + "the 80th or higher). These are regional, modelled values, not field measurements, and the latest "
            + "values can read differently from the long record, so treat the percentile as a prompt to check the "
            + "field. The first request for a place takes a few seconds.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public AgroWeatherController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves agro-weather around a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @param years   years of history to read, or {@code null} for the default of 20
     * @return soil wetness, ET0 and season conditions around the field
     */
    @GetMapping("/fields/{field_id}/agro-weather")
    @Operation(summary = "Soil wetness, radiation and ET0 around a field", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agro-weather around the field."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "years outside 5 to 45."),
            @ApiResponse(responseCode = "502", description = "NASA POWER could not be reached."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public AgroWeatherResponse fieldAgroWeather(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "Years of history to read, 5 to 45; defaults to 20") Integer years) {
        log.trace("Entering fieldAgroWeather()");
        log.info("Handling GET /fields/{field_id}/agro-weather");
        try {
            return service.fieldAgroWeather(fieldId, years);
        } finally {
            log.trace("Exiting fieldAgroWeather()");
        }
    }

    /**
     * Retrieves agro-weather around a GeoJSON outline supplied by the caller.
     *
     * @param request polygon and optional years of history
     * @return soil wetness, ET0 and season conditions around the outline
     */
    @PostMapping("/agro-weather")
    @Operation(summary = "Soil wetness, radiation and ET0 around any outline", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agro-weather around the outline."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon, years outside 5 to 45, or unsupported request property."),
            @ApiResponse(responseCode = "502", description = "NASA POWER could not be reached.")
    })
    public AgroWeatherResponse agroWeather(@RequestBody AgroWeatherRequest request) {
        log.trace("Entering agroWeather()");
        log.info("Handling POST /agro-weather");
        try {
            return service.agroWeather(request);
        } finally {
            log.trace("Exiting agroWeather()");
        }
    }
}
