package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Crop history for several stored fields, one entry per requested field.
 *
 * <p>A field that is unknown, or whose crop maps could not be read, is reported
 * in its own entry and never fails the others. Callers should check
 * {@code failed} or each entry's {@code status}.</p>
 *
 * @param results   one result per unique requested field, in request order
 * @param requested number of unique fields requested
 * @param succeeded number of fields with crop history
 * @param failed    number of fields reported as errors
 */
public record FieldCropHistoryBatchResponse(
        List<Result> results,
        int requested,
        int succeeded,
        int failed) {

    /** Value of {@link Result#status()} for a field with crop history. */
    public static final String OK = "ok";

    /** Value of {@link Result#status()} for a field reported as an error. */
    public static final String ERROR = "error";

    /**
     * Outcome for one field.
     *
     * @param id      the field identifier
     * @param status  {@code ok} with {@code history}, or {@code error} with {@code error}
     * @param history crop history, the same shape as {@code GET /fields/{field_id}/crop-history}
     * @param error   why this field has no crop history
     */
    public record Result(
            String id,
            @Schema(allowableValues = {OK, ERROR}) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) CropHistoryResponse history,
            @JsonInclude(JsonInclude.Include.NON_NULL) Error error) {

        /**
         * @return whether this field has crop history; not part of the JSON body
         */
        @JsonIgnore
        public boolean isOk() {
            return OK.equals(status);
        }
    }

    /**
     * Error for one field.
     *
     * @param code   HTTP-style status: 404 for an unknown field, 502 when a crop map provider failed
     * @param detail human-readable explanation
     */
    public record Error(Integer code, String detail) {
    }
}
