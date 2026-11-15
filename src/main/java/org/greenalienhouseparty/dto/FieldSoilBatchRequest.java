package org.greenalienhouseparty.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request for the topsoil of several stored fields, for example the fields a user
 * selected on the map.
 *
 * @param ids field identifiers from {@code GET /fields}; duplicates are answered once
 */
@Schema(description = "Field IDs whose topsoil is wanted.")
public record FieldSoilBatchRequest(
        @ArraySchema(
                arraySchema = @Schema(description = "Field identifiers from /fields, in display order. "
                        + "Duplicates are answered once; blank IDs are rejected."),
                schema = @Schema(example = "ftw-2025-17TPK_0_0-13020"),
                minItems = 1,
                maxItems = 300)
        List<String> ids) {
}
