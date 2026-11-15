package org.greenalienhouseparty.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.dto.AgroWeatherResponse;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.DroughtIndicatorsResponse;
import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.dto.FieldDetailsResponse.Location;
import org.greenalienhouseparty.dto.FieldDetailsResponse.Section;
import org.greenalienhouseparty.dto.FieldGreennessResponse;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.dto.VegetationHistoryResponse;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Gathers everything known about one stored field into a single response.
 *
 * <p>The field's size is looked up first, so an unknown field is still a 404.
 * The data backend sources are then fetched at the same time, and the
 * location-based sources (regional soil ratings and seasonal outlook) at the
 * field centre once it is known. A source that fails is reported as
 * unavailable with the reason and never fails the whole request.</p>
 *
 * <p>Greenness, vegetation history and drought indicators can take tens of
 * seconds the first time a field is asked for, so they are only fetched when
 * requested.</p>
 */
@Service
public class FieldDetailsService {

    private static final Logger log = LoggerFactory.getLogger(FieldDetailsService.class);

    /** Request keyword for the Sentinel-2 greenness season. */
    public static final String INCLUDE_GREENNESS = "greenness";

    /** Request keyword for the MODIS vegetation history. */
    public static final String INCLUDE_VEGETATION = "vegetation";

    /** Request keyword for the GRACE drought indicators. */
    public static final String INCLUDE_DROUGHT = "drought";

    /** Request keyword for every optional section. */
    public static final String INCLUDE_ALL = "all";

    private static final List<String> OPTIONAL = List.of(INCLUDE_GREENNESS, INCLUDE_VEGETATION, INCLUDE_DROUGHT);

    private static final String NO_LOCATION = "The field centre is not known because the climate summary, crop "
            + "calendar and agro-weather were all unavailable.";

    /** Enough threads for every source of one request at once; extra requests queue. */
    private static final int PARALLEL = 8;

    private final DataBackendService dataBackend;

    private final SoilLandscapeService soilLandscape;

