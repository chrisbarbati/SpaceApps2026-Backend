package org.greenalienhouseparty.market;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.greenalienhouseparty.dto.CropMarketResponse;
import org.greenalienhouseparty.dto.CropMarketResponse.CropMarket;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.market.StatCanClient.DataPoint;
import org.greenalienhouseparty.market.StatCanClient.SeriesKey;
import org.greenalienhouseparty.market.StatCanClient.SeriesResult;
import org.greenalienhouseparty.planning.CropRepository;
import org.greenalienhouseparty.planning.model.Crop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Recent provincial yields and farm prices for the catalog crops, from
 * Statistics Canada.
 *
 * <p>Yields are annual, from table 32-10-0359 (principal field crops). Prices
 * are monthly, from table 32-10-0077 (farm product prices). The two tables
 * number provinces and crops differently, so both numberings are held here.
 * A crop the tables do not cover (grapes, potato yields, alfalfa prices) is
 * returned with nulls and a note rather than left out. Results are cached per
 * province because the tables change at most monthly.</p>
 */
@Service
public class CropMarketService {

    private static final Logger log = LoggerFactory.getLogger(CropMarketService.class);

    static final int YIELD_TABLE = 32100359;

    static final int PRICE_TABLE = 32100077;

    /** Member ID of "Average yield (kilograms per hectare)" in the yield table. */
    private static final int YIELD_KG_PER_HA = 18;

    /** Annual periods read; the newest is often not published yet. */
    static final int YIELD_PERIODS = 6;

    /** Monthly periods read: one year, so seasonal price swings average out. */
    static final int PRICE_PERIODS = 12;

    /** Hundredweights (100 lb) in a tonne, for prices quoted per hundredweight. */
    private static final double CWT_PER_TONNE = 22.0462;

    /**
     * A province as numbered in each table.
     *
     * @param name          province name
     * @param yieldGeoId    geography member ID in the yield table
     * @param priceGeoId    geography member ID in the price table
     */
    private record Province(String name, int yieldGeoId, int priceGeoId) {
    }

    private static final Map<String, Province> PROVINCES = Map.of(
            "NL", new Province("Newfoundland and Labrador", 13, 1),
            "PE", new Province("Prince Edward Island", 3, 2),
            "NS", new Province("Nova Scotia", 4, 3),
            "NB", new Province("New Brunswick", 5, 4),
            "QC", new Province("Quebec", 6, 5),
            "ON", new Province("Ontario", 7, 6),
            "MB", new Province("Manitoba", 9, 7),
            "SK", new Province("Saskatchewan", 10, 8),
            "AB", new Province("Alberta", 11, 9),
            "BC", new Province("British Columbia", 12, 10));

