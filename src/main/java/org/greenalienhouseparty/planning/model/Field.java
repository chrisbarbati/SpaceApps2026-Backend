package org.greenalienhouseparty.planning.model;

import java.util.List;

/**
 * One field as the rotation planner sees it: location, size, soil and what was
 * grown there before.
 *
 * @param id               field identifier
 * @param lat              centroid latitude in degrees
 * @param lon              centroid longitude in degrees
 * @param areaHa           area in hectares
 * @param ph               topsoil pH
 * @param texture          topsoil texture class, for example {@code Sandy loam}
 * @param organicMatterPct topsoil organic matter in percent
 * @param erosionRisk      erosion risk, 0 (none) to 1 (severe)
 * @param history          crop ids grown here, most recent first; {@link #NO_CROP} marks a year with no
 *                         known crop so later years keep their position
 */
public record Field(
        String id,
        double lat,
        double lon,
        double areaHa,
        double ph,
        String texture,
        double organicMatterPct,
        double erosionRisk,
        List<String> history) {

    /** History entry for a year with no crop, or a crop the catalog does not know. */
    public static final String NO_CROP = "none";
}
