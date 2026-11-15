package org.greenalienhouseparty.dto;

import java.util.List;

/**
 * What national soil surveys and agri-environmental indicators say about the
 * land around a point: how capable it is for crops, how it drains, and how
 * erosion risk, soil cover, soil carbon and leftover nitrogen have been
 * trending.
 *
 * <p>The source maps are regional (1:250,000 to 1:1,000,000), so values
 * describe the surrounding soil landscape, not one field. Canada only.</p>
 *
 * @param location       the point asked about
 * @param coverage       {@code Canada} when any layer covers the point, otherwise {@code null}
 * @param landCapability Canada Land Inventory capability for agriculture, or {@code null} when not mapped
 * @param drainage       dominant soil drainage, or {@code null} when not mapped
 * @param indicators     agri-environmental indicators with their history, in a fixed order
 * @param source         where the values come from
 * @param note           how to read the values
 */
public record SoilLandscapeResponse(
        Location location,
        String coverage,
        LandCapability landCapability,
        Drainage drainage,
        List<Indicator> indicators,
        Source source,
        String note) {

    /**
     * The point asked about.
     *
     * @param lat latitude
     * @param lon longitude
     */
    public record Location(Double lat, Double lon) {
    }

    /**
     * Canada Land Inventory capability for agriculture of the dominant soil.
     *
     * @param classCode   {@code 1} (best) to {@code 7}, or {@code O} for organic soils
     * @param description what the class means for cropping
     * @param limitations the kinds of limitation recorded, most important first
     * @param scale       scale of the source map
     */
    public record LandCapability(String classCode, String description, List<Limitation> limitations, String scale) {
    }

    /**
     * One kind of limitation on cropping.
     *
     * @param code    Canada Land Inventory subclass letter
     * @param meaning the limitation in words
     */
    public record Limitation(String code, String meaning) {
    }

    /**
     * Dominant soil drainage of the soil landscape.
     *
     * @param code        drainage code
     * @param description the drainage class in words
     * @param sharePct    share of the soil landscape with this drainage, in percent
     */
    public record Drainage(String code, String description, Integer sharePct) {
    }

    /**
     * One agri-environmental indicator for the soil landscape.
     *
     * @param key        stable identifier, for example {@code erosionRisk}
     * @param label      the indicator in plain words
     * @param unit       unit of the values
     * @param meaning    how to read the indicator, including which direction is better
     * @param latestYear census year of the newest value
     * @param value      the newest value
     * @param rating     the newest value's class in words
     * @param change     change over the whole record, or {@code null} when not published
     * @param history    one entry per census year, oldest first
     */
    public record Indicator(
            String key,
            String label,
            String unit,
            String meaning,
            Integer latestYear,
            Double value,
            String rating,
            Change change,
            List<YearValue> history) {
    }

    /**
     * Change in an indicator between two census years.
     *
     * @param fromYear first year
     * @param toYear   last year
     * @param value    change in the indicator's unit
     * @param rating   the change in words
     */
    public record Change(Integer fromYear, Integer toYear, Double value, String rating) {
    }

    /**
     * An indicator's value in one census year.
     *
     * @param year   census year
     * @param value  the value
     * @param rating the value's class in words
     */
    public record YearValue(Integer year, Double value, String rating) {
    }

    /**
     * Provenance of the values.
     *
     * @param name     publisher
     * @param url      service root URL
     * @param datasets the datasets read
     * @param license  licence the data is published under
     */
    public record Source(String name, String url, List<String> datasets, String license) {
    }
}
