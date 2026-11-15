package org.greenalienhouseparty.planning.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Reference data for one crop: what it needs from climate and soil (sourced
 * from FAO ECOCROP) and the agronomic and economic values that are still
 * estimates.
 *
 * @param id             stable crop identifier, for example {@code corn}
 * @param name           display name
 * @param scientificName botanical name the requirements were looked up under
 * @param family         botanical family
 * @param requirements   environmental requirements from FAO ECOCROP
 * @param estimates      values that are illustrative placeholders, not sourced
 */
public record CropReference(
        String id,
        String name,
        String scientificName,
        String family,
        Requirements requirements,
        Estimates estimates) {

    /**
     * Environmental requirements. "Optimal" is the range the crop does best in;
     * the plain minimum and maximum are the absolute range it tolerates.
     *
     * @param phMin            lowest tolerated soil pH
     * @param phMax            highest tolerated soil pH
     * @param phOptimalMin     low end of the optimal soil pH
     * @param phOptimalMax     high end of the optimal soil pH
     * @param tempOptimalMinC  low end of the optimal growing temperature, in Celsius
     * @param tempOptimalMaxC  high end of the optimal growing temperature, in Celsius
     * @param tempMinC         lowest tolerated growing temperature, in Celsius
     * @param tempMaxC         highest tolerated growing temperature, in Celsius
     * @param rainOptimalMinMm low end of the optimal annual rainfall, in millimetres
     * @param rainOptimalMaxMm high end of the optimal annual rainfall, in millimetres
     * @param rainMinMm        lowest tolerated annual rainfall, in millimetres
     * @param rainMaxMm        highest tolerated annual rainfall, in millimetres
     * @param cycleMinDays     shortest time from planting to harvest, in days
     * @param cycleMaxDays     longest time from planting to harvest, in days
     * @param killingTempC     temperature that kills the crop during rest, in Celsius; {@code null} when not recorded
     * @param rootingDepth     soil depth the crop needs, in words
     * @param lifeSpan         {@code annual} or {@code perennial}
     * @param textureOptimal   soil texture classes the crop does best in: {@code heavy}, {@code medium},
     *                         {@code light}, {@code organic}
     * @param textureTolerated every soil texture class the crop tolerates, including the optimal ones
     * @param drainageOptimal  soil drainage classes the crop does best in: {@code poorly}, {@code well},
     *                         {@code excessive}
     * @param drainageTolerated every soil drainage class the crop tolerates, including the optimal ones
     * @param seasonWaterNeedMm water the crop needs over its growing season, in millimetres; not yet
     *                         sourced, so {@code null} for every crop
     * @param heatUnitsRequired crop heat units the crop needs to mature; not yet sourced, so {@code null}
     *                         for every crop
     */
    public record Requirements(
            Double phMin,
            Double phMax,
            Double phOptimalMin,
            Double phOptimalMax,
            Double tempOptimalMinC,
            Double tempOptimalMaxC,
            Double tempMinC,
            Double tempMaxC,
            Double rainOptimalMinMm,
            Double rainOptimalMaxMm,
            Double rainMinMm,
            Double rainMaxMm,
            Integer cycleMinDays,
            Integer cycleMaxDays,
            Double killingTempC,
            String rootingDepth,
            String lifeSpan,
            List<String> textureOptimal,
            List<String> textureTolerated,
            List<String> drainageOptimal,
            List<String> drainageTolerated,
            Double seasonWaterNeedMm,
            Double heatUnitsRequired) {

        /**
         * Says whether the crop lives for several years.
         *
         * @return {@code true} for a perennial crop
         */
        @JsonIgnore
        public boolean perennial() {
            return "perennial".equalsIgnoreCase(lifeSpan);
        }
    }

    /**
     * Values that are still illustrative placeholders.
     *
     * @param nFixKgHa              nitrogen fixed, in kg per hectare
     * @param nDemandKgHa           nitrogen taken up, in kg per hectare
     * @param residueScore          carbon returned as residue, 0 to 1
     * @param coverMonths           months of ground cover, 0 to 12
     * @param netMarginPerHa        net margin in dollars per hectare
     * @param diseaseSusceptibility penalty for following the same family, 0 to 1
     */
    public record Estimates(
            double nFixKgHa,
            double nDemandKgHa,
            double residueScore,
            int coverMonths,
            double netMarginPerHa,
            double diseaseSusceptibility) {
    }
}
