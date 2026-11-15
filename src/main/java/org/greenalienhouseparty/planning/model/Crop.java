package org.greenalienhouseparty.planning.model;

/**
 * Agronomic and economic description of one crop, as used by the rotation
 * planner and returned to the frontend for display.
 *
 * @param id                    stable identifier submitted in crop-planning requests, for example {@code corn}
 * @param name                  display name, for example {@code Corn}
 * @param family                botanical family; crops of one family share pests and diseases
 * @param nFixKgHa              nitrogen the crop fixes and leaves for the soil, in kg per hectare
 * @param nDemandKgHa           nitrogen the crop takes up, in kg per hectare
 * @param residueScore          carbon returned to the soil as residue, 0 (none) to 1 (most)
 * @param coverMonths           months of the year the crop keeps the ground covered, 0 to 12
 * @param phMin                 lowest soil pH the crop tolerates
 * @param phMax                 highest soil pH the crop tolerates
 * @param netMarginPerHa        net margin in dollars per hectare; may be negative for cover crops
 * @param diseaseSusceptibility how badly the crop suffers when it follows its own family, 0 (immune) to 1
 */
public record Crop(
        String id,
        String name,
        String family,
        double nFixKgHa,
        double nDemandKgHa,
        double residueScore,
        int coverMonths,
        double phMin,
        double phMax,
        double netMarginPerHa,
        double diseaseSusceptibility) {
}
