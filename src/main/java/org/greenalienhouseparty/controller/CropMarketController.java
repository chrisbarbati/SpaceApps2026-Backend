package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropMarketResponse;
import org.greenalienhouseparty.market.CropMarketService;
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
 * Recent provincial yields and farm prices for the catalog crops, read by
 * this application directly from Statistics Canada.
 */
@RestController
@Tag(name = "Crop Planning", description = "Crop choices and five-year crop-rotation plans")
public class CropMarketController {

    private static final Logger log = LoggerFactory.getLogger(CropMarketController.class);

    private final CropMarketService service;

    /**
     * Creates the controller.
     *
     * @param service crop market data service
     */
    public CropMarketController(CropMarketService service) {
        this.service = service;
    }

    /**
     * Returns recent yields and farm prices for every catalog crop in a province.
     *
     * @param province two-letter province code, or {@code null} for Ontario
     * @return one entry per catalog crop
     */
    @GetMapping("/crops/market")
    @Operation(summary = "Recent yields and farm prices by crop",
            description = "Returns, for every crop from GET /crops, the latest and recent-average provincial yield "
                    + "(kg/ha, Statistics Canada table 32-10-0359) and farm price ($/tonne, table 32-10-0077), with "
                    + "gross revenue per hectare (mean yield times mean price, before any costs). These are "
                    + "provincial averages, not field figures, and gross revenue is not a margin. Values are null "
                    + "where Statistics Canada does not publish them (for example grapes, potato yields and hay "
                    + "prices); notes explains each gap. Answers are cached for 12 hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Yields and prices for the province."),
            @ApiResponse(responseCode = "422", description = "Unknown province code."),
            @ApiResponse(responseCode = "502", description = "Statistics Canada could not be reached.")
    })
    public CropMarketResponse market(
            @RequestParam(required = false)
            @Parameter(description = "Two-letter province code (NL, PE, NS, NB, QC, ON, MB, SK, AB, BC); defaults to ON")
            String province) {
        log.trace("Entering market()");
        log.info("Handling GET /crops/market");
        try {
            return service.market(province);
        } finally {
            log.trace("Exiting market()");
        }
    }
}