    /**
     * Which Statistics Canada series stand for a catalog crop.
     *
     * @param yieldCropId   crop member ID in the yield table, or {@code null} when not covered
     * @param yieldSeries   name of that crop in the yield table
     * @param priceId       farm product member ID in the price table, or {@code null} when not covered
     * @param priceSeries   name of that farm product
     * @param pricePerCwt   whether the price is quoted per hundredweight rather than per tonne
     * @param note          approximation or gap to tell the user about, or {@code null}
     */
    private record Mapping(
            Integer yieldCropId, String yieldSeries, Integer priceId, String priceSeries, boolean pricePerCwt, String note) {
    }

    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "corn", new Mapping(11, "Corn for grain", 16, "Corn for grain", false, null),
            "soybeans", new Mapping(15, "Soybeans", 15, "Soybeans", false, null),
            "wheat", new Mapping(1, "Wheat, all", 3, "Wheat (except durum wheat)", false, null),
            "barley", new Mapping(6, "Barley", 14, "Barley", false, null),
            "oats", new Mapping(5, "Oats", 13, "Oats", false, null),
            "canola", new Mapping(16, "Canola (rapeseed)", 18, "Canola (including rapeseed)", false, null),
            "potatoes", new Mapping(null, null, 23, "Fresh potatoes", true,
                    "Statistics Canada publishes potato yields in a separate survey, so no yield is shown."),
            "alfalfa", new Mapping(28, "Tame hay", null, null, false,
                    "Yield is for all tame hay, used as a stand-in for alfalfa. Hay has no published farm price."));

    /** Ontario wheat is priced under its own farm product. */
    private static final Mapping ONTARIO_WHEAT =
            new Mapping(1, "Wheat, all", 2, "Ontario wheat excluding payments", false, null);

    private static final String NOT_COVERED =
            "Statistics Canada's field crop and farm price tables do not cover this crop.";

    private static final String NOTE = "Provincial averages from Statistics Canada, not figures for a field or farm. "
            + "Yield is the mean of the recent years published; price is the mean of the recent months published. "
            + "Gross revenue is yield times price before any costs, so it is not a margin. "
            + "Check local prices and your own yields before relying on these.";

    private final StatCanClient client;

    private final CropRepository crops;

    private final Duration cacheTtl;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * A cached answer.
     *
     * @param storedNanos when it was stored, from {@link System#nanoTime()}
     * @param response    the answer
     */
    private record Cached(long storedNanos, CropMarketResponse response) {
    }

    /**
     * Creates the service.
     *
     * @param client   Statistics Canada client
     * @param crops    crop catalog
     * @param cacheTtl how long an answer is reused (see {@code statcan.cache-ttl})
     */
    public CropMarketService(
            StatCanClient client,
            CropRepository crops,
            @Value("${statcan.cache-ttl:12h}") Duration cacheTtl) {
        this.client = client;
        this.crops = crops;
        this.cacheTtl = cacheTtl;
    }

    /**
     * Returns recent yields and farm prices for every catalog crop in a province.
     *
     * @param provinceCode two-letter province code, any case; {@code null} or blank means Ontario
     * @return one entry per catalog crop
     * @throws InvalidRequestException when the province code is unknown
     */
    public CropMarketResponse market(String provinceCode) {
        log.trace("Entering market()");
        try {
            String code = provinceCode == null || provinceCode.isBlank()
                    ? "ON"
                    : provinceCode.strip().toUpperCase(Locale.ROOT);
            Province province = PROVINCES.get(code);
            if (province == null) {
                throw new InvalidRequestException("province must be one of " + PROVINCES.keySet().stream().sorted().toList());
            }
            Cached hit = cache.get(code);
            if (hit != null && System.nanoTime() - hit.storedNanos() < cacheTtl.toNanos()) {
                log.debug("Returning cached market data for {}", code);
                return hit.response();
            }
            CropMarketResponse response = fetch(code, province);
            cache.put(code, new Cached(System.nanoTime(), response));
            return response;
        } finally {
            log.trace("Exiting market()");
        }
    }

    /**
     * Reads both tables for a province and builds the answer.
     *
     * @param code     province code
     * @param province province numbering
     * @return one entry per catalog crop
     */
    private CropMarketResponse fetch(String code, Province province) {
        log.debug("Requesting yields and prices for {} from Statistics Canada", code);
        Map<String, SeriesKey> yieldKeys = new HashMap<>();
        Map<String, SeriesKey> priceKeys = new HashMap<>();
        for (Crop crop : crops.findAll()) {
            Mapping mapping = mappingFor(crop.id(), code);
            if (mapping == null) {
                continue;
            }
            if (mapping.yieldCropId() != null) {
                yieldKeys.put(crop.id(), new SeriesKey(YIELD_TABLE,
                        coordinate(province.yieldGeoId(), YIELD_KG_PER_HA, mapping.yieldCropId())));
            }
            if (mapping.priceId() != null) {
                priceKeys.put(crop.id(), new SeriesKey(PRICE_TABLE, coordinate(province.priceGeoId(), mapping.priceId())));
            }
        }
        Map<SeriesKey, List<DataPoint>> yields = byKey(client.latestPeriods(List.copyOf(yieldKeys.values()), YIELD_PERIODS));
        Map<SeriesKey, List<DataPoint>> prices = byKey(client.latestPeriods(List.copyOf(priceKeys.values()), PRICE_PERIODS));

        List<CropMarket> entries = new ArrayList<>();
        for (Crop crop : crops.findAll()) {
            Mapping mapping = mappingFor(crop.id(), code);
            entries.add(entry(crop, mapping,
                    yields.getOrDefault(yieldKeys.get(crop.id()), List.of()),
                    prices.getOrDefault(priceKeys.get(crop.id()), List.of())));
        }
        return new CropMarketResponse(
                code,
                province.name(),
                entries,
                new CropMarketResponse.Source(
                        "Statistics Canada",
                        "https://www150.statcan.gc.ca/t1/wds/rest",
                        List.of("32-10-0359-01 Estimated areas, yield, production, average farm price and total farm "
                                + "value of principal field crops",
                                "32-10-0077-01 Farm product prices, crops and livestock"),
                        "Statistics Canada Open Licence"),
                NOTE);
    }

    /**
     * Builds one crop's entry from its yield and price points.
     *
     * @param crop    catalog crop
     * @param mapping series mapping, or {@code null} when the crop is not covered
     * @param yields  annual yield points, oldest first
     * @param prices  monthly price points, oldest first
     * @return the entry
     */
    private static CropMarket entry(Crop crop, Mapping mapping, List<DataPoint> yields, List<DataPoint> prices) {
        List<String> notes = new ArrayList<>();
        if (mapping == null) {
            notes.add(NOT_COVERED);
            return new CropMarket(crop.id(), crop.name(), null, null, null, null, null,
                    null, null, null, null, null, null, notes);
        }
        if (mapping.note() != null) {
            notes.add(mapping.note());
        }
        double priceFactor = mapping.pricePerCwt() ? CWT_PER_TONNE : 1.0;
        List<DataPoint> validYields = published(yields);
        List<DataPoint> validPrices = published(prices);
        DataPoint latestYield = validYields.isEmpty() ? null : validYields.getLast();
        DataPoint latestPrice = validPrices.isEmpty() ? null : validPrices.getLast();
        Double meanYield = mean(validYields, 1.0);
        Double meanPrice = mean(validPrices, priceFactor);
        if (mapping.yieldCropId() != null && latestYield == null) {
            notes.add("No recent yield is published for this crop in this province.");
        }
        if (mapping.priceId() != null && latestPrice == null) {
            notes.add("No recent farm price is published for this crop in this province.");
        }
        if (mapping.pricePerCwt() && latestPrice != null) {
            notes.add("Price is published per hundredweight and converted to dollars per tonne.");
        }
        Double revenue = meanYield == null || meanPrice == null ? null : round(meanYield / 1000.0 * meanPrice, 0);
        return new CropMarket(
                crop.id(),
                crop.name(),
                latestYield == null ? null : latestYield.value(),
                latestYield == null ? null : Integer.valueOf(latestYield.refPer().substring(0, 4)),
                meanYield == null ? null : round(meanYield, 0),
                meanYield == null ? null : validYields.size(),
                mapping.yieldSeries(),
                latestPrice == null ? null : round(latestPrice.value() * priceFactor, 2),
                latestPrice == null ? null : latestPrice.refPer().substring(0, 7),
                meanPrice == null ? null : round(meanPrice, 2),
                meanPrice == null ? null : validPrices.size(),
                mapping.priceSeries(),
                revenue,
                List.copyOf(notes));
    }

    /**
     * Finds the series mapping for a crop, using Ontario's own wheat price series in Ontario.
     *
     * @param cropId   catalog crop id
     * @param province province code
     * @return the mapping, or {@code null} when the crop is not covered
     */
    private static Mapping mappingFor(String cropId, String province) {
        if ("wheat".equals(cropId) && "ON".equals(province)) {
            return ONTARIO_WHEAT;
        }
        return MAPPINGS.get(cropId);
    }

    /**
     * Builds a WDS coordinate: member IDs joined by dots and padded with zeros to ten positions.
     *
     * @param memberIds member ID per dimension, in dimension order
     * @return the coordinate
     */
    static String coordinate(int... memberIds) {
        StringBuilder coordinate = new StringBuilder();
        for (int position = 0; position < 10; position++) {
            if (position > 0) {
                coordinate.append('.');
            }
            coordinate.append(position < memberIds.length ? memberIds[position] : 0);
        }
        return coordinate.toString();
    }

    /**
     * Indexes successful results by the series they answer.
     *
     * @param results results from the service
     * @return data points per series; failed series are absent
     */
    private static Map<SeriesKey, List<DataPoint>> byKey(List<SeriesResult> results) {
        Map<SeriesKey, List<DataPoint>> points = new HashMap<>();
        for (SeriesResult result : results) {
            if (result == null || result.object() == null || !"SUCCESS".equals(result.status())
                    || result.object().productId() == null || result.object().vectorDataPoint() == null) {
                continue;
            }
            points.put(new SeriesKey(result.object().productId(), result.object().coordinate()),
                    result.object().vectorDataPoint());
        }
        return points;
    }

    /**
     * Keeps the points that have a value and a period.
     *
     * @param points points as returned
     * @return published points, in the same order
     */
    private static List<DataPoint> published(List<DataPoint> points) {
        return points.stream()
                .filter(Objects::nonNull)
                .filter(point -> point.value() != null && point.refPer() != null && point.refPer().length() >= 7)
                .toList();
    }

    /**
     * Averages point values.
     *
     * @param points published points
     * @param factor multiplier applied to each value
     * @return the mean, or {@code null} without points
     */
    private static Double mean(List<DataPoint> points, double factor) {
        return points.isEmpty()
                ? null
                : points.stream().mapToDouble(point -> point.value() * factor).average().orElseThrow();
    }

    /**
     * Rounds a value for display.
     *
     * @param value    value to round
     * @param decimals decimal places to keep
     * @return the rounded value
     */
    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}
