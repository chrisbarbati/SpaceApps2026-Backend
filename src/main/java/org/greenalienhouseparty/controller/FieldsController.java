package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.service.DataBackendService;
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
 * Field boundary endpoints, re-exposed from the data backend.
 */
@RestController
@RequestMapping("/fields")
@Tag(name = "Fields", description = "Field outlines and acreage")
public class FieldsController {

    private static final Logger log = LoggerFactory.getLogger(FieldsController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public FieldsController(DataBackendService service) {
        this.service = service;
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
            @RequestParam(defaultValue = "500")
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
     * Retrieves the estimated hectares and acres of a selected field.
     *
     * @param fieldId the field identifier
     * @return the estimated area
     */
    @GetMapping("/{field_id}")
    @Operation(summary = "Estimated hectares and acres of a selected field")
    public FieldAreaResponse field(
            @PathVariable("field_id") @Parameter(description = "Field identifier") String fieldId) {
        log.trace("Entering field()");
        log.info("Handling GET /fields/{field_id}");
        try {
            return service.fieldArea(fieldId);
        } finally {
            log.trace("Exiting field()");
        }
    }
}
