package org.greenalienhouseparty.outlook;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse.Chances;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse.Period;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.outlook.GeoMetClient.PointValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Seasonal outlook at a point from the Canadian Seasonal to Inter-annual
 * Prediction System (CanSIPS), read from MSC GeoMet.
 *
 * <p>CanSIPS publishes, each month, the probability that a three-month period
 * is above, near or below normal for temperature and for precipitation, for
 * periods starting from the run month up to nine months ahead. This service
 * reads the three consecutive periods that cover the next nine months. The
 * newest run is found by asking for the first period without a date. Answers
 * are cached per grid cell because a run is published once a month.</p>
 */
@Service
public class SeasonalOutlookService {

    private static final Logger log = LoggerFactory.getLogger(SeasonalOutlookService.class);

    private static final String LAYER = "CanSIPS_100km_%s-Prob%sNormal-2m_PT3M";

    private static final String TEMPERATURE = "AirTemp";

    private static final String PRECIPITATION = "PrecipAccum";

    private static final List<String> CATEGORIES = List.of("Above", "Near", "Below");

    /** Start of each period read, in months after the run month. */
    static final List<Integer> PERIOD_OFFSETS = List.of(0, 3, 6);

    /** Below this no category stands out; with no information each is about 33%. */
    static final double CLEAR_SIGNAL_PCT = 40.0;

    /** Requests to GeoMet in flight at once. */
    private static final int PARALLEL = 6;

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private static final String NOTE = "Chances that each three-month period is above, near or below normal, from "
            + "CanSIPS for a cell of about 100 km. With no information each chance is about 33%, so only clear "
            + "departures mean much. Temperature outlooks are more reliable than precipitation outlooks, and "
            + "reliability falls for later periods. Use this for the coming season only; it says nothing about "
            + "individual days or about later years.";

    private static final SeasonalOutlookResponse.Source SOURCE = new SeasonalOutlookResponse.Source(
            "Canadian Seasonal to Inter-annual Prediction System (CanSIPS)",
            "Environment and Climate Change Canada, MSC GeoMet",
            "https://geo.weather.gc.ca/geomet",
            100,
            "Environment and Climate Change Canada Data Servers End-use Licence");

    private final GeoMetClient client;

