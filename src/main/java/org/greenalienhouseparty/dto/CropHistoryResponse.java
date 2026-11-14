package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Year-by-year crop classification returned for a field or drawn outline.
 *
 * @param id               field identifier, present only for the field-specific route
 * @param source           source dataset metadata, or {@code null} outside supported coverage
 * @param years            per-year dominant classes and class shares
 * @param rotation         dominant class name for each available year
 * @param distinctCrops    sorted distinct crop names, excluding non-crop classes
 * @param cropYears        count of years whose dominant class is a crop
 * @param unavailableYears years where a provider did not return a usable result
 * @param summary          plain-language reading of the history for display: last season, rotation
 *                         pattern, the crop the pattern points to this year, and data-quality flags
 * @param note             interpretation and coverage limitations
 */
public record CropHistoryResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        CropSource source,
        List<CropYear> years,
        List<String> rotation,
        List<String> distinctCrops,
        Integer cropYears,
        List<Integer> unavailableYears,
        Summary summary,
        String note) {

    /**
     * Provenance of the national crop-map source used for a result.
     *
     * @param name        provider or dataset name
     * @param url         dataset information URL
     * @param license     source licence
     * @param resolutionM nominal map resolution in metres
     */
    public record CropSource(String name, String url, String license, Integer resolutionM) {
    }

    /**
     * Dominant crop-map class for one year and the ranked classes within the field.
     *
     * @param year    crop-map year
     * @param crop    dominant class name
     * @param code    provider class code
     * @param isCrop    whether the class represents a crop, or {@code null} if unknown
     * @param group     crop group shared by both countries, e.g. {@code corn}, {@code soybeans}, {@code pasture}
     * @param groupLabel the crop group in plain words, e.g. "Corn"
     * @param share     fraction of mapped field pixels assigned to the dominant class
     * @param cropShare fraction of mapped field pixels under any crop class
     * @param classes   leading classes that each meet the provider's minimum-share threshold
     */
    public record CropYear(
            Integer year,
            String crop,
            Integer code,
            Boolean isCrop,
            String group,
            String groupLabel,
            Double share,
            Double cropShare,
            List<CropClass> classes) {
    }

    /**
     * A ranked crop-map class and its field share.
     *
     * @param code   provider class code
     * @param name   provider class name
     * @param isCrop whether the class represents a crop, or {@code null} if unknown
     * @param share  fraction of mapped field pixels assigned to the class
     */
    public record CropClass(Integer code, String name, Boolean isCrop, Double share) {
    }

    /**
     * Plain-language reading of a crop history, ready to display.
     *
     * @param yearsOfData      number of mapped years
     * @param firstYear        first mapped year
     * @param lastSeason       the newest mapped year; always last season, never the current year
     * @param pattern          the rotation in words, or {@code null} without history
     * @param expectedThisYear the crop the pattern points to this year, or {@code null} when there is no clear pattern
     * @param flags            data-quality warnings
     * @param notes            one sentence per caveat, ready to show
     */
    public record Summary(
            Integer yearsOfData,
            Integer firstYear,
            LastSeason lastSeason,
            Pattern pattern,
            ExpectedCrop expectedThisYear,
            Flags flags,
            List<String> notes) {
    }

    /**
     * The newest mapped season.
     *
     * @param year       crop-map year
     * @param crop       the provider's class name, e.g. "Corn for grain"
     * @param group      crop group key
     * @param groupLabel crop group in plain words, e.g. "Corn"
     */
    public record LastSeason(Integer year, String crop, String group, String groupLabel) {
    }

    /**
     * The rotation in recent years.
     *
     * @param kind        {@code stable}, {@code alternating}, {@code rotation}, {@code changed} or {@code mixed}
     * @param periodYears years before the pattern repeats, or {@code null} when mixed
     * @param groups      crop groups in the pattern
     * @param text        the pattern in one sentence, e.g. "A 3-year rotation: corn, corn, then soybeans."
     */
    public record Pattern(
            @Schema(allowableValues = {"stable", "alternating", "rotation", "changed", "mixed"}) String kind,
            Integer periodYears,
            List<String> groups,
            String text) {
    }

    /**
     * The crop the rotation pattern points to this year. An estimate, not an observation.
     *
     * @param year       the current year
     * @param group      crop group key
     * @param crop       crop group in plain words, e.g. "Corn"
     * @param confidence {@code high} when the pattern held every year, otherwise {@code medium}
     * @param reason     why, in one sentence
     */
    public record ExpectedCrop(
            Integer year,
            String group,
            String crop,
            @Schema(allowableValues = {"high", "medium"}) String confidence,
            String reason) {
    }

    /**
     * Data-quality warnings.
     *
     * @param possiblyMerged    many years show a mix of crops, so the outline may cover more than one field
     * @param mostlyNotCropland recent years were mostly grass, trees or buildings rather than crops
     */
    public record Flags(Boolean possiblyMerged, Boolean mostlyNotCropland) {
    }
}
