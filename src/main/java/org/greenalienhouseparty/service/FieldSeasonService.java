package org.greenalienhouseparty.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.FieldSeasonResponse;
import org.greenalienhouseparty.dto.FieldSeasonResponse.Point;
import org.greenalienhouseparty.dto.FieldSeasonResponse.Gap;
import org.greenalienhouseparty.dto.HlsAcquisitionResponse;
import org.greenalienhouseparty.dto.HlsAcquisitionResponse.Observation;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Prepares acquired HLS measurements for display without diagnosing harvest, stress or crop identity. */
@Service
public class FieldSeasonService {
    private static final int MAX_LINE_GAP_DAYS = 20;
    private final DataBackendClient client;
    private final Clock clock;

    /** Creates a production service using the application calendar. */
    @Autowired
    public FieldSeasonService(DataBackendClient client) {
        this(client, Clock.systemUTC());
    }

    /** Creates a service with a fixed clock for deterministic verification. */
    FieldSeasonService(DataBackendClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * Chooses a full calendar observation period and polls acquisition without waiting for its completion.
     * With pixels, points carry display arrays only for observations after {@code since}, so a polling
     * client receives each picture once; summaries always cover every observation.
     */
    public FieldSeasonResponse fieldSeason(String fieldId, Integer requestedYear, boolean retry, boolean pixels, int since) {
        if (since < 0) throw new InvalidRequestException("since must be zero or more");
        LocalDate today = LocalDate.now(clock);
        int year = requestedYear == null ? Year.now(clock).getValue() : requestedYear;
        if (year < 2013 || year > today.getYear()) throw new InvalidRequestException("year must be from 2013 through the current year");
        LocalDate start = year == 2013 ? LocalDate.of(2013, 4, 11) : LocalDate.of(year, 1, 1);
        LocalDate end = year == today.getYear() ? today : LocalDate.of(year, 12, 31);
        return prepare(fieldId, year, client.fieldSeason(fieldId, start, end, retry, pixels), pixels, since);
    }

    /** Applies quality rules, calendar ordering, daily deduplication and gap-aware line construction. */
    FieldSeasonResponse prepare(String fieldId, int year, HlsAcquisitionResponse raw, boolean pixels, int since) {
        TreeMap<LocalDate, Point> days = new TreeMap<>();
        List<Observation> observations = raw.observations() == null ? List.of() : raw.observations();
        for (int index = 0; index < observations.size(); index++) {
            Observation observation = observations.get(index);
            if (observation.date() == null || observation.date().isBefore(raw.period().start()) || observation.date().isAfter(raw.period().end())) continue;
            // Python appends observations in acquisition order, so an index at or after since is new to the client.
            Point point = point(observation, pixels && index >= since);
            days.merge(point.date(), point, (a, b) -> support(a, b) >= 0 ? a : b);
        }
        List<Point> points = List.copyOf(days.values());
        List<Point> clear = points.stream().filter(point -> point.quality().equals("clear")).toList();
        List<List<Point>> segments = new ArrayList<>();
        List<Point> segment = new ArrayList<>();
        for (Point point : clear) {
            if (!segment.isEmpty() && ChronoUnit.DAYS.between(segment.getLast().date(), point.date()) > MAX_LINE_GAP_DAYS) {
                segments.add(List.copyOf(segment));
                segment.clear();
            }
            segment.add(point);
        }
        if (!segment.isEmpty()) segments.add(List.copyOf(segment));
        List<Gap> gaps = new ArrayList<>();
        LocalDate prior = raw.period().start();
        long longest = 0;
        for (Point point : clear) {
            long interval = ChronoUnit.DAYS.between(prior, point.date());
            longest = Math.max(longest, interval);
            if (interval > MAX_LINE_GAP_DAYS) gaps.add(new Gap(prior, point.date(), interval));
            prior = point.date();
        }
        long tail = ChronoUnit.DAYS.between(prior, raw.period().end());
        longest = Math.max(longest, tail);
        if (tail > MAX_LINE_GAP_DAYS) gaps.add(new Gap(prior, raw.period().end(), tail));
        Point peak = clear.stream().max(Comparator.comparing(Point::ndvi)).orElse(null);
        Point latest = clear.isEmpty() ? null : clear.getLast();
        int partial = (int) points.stream().filter(point -> point.quality().equals("partial")).count();
        var summary = new FieldSeasonResponse.Summary(clear.size(), partial, points.size() - clear.size() - partial,
                peak == null ? null : peak.ndvi(), peak == null ? null : peak.date(),
                latest == null ? null : latest.ndvi(), latest == null ? null : latest.date(), longest);
        double axisMin = points.stream().filter(point -> point.ndvi() != null).mapToDouble(Point::ndvi).min().orElse(0);
        List<Double> ticks = axisMin < 0 ? List.of(-1.0, 0.0, 1.0) : List.of(0.0, 0.5, 1.0);
        int failed = raw.failures() == null ? 0 : raw.failures().size();
        String message = message(raw, clear.size(), failed);
        List<String> notes = new ArrayList<>(List.of(
                "Greenness (NDVI) measures vegetation reflectance, not crop identity, yield or the cause of a change.",
                "Solid dots require at least 70% valid sample pixels, 90% field coverage and three measured pixels. Partial observations are hollow dots and excluded from summaries.",
                "Lines stop across gaps longer than 20 days. Grey ticks mark passes without a usable land-greenness measurement.",
                "Cloud, snow and water are reported separately. Spectral bands are not read when fewer than 20% of sampled land pixels are clear."));
        if (raw.grid() != null && !Boolean.TRUE.equals(raw.grid().interiorOnly())) notes.add(
                "This field has too few interior pixels to exclude its edges; measurements may mix neighbouring land.");
        return new FieldSeasonResponse(fieldId, year, raw.jobId(), raw.status(), message, raw.period(), raw.progress(),
                raw.grid(), points, List.copyOf(segments), List.copyOf(gaps), summary, ticks, List.copyOf(notes), raw.source(), failed, raw.error(),
                raw.count() == null ? observations.size() : raw.count(), pixels ? raw.encoding() : null);
    }

    /** Preserves valid measured NDVI, while excluding unsupported observations from the plot and summaries. */
    private static Point point(Observation observation, boolean withPixels) {
        double coverage = fraction(observation.coverageShare()), clear = fraction(observation.sampleClearShare());
        int pixels = observation.validPixels() == null ? 0 : observation.validPixels();
        boolean measured = observation.ndviMean() != null && Double.isFinite(observation.ndviMean())
                && observation.ndviMean() >= -1 && observation.ndviMean() <= 1 && pixels > 0;
        String quality = coverage == 0 ? "unobserved"
                : fraction(observation.snowShare()) >= 0.5 ? "snow"
                : fraction(observation.waterShare()) >= 0.5 ? "water"
                : measured && clear >= 0.7 && coverage >= 0.9 && pixels >= 3 ? "clear"
                : measured && clear >= 0.2 ? "partial" : "cloudy";
        boolean plotted = quality.equals("clear") || quality.equals("partial");
        String description = switch (quality) {
            case "clear" -> "Clear land measurement";
            case "partial" -> "Partial measurement; excluded from the season summary";
            case "snow" -> "Mostly snow-covered; no land-greenness value plotted";
            case "water" -> "Mostly water; no land-greenness value plotted";
            case "unobserved" -> "Satellite swath did not image this field";
            default -> "Insufficient clear land pixels; no greenness value plotted";
        };
        return new Point(observation.date(), observation.sceneId(), observation.sensor(), quality, description,
                plotted ? observation.ndviMean() : null, plotted ? observation.ndviP10() : null,
                plotted ? observation.ndviP90() : null, clear, fraction(observation.cloudShare()),
                fraction(observation.snowShare()), fraction(observation.waterShare()), coverage, pixels,
                plotted ? observation.ndviP50() : null, withPixels ? observation.pixels() : null);
    }

    /** Chooses daily evidence by quality, valid-pixel share and coverage, never by the largest NDVI. */
    private static int support(Point a, Point b) {
        return Comparator.comparingInt((Point point) -> point.quality().equals("clear") ? 2 : point.quality().equals("partial") ? 1 : 0)
                .thenComparingDouble(Point::clearShare).thenComparingDouble(Point::coverageShare)
                .thenComparing(Point::sceneId).compare(a, b);
    }

    /** Rejects invalid fractions as support rather than treating them as clear observations. */
    private static double fraction(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1 ? value : 0;
    }

    /** Produces status text from actual acquisition counters and measured support. */
    private static String message(HlsAcquisitionResponse raw, int clear, int failed) {
        if (raw.status().equals("failed")) return "Satellite observations could not be loaded. You can retry without changing your crop answer.";
        if (raw.progress().total() == null) return "Finding Landsat and Sentinel-2 passes for this field.";
        if (raw.status().equals("queued") || raw.status().equals("running")) return "Checking satellite passes: "
                + raw.progress().processed() + " of " + raw.progress().total() + ". Measurements appear as they arrive.";
        if (raw.grid() != null && raw.grid().fieldPixels() == 0) return "This outline contains no native 30 m pixel centres; no field measurement is available.";
        if (raw.progress().total() == 0) return "No HLS observations were found for this field and period.";
        return clear + " clear observation" + (clear == 1 ? "" : "s") + " available."
                + (failed > 0 ? " " + failed + " satellite passes could not be read." : "");
    }
}
