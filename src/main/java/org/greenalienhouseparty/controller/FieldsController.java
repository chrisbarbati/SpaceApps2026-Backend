package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.greenalienhouseparty.service.FieldDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Field boundary endpoints, re-exposed from the data backend, and the full
 * data for one field.
 */
@RestController
@RequestMapping("/fields")
@Tag(name = "Fields", description = "Field outlines, acreage and everything known about a field")
public class FieldsController {

    private static final Logger log = LoggerFactory.getLogger(FieldsController.class);

    private final DataBackendService service;

    private final FieldDetailsService details;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     * @param details gathers everything known about one field
     */
    public FieldsController(DataBackendService service, FieldDetailsService details) {
        this.service = service;
        this.details = details;
    }

    /**
     * Lists field outlines in a map viewport, with pagination.
     *
     * @param bbox   the optional {@code west,south,east,north} bounding box
     * @param limit  the maximum number of features per page
     * @param cursor the cursor from the previous page
     * @return a page of field outlines
     */
    @GetMapping
    @Operation(summary = "Field outlines in a map viewport",
            description = "Returns WGS84 field outlines in the visible map area, with pagination.")
    public FieldsResponse listFields(
            @RequestParam(required = false)
            @Parameter(description = "west,south,east,north; defaults to the Orillia area") String bbox,
            @RequestParam(defaultValue = "50")
            @Parameter(description = "Maximum features per page") int limit,
            @RequestParam(defaultValue = "0")
            @Parameter(description = "nextCursor from the previous page") int cursor) {
        log.trace("Entering listFields()");
        log.info("Handling GET /fields");
        try {
            return service.listFields(bbox, limit, cursor);
        } finally {
            log.trace("Exiting listFields()");
        }
    }

    /**
     * Retrieves everything known about a selected field: its estimated size
     * and one section per data source.
     *
     * @param fieldId the field identifier
     * @param include optional slow sections to add, comma-separated
     * @return the field's size, data sections and the status of each section
     */
    @GetMapping("/{field_id}")
    @Operation(summary = "Everything known about a selected field",
            description = "Returns the field's estimated hectares and acres, as before, plus one section per data "
                    + "source in the same shape as that source's own route: soil, cropHistory, climate (summary "
                    + "with the default years), cropCalendar, agroWeather, soilLandscape (Canada only) and "
                    + "seasonalOutlook, all fetched at the same time. greenness, vegetationHistory and "
                    + "droughtIndicators can take 20 to 45 seconds the first time a field is asked for, so they "
                    + "are only fetched with include=greenness, vegetation, drought or all. A section is null when "
                    + "it was not requested or could not be fetched; sections gives each one's status (ok, "
                    + "unavailable or not_requested) and the reason. A failing source never fails the request; an "
                    + "unknown field is still a 404. Without include, a field takes a few seconds the first time "
                    + "and well under a second after that.")
    public FieldDetailsResponse field(
            @PathVariable("field_id") @Parameter(description = "Field identifier") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "Optional sections, comma-separated: greenness, vegetation, drought or all")
            String include) {
        log.trace("Entering field()");
        log.info("Handling GET /fields/{field_id}");
        try {
            return details.details(fieldId, include);
        } finally {
            log.trace("Exiting field()");
        }
    }
}