    private final Duration cacheTtl;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLEL);

    /**
     * A cached answer.
     *
     * @param storedNanos when it was stored, from {@link System#nanoTime()}
     * @param response    the answer
     */
    private record Cached(long storedNanos, SeasonalOutlookResponse response) {
    }

    /**
     * Creates the service.
     *
     * @param client   MSC GeoMet client
     * @param cacheTtl how long an answer is reused (see {@code geomet.cache-ttl})
     */
    public SeasonalOutlookService(GeoMetClient client, @Value("${geomet.cache-ttl:6h}") Duration cacheTtl) {
        this.client = client;
        this.cacheTtl = cacheTtl;
    }

    /**
     * Stops the request threads when the application shuts down.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns the outlook for the next nine months at a point.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return three consecutive three-month periods, or no periods when CanSIPS has nothing at the point
     * @throws InvalidRequestException when the coordinates are out of range
     * @throws ExternalDataException   when MSC GeoMet cannot be reached
     */
    public SeasonalOutlookResponse outlook(double lat, double lon) {
        log.trace("Entering outlook()");
        try {
            if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
                throw new InvalidRequestException("lat must be -90 to 90 and lon -180 to 180");
            }
            // The grid is about 1 degree; rounding to half a degree lets nearby fields share an answer.
            String cell = String.format(Locale.ROOT, "%.1f,%.1f", Math.round(lat * 2) / 2.0, Math.round(lon * 2) / 2.0);
            Cached hit = cache.get(cell);
            if (hit != null && System.nanoTime() - hit.storedNanos() < cacheTtl.toNanos()) {
                log.debug("Returning cached seasonal outlook");
                return withLocation(hit.response(), lat, lon);
            }
            SeasonalOutlookResponse response = fetch(lat, lon);
            cache.put(cell, new Cached(System.nanoTime(), response));
            return response;
        } finally {
            log.trace("Exiting outlook()");
        }
    }

    /**
     * Reads the newest run's periods from GeoMet.
     *
     * @param lat latitude
     * @param lon longitude
     * @return the outlook
     */
    private SeasonalOutlookResponse fetch(double lat, double lon) {
        log.debug("Requesting the CanSIPS seasonal outlook from MSC GeoMet");
        var location = new SeasonalOutlookResponse.Location(lat, lon);
        Optional<PointValue> newest = client.valueAt(layer(TEMPERATURE, "Above"), lat, lon, null, null);
        if (newest.isEmpty() || newest.get().time() == null || newest.get().referenceTime() == null) {
            return new SeasonalOutlookResponse(location, null, List.of(), SOURCE,
                    "No seasonal outlook is available at this location. " + NOTE);
        }
        String run = newest.get().referenceTime();
        YearMonth runMonth = YearMonth.parse(run.substring(0, 7));

        record Job(int offset, String variable, String category, CompletableFuture<Optional<PointValue>> value) {
        }
        List<Job> jobs = new ArrayList<>();
        for (int offset : PERIOD_OFFSETS) {
            String time = runMonth.plusMonths(offset) + "-01T00:00:00Z";
            for (String variable : List.of(TEMPERATURE, PRECIPITATION)) {
                for (String category : CATEGORIES) {
                    jobs.add(new Job(offset, variable, category, CompletableFuture.supplyAsync(
                            () -> client.valueAt(layer(variable, category), lat, lon, time, run), executor)));
                }
            }
        }
        List<Period> periods = new ArrayList<>();
        try {
            for (int offset : PERIOD_OFFSETS) {
                YearMonth start = runMonth.plusMonths(offset);
                YearMonth end = start.plusMonths(2);
                periods.add(new Period(
                        start.toString(),
                        end.toString(),
                        start.format(MONTH_LABEL) + " - " + end.format(MONTH_LABEL),
                        chances(jobs.stream().filter(j -> j.offset() == offset && j.variable().equals(TEMPERATURE))
                                .map(j -> j.value().join().map(PointValue::value).orElse(null)).toList()),
                        chances(jobs.stream().filter(j -> j.offset() == offset && j.variable().equals(PRECIPITATION))
                                .map(j -> j.value().join().map(PointValue::value).orElse(null)).toList())));
            }
        } catch (CompletionException e) {
            if (e.getCause() instanceof ExternalDataException external) {
                throw external;
            }
            throw e;
        }
        return new SeasonalOutlookResponse(location, runMonth.toString(), periods, SOURCE, NOTE);
    }

    /**
     * Builds the chances for one variable and period.
     *
     * @param values above, near and below probabilities in that order; any may be {@code null}
     * @return the chances and which is most likely
     */
    static Chances chances(List<Double> values) {
        Double above = round(values.get(0));
        Double near = round(values.get(1));
        Double below = round(values.get(2));
        if (above == null || near == null || below == null) {
            return new Chances(above, near, below, null);
        }
        double highest = Math.max(above, Math.max(near, below));
        String mostLikely = highest < CLEAR_SIGNAL_PCT
                ? "no clear signal"
                : highest == above ? "above" : highest == below ? "below" : "near";
        return new Chances(above, near, below, mostLikely);
    }

    /**
     * Builds a CanSIPS three-month probability layer name.
     *
     * @param variable {@code AirTemp} or {@code PrecipAccum}
     * @param category {@code Above}, {@code Near} or {@code Below}
     * @return the layer name
     */
    static String layer(String variable, String category) {
        return LAYER.formatted(variable, category);
    }

    /**
     * Returns a cached answer with the caller's own coordinates.
     *
     * @param response cached answer for the grid cell
     * @param lat      latitude asked about
     * @param lon      longitude asked about
     * @return the answer for this caller
     */
    private static SeasonalOutlookResponse withLocation(SeasonalOutlookResponse response, double lat, double lon) {
        return new SeasonalOutlookResponse(new SeasonalOutlookResponse.Location(lat, lon),
                response.issued(), response.periods(), response.source(), response.note());
    }

    /**
     * Rounds a probability to one decimal place.
     *
     * @param value probability in percent, or {@code null}
     * @return the rounded value, or {@code null}
     */
    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