    private final SeasonalOutlookService seasonalOutlook;

    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLEL);

    /**
     * Creates the service.
     *
     * @param dataBackend     access to the Python data backend
     * @param soilLandscape   regional soil ratings from Agriculture and Agri-Food Canada
     * @param seasonalOutlook seasonal outlook from Environment Canada
     */
    public FieldDetailsService(DataBackendService dataBackend, SoilLandscapeService soilLandscape,
                               SeasonalOutlookService seasonalOutlook) {
        this.dataBackend = dataBackend;
        this.soilLandscape = soilLandscape;
        this.seasonalOutlook = seasonalOutlook;
    }

    /**
     * Stops the request threads when the application shuts down.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns everything known about a stored field.
     *
     * @param fieldId the field identifier
     * @param include optional sections to add: {@code greenness}, {@code vegetation}, {@code drought} or
     *                {@code all}, comma-separated; {@code null} or blank for none
     * @return the field's size and one section per data source, with the status of each
     * @throws InvalidRequestException when {@code include} names an unknown section
     * @throws DataBackendException    when the field does not exist or its size cannot be read
     */
    public FieldDetailsResponse details(String fieldId, String include) {
        log.trace("Entering details()");
        try {
            Set<String> optional = parseInclude(include);

            // The field's size first, so an unknown field is reported as before
            FieldAreaResponse area = dataBackend.fieldArea(fieldId);

            Map<String, Section> sections = new LinkedHashMap<>();
            var soil = start(() -> dataBackend.fieldSoil(fieldId));
            var cropHistory = start(() -> dataBackend.fieldCropHistory(fieldId));
            var climate = start(() -> dataBackend.fieldSummary(fieldId, null));
            var cropCalendar = start(() -> dataBackend.fieldCropCalendar(fieldId, null, null));
            var agroWeather = start(() -> dataBackend.fieldAgroWeather(fieldId, null));
            var greenness = optional.contains(INCLUDE_GREENNESS)
                    ? start(() -> dataBackend.fieldGreenness(fieldId, null)) : null;
            var vegetation = optional.contains(INCLUDE_VEGETATION)
                    ? start(() -> dataBackend.fieldVegetationHistory(fieldId, null)) : null;
            var drought = optional.contains(INCLUDE_DROUGHT)
                    ? start(() -> dataBackend.fieldDroughtIndicators(fieldId, null, null)) : null;

            FieldSummaryResponse climateResult = finish("climate", climate, sections);
            CropCalendarResponse calendarResult = finish("cropCalendar", cropCalendar, sections);
            AgroWeatherResponse agroWeatherResult = finish("agroWeather", agroWeather, sections);

            // The location-based sources need the field centre, which the sections above provide
            Location location = location(climateResult, calendarResult, agroWeatherResult);
            CompletableFuture<SoilLandscapeResponse> landscape = null;
            CompletableFuture<SeasonalOutlookResponse> outlook = null;
            if (location != null) {
                landscape = start(() -> soilLandscape.landscape(location.lat(), location.lon()));
                outlook = start(() -> seasonalOutlook.outlook(location.lat(), location.lon()));
            }

            FieldSoilResponse soilResult = finish("soil", soil, sections);
            CropHistoryResponse cropHistoryResult = finish("cropHistory", cropHistory, sections);
            SoilLandscapeResponse landscapeResult = landscape == null
                    ? unavailable("soilLandscape", NO_LOCATION, sections)
                    : finish("soilLandscape", landscape, sections);
            SeasonalOutlookResponse outlookResult = outlook == null
                    ? unavailable("seasonalOutlook", NO_LOCATION, sections)
                    : finish("seasonalOutlook", outlook, sections);
            FieldGreennessResponse greennessResult = greenness == null
                    ? notRequested("greenness", INCLUDE_GREENNESS, sections)
                    : finish("greenness", greenness, sections);
            VegetationHistoryResponse vegetationResult = vegetation == null
                    ? notRequested("vegetationHistory", INCLUDE_VEGETATION, sections)
                    : finish("vegetationHistory", vegetation, sections);
            DroughtIndicatorsResponse droughtResult = drought == null
                    ? notRequested("droughtIndicators", INCLUDE_DROUGHT, sections)
                    : finish("droughtIndicators", drought, sections);

            log.debug("Gathered field details with {} of {} sections available",
                    sections.values().stream().filter(section -> Section.OK.equals(section.status())).count(),
                    sections.size());
            return new FieldDetailsResponse(
                    area.id() == null ? fieldId : area.id(),
                    area.hectares(),
                    area.acres(),
                    location,
                    soilResult,
                    cropHistoryResult,
                    climateResult,
                    calendarResult,
                    agroWeatherResult,
                    landscapeResult,
                    outlookResult,
                    greennessResult,
                    vegetationResult,
                    droughtResult,
                    orderedSections(sections));
        } finally {
            log.trace("Exiting details()");
        }
    }

    /**
     * Reads the optional sections requested.
     *
     * @param include comma-separated keywords, or {@code null}
     * @return the requested optional sections
     * @throws InvalidRequestException when a keyword is unknown
     */
    static Set<String> parseInclude(String include) {
        Set<String> requested = new LinkedHashSet<>();
        if (include == null) {
            return requested;
        }
        for (String part : include.split(",")) {
            String keyword = part.strip().toLowerCase(Locale.ROOT);
            if (keyword.isEmpty()) {
                continue;
            }
            if (INCLUDE_ALL.equals(keyword)) {
                requested.addAll(OPTIONAL);
            } else if (OPTIONAL.contains(keyword)) {
                requested.add(keyword);
            } else {
                throw new InvalidRequestException(
                        "include must be a comma-separated list of greenness, vegetation, drought or all");
            }
        }
        return requested;
    }

    /**
     * Finds the field centre: from the climate summary, else the crop calendar,
     * else the agro-weather response.
     *
     * @param climate      climate summary, or {@code null}
     * @param cropCalendar crop calendar, or {@code null}
     * @param agroWeather  agro-weather, or {@code null}
     * @return the centre and where it came from, or {@code null} when none has one
     */
    static Location location(FieldSummaryResponse climate, CropCalendarResponse cropCalendar, AgroWeatherResponse agroWeather) {
        if (climate != null && climate.field() != null && climate.field().centroid() != null
                && climate.field().centroid().lat() != null && climate.field().centroid().lon() != null) {
            return new Location(climate.field().centroid().lat(), climate.field().centroid().lon(), "climate");
        }
        if (cropCalendar != null && cropCalendar.location() != null
                && cropCalendar.location().lat() != null && cropCalendar.location().lon() != null) {
            return new Location(cropCalendar.location().lat(), cropCalendar.location().lon(), "cropCalendar");
        }
        if (agroWeather != null && agroWeather.location() != null
                && agroWeather.location().lat() != null && agroWeather.location().lon() != null) {
            return new Location(agroWeather.location().lat(), agroWeather.location().lon(), "agroWeather");
        }
        return null;
    }

    /**
     * Starts one lookup on the request threads, carrying the caller's logging
     * context (including the request ID sent on to the data backend).
     *
     * @param lookup the lookup
     * @param <T>    the response type
     * @return the pending response
     */
    private <T> CompletableFuture<T> start(Supplier<T> lookup) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            try {
                return lookup.get();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        }, executor);
    }

    /**
     * Waits for one lookup and records its status.
     *
     * @param name     section name
     * @param pending  the pending lookup
     * @param sections status of each section so far
     * @param <T>      the response type
     * @return the response, or {@code null} when it could not be fetched
     */
    private <T> T finish(String name, CompletableFuture<T> pending, Map<String, Section> sections) {
        try {
            T result = pending.join();
            sections.put(name, new Section(Section.OK, null));
            return result;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String detail = reason(cause);
            log.warn("Field section {} is unavailable: {}", name, detail);
            sections.put(name, new Section(Section.UNAVAILABLE, detail));
            return null;
        }
    }

    /**
     * Puts the reason a lookup failed in words for the response.
     *
     * @param cause the failure
     * @return the reason
     */
    private static String reason(Throwable cause) {
        if (cause instanceof DataBackendException failure) {
            String detail = failure.getDetail() != null && failure.getDetail().isString()
                    ? failure.getDetail().asString()
                    : failure.getMessage();
            return "The data backend answered HTTP " + failure.getStatusCode()
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }
        if (cause instanceof ExternalDataException || cause instanceof InvalidRequestException) {
            return cause.getMessage();
        }
        log.error("Unexpected failure while gathering field details", cause);
        return "Unexpected error: " + cause.getClass().getSimpleName();
    }

    /**
     * Records a section that could not be looked up at all.
     *
     * @param name     section name
     * @param detail   why
     * @param sections status of each section so far
     * @param <T>      the response type
     * @return {@code null}
     */
    private static <T> T unavailable(String name, String detail, Map<String, Section> sections) {
        sections.put(name, new Section(Section.UNAVAILABLE, detail));
        return null;
    }

    /**
     * Records an optional section that was not requested.
     *
     * @param name     section name
     * @param keyword  the {@code include} keyword that requests it
     * @param sections status of each section so far
     * @param <T>      the response type
     * @return {@code null}
     */
    private static <T> T notRequested(String name, String keyword, Map<String, Section> sections) {
        sections.put(name, new Section(Section.NOT_REQUESTED, "Add include=" + keyword + " (or include=all) to fetch it."));
        return null;
    }

    /**
     * Orders the section statuses as the sections appear in the response.
     *
     * @param sections status of each section
     * @return the statuses in response order
     */
    private static Map<String, Section> orderedSections(Map<String, Section> sections) {
        Map<String, Section> ordered = new LinkedHashMap<>();
        for (String name : List.of("soil", "cropHistory", "climate", "cropCalendar", "agroWeather", "soilLandscape",
                "seasonalOutlook", "greenness", "vegetationHistory", "droughtIndicators")) {
            ordered.put(name, sections.get(name));
        }
        return ordered;
    }
}
