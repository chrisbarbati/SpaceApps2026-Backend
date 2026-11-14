package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Topsoil for several stored fields, one entry per requested field.
 *
 * <p>A field that is unknown, or whose soil could not be read, is reported in its
 * own entry and never fails the others. Callers should check {@code failed} or each
 * entry's {@code status}.</p>
 *
 * @param results   one result per unique requested field, in request order
 * @param requested number of unique fields requested
 * @param succeeded number of fields with soil estimates
 * @param failed    number of fields reported as errors
 */
public record FieldSoilBatchResponse(
        List<Result> results,
        int requested,
        int succeeded,
        int failed) {

    /** Value of {@link Result#status()} for a field with soil estimates. */
    public static final String OK = "ok";

    /** Value of {@link Result#status()} for a field reported as an error. */
    public static final String ERROR = "error";

    /**
     * Outcome for one field.
     *
     * @param id     the field identifier
     * @param status {@code ok} with {@code soil}, or {@code error} with {@code error}
     * @param soil   topsoil estimates, the same shape as {@code GET /fields/{field_id}/soil}
     * @param error  why this field has no soil estimates
     */
    public record Result(
            String id,
            @Schema(allowableValues = {OK, ERROR}) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) FieldSoilResponse soil,
            @JsonInclude(JsonInclude.Include.NON_NULL) FieldCropHistoryBatchResponse.Error error) {

        /**
         * @return whether this field has soil estimates; not part of the JSON body
         */
        @JsonIgnore
        public boolean isOk() {
            return OK.equals(status);
        }
    }
}
