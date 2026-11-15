package org.greenalienhouseparty.dto;

import java.util.List;

/**
 * Recent yields and farm prices for the crops in the catalog, from Statistics
 * Canada, for one province.
 *
 * <p>These are provincial averages, not field or farm figures, and gross
 * revenue leaves out every cost. They show how crops compare in the market;
 * they are not a margin.</p>
 *
 * @param province     two-letter province code
 * @param provinceName province name
 * @param crops        one entry per catalog crop, in catalog order
 * @param source       where the figures come from
 * @param note         how to read the figures
 */
public record CropMarketResponse(
        String province,
        String provinceName,
        List<CropMarket> crops,
        Source source,
        String note) {

    /**
     * Yield and price for one crop. Any value is {@code null} when Statistics
     * Canada does not publish it for this crop and province; {@code notes} says why.
     *
     * @param cropId            catalog crop identifier
     * @param name              catalog display name
     * @param yieldKgPerHa      latest published average yield in kilograms per hectare
     * @param yieldYear         year of the latest yield
     * @param yieldMeanKgPerHa  mean yield over the recent years published
     * @param yieldYears        number of years in the mean
     * @param yieldSeries       the Statistics Canada crop the yield is for
     * @param pricePerTonne     latest published monthly farm price in dollars per tonne
     * @param priceMonth        month of the latest price, {@code YYYY-MM}
     * @param priceMeanPerTonne mean farm price over the recent months published
     * @param priceMonths       number of months in the mean
     * @param priceSeries       the Statistics Canada farm product the price is for
     * @param grossRevenuePerHa mean yield times mean price, in dollars per hectare, before any costs
     * @param notes             plain-language notes on gaps and approximations
     */
    public record CropMarket(
            String cropId,
            String name,
            Double yieldKgPerHa,
            Integer yieldYear,
            Double yieldMeanKgPerHa,
            Integer yieldYears,
            String yieldSeries,
            Double pricePerTonne,
            String priceMonth,
            Double priceMeanPerTonne,
            Integer priceMonths,
            String priceSeries,
            Double grossRevenuePerHa,
            List<String> notes) {
    }

    /**
     * Provenance of the figures.
     *
     * @param name    publisher
     * @param url     API or dataset URL
     * @param tables  the tables read
     * @param license licence the data is published under
     */
    public record Source(String name, String url, List<String> tables, String license) {
    }
}
