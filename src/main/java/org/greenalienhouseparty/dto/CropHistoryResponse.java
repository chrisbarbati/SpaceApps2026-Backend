package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

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
     * @param isCrop  whether the class represents a crop, or {@code null} if unknown
     * @param share   fraction of mapped field pixels assigned to the dominant class
     * @param classes leading classes that each meet the provider's minimum-share threshold
     */
    public record CropYear(
            Integer year,
            String crop,
            Integer code,
            Boolean isCrop,
            Double share,
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
}
