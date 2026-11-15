package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.dto.CropCalendarRequest;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.dto.SoilRequest;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Builds the rotation planner's {@link Field} input from the data backend's
 * responses, leaving those response models untouched.
 *
 * <p>Where each value comes from:</p>
 * <ul>
 *   <li>pH, texture, organic matter: topsoil estimates ({@code POST /soil}).</li>
 *   <li>area and centroid: field summary ({@code POST /field-summary}).</li>
 *   <li>erosion risk: the field summary's terrain rating, mapped low 0.2, moderate 0.5, high 0.9,
 *       averaged with Agriculture and Agri-Food Canada's regional erosion rating when there is one.</li>
 *   <li>history: the farmer's current crop for this year, then the crop maps
 *       ({@code POST /crop-history}) year by year back from last year.</li>
 *   <li>climate record: the field summary's monthly temperatures and year-by-year rainfall, frost-free
 *       days and crop heat units.</li>
 *   <li>crop seasons: the crop calendar ({@code POST /crop-calendar}).</li>
 *   <li>land class, drainage, soil carbon trend and leftover nitrogen: Agriculture and Agri-Food Canada's
 *       regional soil ratings (Canada only).</li>
 *   <li>first-year rainfall chances: Environment Canada's seasonal outlook, when it shows a clear signal
 *       for the first plan year's growing season.</li>
 * </ul>
 *
 * <p>A missing value, or a data backend call that fails, never fails the plan:
 * a neutral default is used and recorded in {@link NormalizedField#assumptions()}
 * so the farmer can see what the plan did not know.</p>
 */
@Component
public class PlannerInputNormalizer {

    private static final Logger log = LoggerFactory.getLogger(PlannerInputNormalizer.class);

    /** pH assumed when there is no soil estimate: neutral, so no crop is ruled out by a guess. */
    static final double DEFAULT_PH = 6.5;

    /** Organic matter assumed when there is no soil estimate, in percent. */
    static final double DEFAULT_ORGANIC_MATTER_PCT = 3.0;

    /** Erosion risk assumed when there is no terrain rating. */
    static final double DEFAULT_EROSION_RISK = 0.5;

    /** Area assumed when the field area is unknown, so income totals read as per hectare. */
    static final double DEFAULT_AREA_HA = 1.0;

    /** Texture reported when there is no soil estimate. */
    static final String UNKNOWN_TEXTURE = "Unknown";

    private static final Map<String, Double> EROSION_RISK_BY_RATING = Map.of(
            "low", 0.2,
            "moderate", 0.5,
            "high", 0.9);

    /** Erosion risk for each of Agriculture and Agri-Food Canada's erosion risk classes. */
    private static final Map<String, Double> EROSION_RISK_BY_REGIONAL_RATING = Map.of(
            "very low", 0.1,
            "low", 0.2,
            "moderate", 0.5,
            "high", 0.8,
            "very high", 0.95);

    /**
     * Crop calendar key for each catalog crop the calendar covers. Wheat is read as winter wheat, the usual
     * wheat in Ontario. Oats, alfalfa and grapes are not in the calendar.
     */
    private static final Map<String, String> CALENDAR_KEY_BY_CROP_ID = Map.of(
            "corn", "corn",
            "soybeans", "soybeans",
            "wheat", "winter_wheat",
            "barley", "barley",
            "canola", "canola",
            "potatoes", "potatoes");

    private static final String EROSION_INDICATOR = "erosionRisk";

    private static final String CARBON_INDICATOR = "organicCarbonChange";

    private static final String NITROGEN_INDICATOR = "residualSoilNitrogen";

    private final DataBackendService dataBackend;

    private final CropRepository crops;

    private final SoilLandscapeService soilLandscape;

    private final SeasonalOutlookService seasonalOutlook;

    private final Clock clock;

    /**
     * Creates the normalizer using the system clock.
     *
     * @param dataBackend     access to the Python data backend
     * @param crops           crop catalog used to recognise crops in a field's history
     * @param soilLandscape   regional soil ratings from Agriculture and Agri-Food Canada
     * @param seasonalOutlook seasonal outlook from Environment Canada
     */
    @Autowired
    public PlannerInputNormalizer(DataBackendService dataBackend, CropRepository crops,
                                  SoilLandscapeService soilLandscape, SeasonalOutlookService seasonalOutlook) {
        this(dataBackend, crops, soilLandscape, seasonalOutlook, Clock.systemDefaultZone());
    }

    /**
     * Creates the normalizer with an explicit clock for repeatable testing.
     *
     * @param dataBackend     access to the Python data backend
     * @param crops           crop catalog used to recognise crops in a field's history
     * @param soilLandscape   regional soil ratings from Agriculture and Agri-Food Canada
     * @param seasonalOutlook seasonal outlook from Environment Canada
     * @param clock           calendar source used to decide which year is "last year"
     */
    PlannerInputNormalizer(DataBackendService dataBackend, CropRepository crops, SoilLandscapeService soilLandscape,
                           SeasonalOutlookService seasonalOutlook, Clock clock) {
        this.dataBackend = dataBackend;
        this.crops = crops;
        this.soilLandscape = soilLandscape;
        this.seasonalOutlook = seasonalOutlook;
        this.clock = clock;
    }

    /**
     * Fetches soil, crop history, the environmental summary, the crop calendar,
     * regional soil ratings and the seasonal outlook for a field's outline and
     * turns them into planner input.
     *
     * <p>The lookups run one after another. A lookup that fails is logged and
     * treated as missing data. The regional soil ratings and the outlook are
     * looked up at the field's centre, so they are skipped when the summary
     * that gives the centre is missing.</p>
     *
     * @param request field identifier, outline and the farmer's current crop
     * @return planner input and the assumptions made to build it
     */
    public NormalizedField normalize(CropOptimizationRequest.FieldRequest request) {
        log.trace("Entering normalize()");
        try {
            JsonNode outline = request.geometry();

            SoilRequest soilRequest = new SoilRequest();
            soilRequest.setPolygon(outline);
            FieldSoilResponse soil = fetch("topsoil", () -> dataBackend.soil(soilRequest));

            CropHistoryRequest historyRequest = new CropHistoryRequest();
            historyRequest.setPolygon(outline);
            CropHistoryResponse history = fetch("crop history", () -> dataBackend.cropHistory(historyRequest));

            FieldSummaryRequest summaryRequest = new FieldSummaryRequest();
            summaryRequest.setPolygon(outline);
            FieldSummaryResponse summary = fetch("field summary", () -> dataBackend.fieldSummary(summaryRequest));

            CropCalendarRequest calendarRequest = new CropCalendarRequest();
            calendarRequest.setPolygon(outline);
            CropCalendarResponse calendar = fetch("crop calendar", () -> dataBackend.cropCalendar(calendarRequest));

            SoilLandscapeResponse landscape = null;
            SeasonalOutlookResponse outlook = null;
            FieldSummaryResponse.Centroid centre = summary == null || summary.field() == null ? null : summary.field().centroid();
            if (centre != null && centre.lat() != null && centre.lon() != null) {
                double lat = centre.lat();
                double lon = centre.lon();
                landscape = fetch("regional soil ratings", () -> soilLandscape.landscape(lat, lon));
                outlook = fetch("seasonal outlook", () -> seasonalOutlook.outlook(lat, lon));
            }

            Set<String> growableCropIds = new LinkedHashSet<>();
            if (request.growableCrops() != null) {
                for (String crop : request.growableCrops()) {
                    crops.find(crop).map(Crop::id).ifPresent(growableCropIds::add);
                }
            }
            int firstPlanYear = PlanYears.firstPlanYear(clock, request.readyDate());

            return toField(request.fieldId(), request.currentCrop(), soil, history, summary, calendar, landscape, outlook,
                    firstPlanYear, growableCropIds);
        } finally {
            log.trace("Exiting normalize()");
        }
    }

    /**
     * Turns the three core data backend responses into planner input, with no
     * crop calendar, regional soil ratings or seasonal outlook.
     *
     * @param fieldId     field identifier
     * @param currentCrop id or display name of the crop growing this year, as stated by the farmer
     * @param soil        topsoil estimates, or {@code null} when unavailable
     * @param history     crop-map history, or {@code null} when unavailable
     * @param summary     environmental summary, or {@code null} when unavailable
     * @return planner input and the assumptions made to build it
     */
    public NormalizedField toField(
            String fieldId,
            String currentCrop,
            FieldSoilResponse soil,
            CropHistoryResponse history,
            FieldSummaryResponse summary) {
        return toField(fieldId, currentCrop, soil, history, summary, null, null, null,
                PlanYears.firstPlanYear(clock, null), null);
    }

    /**
     * Turns data backend responses into planner input. Any response, and any
     * value inside one, may be {@code null}.
     *
     * @param fieldId     field identifier
     * @param currentCrop id or display name of the crop growing this year, as stated by the farmer
     * @param soil        topsoil estimates, or {@code null} when unavailable
     * @param history     crop-map history, or {@code null} when unavailable
     * @param summary     environmental summary, or {@code null} when unavailable
     * @param calendar    crop calendar around the field, or {@code null} when unavailable
     * @param landscape   regional soil ratings, or {@code null} when unavailable
     * @param outlook     seasonal outlook, or {@code null} when unavailable
     * @param firstPlanYear calendar year of the first planned year, used to match the outlook to its season
     * @param growableCropIds crops the farmer can grow, used to keep notes to the crops that matter;
     *                    {@code null} for every crop
     * @return planner input and the assumptions made to build it
     */
    public NormalizedField toField(
            String fieldId,
            String currentCrop,
            FieldSoilResponse soil,
            CropHistoryResponse history,
            FieldSummaryResponse summary,
            CropCalendarResponse calendar,
            SoilLandscapeResponse landscape,
            SeasonalOutlookResponse outlook,
            int firstPlanYear,
            Set<String> growableCropIds) {
        log.trace("Entering toField()");
        try {
            List<String> assumptions = new ArrayList<>();

            Double ph = soil == null || soil.topsoil() == null ? null : soil.topsoil().ph();
            if (ph == null) {
                ph = DEFAULT_PH;
                assumptions.add("No soil pH estimate was available for this field, so a neutral pH of "
                        + DEFAULT_PH + " was assumed.");
            }
            Double organicMatter = soil == null || soil.derived() == null ? null : soil.derived().organicMatterPct();
            if (organicMatter == null) {
                organicMatter = DEFAULT_ORGANIC_MATTER_PCT;
                assumptions.add("No organic matter estimate was available for this field, so "
                        + DEFAULT_ORGANIC_MATTER_PCT + "% was assumed.");
            }
            String texture = soil == null || soil.derived() == null || soil.derived().textureClass() == null
                    ? UNKNOWN_TEXTURE
                    : soil.derived().textureClass();

            FieldSummaryResponse.FieldInfo info = summary == null ? null : summary.field();
            Double area = info == null ? null : info.areaHa();
            if (area == null) {
                area = DEFAULT_AREA_HA;
                assumptions.add("The field's area was not available, so income totals are for one hectare.");
            }
            FieldSummaryResponse.Centroid centroid = info == null ? null : info.centroid();
            double lat = centroid == null || centroid.lat() == null ? Double.NaN : centroid.lat();
            double lon = centroid == null || centroid.lon() == null ? Double.NaN : centroid.lon();

            // Regional soil ratings only count when the field is inside the area they cover
            boolean regionalRatings = landscape != null && landscape.coverage() != null;

            String rating = summary == null || summary.terrain() == null ? null : summary.terrain().erosionRating();
            Double terrainRisk = rating == null ? null : EROSION_RISK_BY_RATING.get(rating.toLowerCase(Locale.ROOT));
            String regionalRating = regionalRatings ? indicatorRating(landscape, EROSION_INDICATOR) : null;
            Double regionalRisk = regionalRating == null
                    ? null
                    : EROSION_RISK_BY_REGIONAL_RATING.get(regionalRating.strip().toLowerCase(Locale.ROOT));
            Double erosionRisk;
            if (terrainRisk != null && regionalRisk != null) {
                // The terrain rating is for this field's slopes; the regional rating reflects how the area is farmed
                erosionRisk = (terrainRisk + regionalRisk) / 2;
            } else if (terrainRisk != null) {
                erosionRisk = terrainRisk;
            } else if (regionalRisk != null) {
                erosionRisk = regionalRisk;
            } else {
                erosionRisk = DEFAULT_EROSION_RISK;
                assumptions.add("No terrain rating was available for this field, so a moderate erosion risk was assumed.");
            }

            List<String> cropHistory = history(currentCrop, history, assumptions);

            FieldConditions conditions = conditions(summary, calendar, regionalRatings ? landscape : null, outlook,
                    firstPlanYear, growableCropIds, assumptions);

            Field field = new Field(fieldId, lat, lon, area, ph, texture, organicMatter, erosionRisk, cropHistory, conditions);
            log.debug("Normalized field with {} history years and {} assumptions", cropHistory.size(), assumptions.size());
            return new NormalizedField(field, List.copyOf(assumptions));
        } finally {
            log.trace("Exiting toField()");
        }
    }

    /**
     * Gathers the field's climate record, crop seasons, regional soil ratings and
     * first-year rainfall chances. Anything missing is left empty and noted.
     *
     * @param summary         environmental summary, or {@code null}
     * @param calendar        crop calendar around the field, or {@code null}
     * @param landscape       regional soil ratings covering the field, or {@code null}
     * @param outlook         seasonal outlook, or {@code null}
     * @param firstPlanYear   calendar year of the first planned year
     * @param growableCropIds crops the farmer can grow, or {@code null} for every crop
     * @param assumptions     list to add a note to for each group of values that is missing
     * @return the field's conditions
     */
    private FieldConditions conditions(
            FieldSummaryResponse summary,
            CropCalendarResponse calendar,
            SoilLandscapeResponse landscape,
            SeasonalOutlookResponse outlook,
            int firstPlanYear,
            Set<String> growableCropIds,
            List<String> assumptions) {

        // Climate record
        Double[] monthlyMeanTempC = new Double[12];
        boolean anyTemperature = false;
        if (summary != null && summary.monthly() != null) {
            for (FieldSummaryResponse.MonthlyRow row : summary.monthly()) {
                if (row != null && row.month() != null && row.month() >= 1 && row.month() <= 12 && row.tmeanC() != null) {
                    monthlyMeanTempC[row.month() - 1] = row.tmeanC();
                    anyTemperature = true;
                }
            }
        }
        List<Integer> seasonMonths = new ArrayList<>();
        if (summary != null && summary.season() != null && summary.season().months() != null) {
            for (Integer month : summary.season().months()) {
                if (month != null && month >= 1 && month <= 12) {
                    seasonMonths.add(month);
                }
            }
        }
        List<Double> yearlyRainfallMm = new ArrayList<>();
        List<Double> yearlyFrostFreeDays = new ArrayList<>();
        List<Double> yearlyHeatUnits = new ArrayList<>();
        if (summary != null && summary.yearly() != null && summary.yearly().years() != null) {
            for (FieldSummaryResponse.YearRow row : summary.yearly().years()) {
                if (row == null) {
                    continue;
                }
                if (row.precipMm() != null) {
                    yearlyRainfallMm.add(row.precipMm());
                }
                if (row.frostFreeDays() != null) {
                    yearlyFrostFreeDays.add(row.frostFreeDays());
                }
                if (row.chuAvailable() != null) {
                    yearlyHeatUnits.add(row.chuAvailable());
                }
            }
        }
        Double seasonRainfallMm = summary == null || summary.climate() == null
                ? null
                : summary.climate().growingSeasonPrecipMmMedian();
        if (!anyTemperature || yearlyRainfallMm.isEmpty()) {
            assumptions.add("No complete climate record was available for this field, so crops were not fully checked "
                    + "against its temperature, rainfall and season length.");
        }

        // Crop seasons
        Map<String, FieldConditions.CropSeason> cropSeasons = new LinkedHashMap<>();
        if (calendar != null && calendar.crops() != null) {
            Map<String, CropCalendarResponse.CropSeason> byKey = new HashMap<>();
            for (CropCalendarResponse.CropSeason season : calendar.crops()) {
                if (season != null && season.crop() != null) {
                    byKey.put(season.crop(), season);
                }
            }
            for (Crop crop : crops.findAll()) {
                String key = CALENDAR_KEY_BY_CROP_ID.get(crop.id());
                CropCalendarResponse.CropSeason season = key == null ? null : byKey.get(key);
                if (season == null || season.plantingDay() == null || season.maturityDay() == null
                        || season.growingSeasonDays() == null) {
                    continue;
                }
                boolean crossesNewYear = Boolean.TRUE.equals(season.crossesNewYear());
                cropSeasons.put(crop.id(), new FieldConditions.CropSeason(
                        season.plantingDay(), season.maturityDay(), season.growingSeasonDays(), crossesNewYear));
                boolean ofInterest = growableCropIds == null || growableCropIds.contains(crop.id());
                if (crossesNewYear && !"wheat".equals(crop.id()) && ofInterest) {
                    assumptions.add("The crop calendar gives " + crop.name().toLowerCase(Locale.ROOT)
                            + " as sown in autumn around this field. Its ground cover is counted from those dates, "
                            + "so check them against local practice.");
                }
            }
        }
        if (cropSeasons.isEmpty()) {
            assumptions.add("No crop calendar was available for this field, so ground cover uses typical months for "
                    + "each crop rather than local planting and harvest dates.");
        }

        // Regional soil ratings
        String landClass = null;
        String drainageCode = null;
        boolean residualNitrogenHigh = false;
        boolean organicCarbonDeclining = false;
        if (landscape == null) {
            assumptions.add("No regional soil ratings were available for this field (they cover Canada only), so land "
                    + "class, drainage, soil carbon trend and leftover nitrogen were not considered.");
        } else {
            landClass = landscape.landCapability() == null ? null : landscape.landCapability().classCode();
            drainageCode = landscape.drainage() == null ? null : landscape.drainage().code();
            String nitrogenRating = indicatorRating(landscape, NITROGEN_INDICATOR);
            residualNitrogenHigh = nitrogenRating != null && nitrogenRating.toLowerCase(Locale.ROOT).contains("high");
            SoilLandscapeResponse.Indicator carbon = indicator(landscape, CARBON_INDICATOR);
            if (carbon != null) {
                organicCarbonDeclining = carbon.rating() != null
                        ? carbon.rating().toLowerCase(Locale.ROOT).contains("decrease")
                        : carbon.value() != null && carbon.value() < 0;
            }
        }

        FieldConditions.RainfallChances firstYearRainfall = firstYearRainfall(outlook, firstPlanYear, seasonMonths);

        return new FieldConditions(
                anyTemperature ? Collections.unmodifiableList(Arrays.asList(monthlyMeanTempC)) : List.of(),
                List.copyOf(seasonMonths),
                List.copyOf(yearlyRainfallMm),
                List.copyOf(yearlyFrostFreeDays),
                List.copyOf(yearlyHeatUnits),
                seasonRainfallMm,
                Collections.unmodifiableMap(cropSeasons),
                landClass,
                drainageCode,
                residualNitrogenHigh,
                organicCarbonDeclining,
                firstYearRainfall);
    }

    /**
     * Finds the seasonal outlook's rainfall chances for the first plan year's
     * growing season: the outlook period that shares the most months with that
     * season, provided it shows a clear signal.
     *
     * @param outlook       seasonal outlook, or {@code null}
     * @param firstPlanYear calendar year of the first planned year
     * @param seasonMonths  the field's growing-season months
     * @return the chances as fractions, or {@code null} when no period covers the season or none shows a signal
     */
    private static FieldConditions.RainfallChances firstYearRainfall(
            SeasonalOutlookResponse outlook, int firstPlanYear, List<Integer> seasonMonths) {
        if (outlook == null || outlook.periods() == null || seasonMonths.isEmpty()) {
            return null;
        }
        SeasonalOutlookResponse.Period best = null;
        int mostSharedMonths = 0;
        for (SeasonalOutlookResponse.Period period : outlook.periods()) {
            if (period == null || period.startMonth() == null || period.endMonth() == null) {
                continue;
            }
            int sharedMonths = 0;
            try {
                YearMonth last = YearMonth.parse(period.endMonth());
                for (YearMonth month = YearMonth.parse(period.startMonth()); !month.isAfter(last); month = month.plusMonths(1)) {
                    if (month.getYear() == firstPlanYear && seasonMonths.contains(month.getMonthValue())) {
                        sharedMonths++;
                    }
                }
            } catch (DateTimeParseException e) {
                continue;
            }
            if (sharedMonths > mostSharedMonths) {
                mostSharedMonths = sharedMonths;
                best = period;
            }
        }
        SeasonalOutlookResponse.Chances chances = best == null ? null : best.precipitation();
        if (chances == null || chances.belowPct() == null || chances.nearPct() == null || chances.abovePct() == null
                || chances.mostLikely() == null || chances.mostLikely().equalsIgnoreCase("no clear signal")) {
            return null;
        }
        double total = chances.belowPct() + chances.nearPct() + chances.abovePct();
        if (total <= 0) {
            return null;
        }
        return new FieldConditions.RainfallChances(
                chances.belowPct() / total, chances.nearPct() / total, chances.abovePct() / total);
    }

    /**
     * Finds one regional soil indicator.
     *
     * @param landscape regional soil ratings
     * @param key       indicator key, for example {@code erosionRisk}
     * @return the indicator, or {@code null} when the response does not have it
     */
    private static SoilLandscapeResponse.Indicator indicator(SoilLandscapeResponse landscape, String key) {
        if (landscape.indicators() == null) {
            return null;
        }
        for (SoilLandscapeResponse.Indicator indicator : landscape.indicators()) {
            if (indicator != null && key.equals(indicator.key())) {
                return indicator;
            }
        }
        return null;
    }

    /**
     * Finds the latest rating of one regional soil indicator.
     *
     * @param landscape regional soil ratings
     * @param key       indicator key
     * @return the rating, for example {@code Moderate}, or {@code null}
     */
    private static String indicatorRating(SoilLandscapeResponse landscape, String key) {
        SoilLandscapeResponse.Indicator indicator = indicator(landscape, key);
        return indicator == null ? null : indicator.rating();
    }

    /**
     * Builds the crop sequence, most recent first: the farmer's current crop
     * for this year, then one entry per year back from last year to the first
     * mapped year. A year with no map, a non-crop class or a crop outside the
     * catalog becomes {@link Field#NO_CROP} so the years after it keep their place.
     *
     * @param currentCrop id or display name of the crop growing this year
     * @param history     crop-map history, or {@code null}
     * @param assumptions list to add a note to when there is no usable history
     * @return crop ids, most recent first
     */
    private List<String> history(String currentCrop, CropHistoryResponse history, List<String> assumptions) {
        List<String> sequence = new ArrayList<>();
        sequence.add(crops.find(currentCrop).map(Crop::id).orElse(Field.NO_CROP));

        Map<Integer, CropHistoryResponse.CropYear> byYear = new HashMap<>();
        if (history != null && history.years() != null) {
            for (CropHistoryResponse.CropYear year : history.years()) {
                if (year != null && year.year() != null) {
                    byYear.put(year.year(), year);
                }
            }
        }
        if (byYear.isEmpty()) {
            assumptions.add("No crop map history was available for this field, so only the current crop was considered.");
            return List.copyOf(sequence);
        }

        int lastYear = Year.now(clock).getValue() - 1;
        int firstYear = byYear.keySet().stream().mapToInt(Integer::intValue).min().orElse(lastYear);
        for (int year = lastYear; year >= firstYear; year--) {
            sequence.add(cropIdFor(byYear.get(year)));
        }
        return List.copyOf(sequence);
    }

    /**
     * Finds the catalog crop id for one mapped year.
     *
     * @param year the mapped year, or {@code null} when that year has no map
     * @return a crop id from the catalog, or {@link Field#NO_CROP}
     */
    private String cropIdFor(CropHistoryResponse.CropYear year) {
        if (year == null || Boolean.FALSE.equals(year.isCrop())) {
            return Field.NO_CROP;
        }
        return CropHistoryMapper.cropId(year.crop(), year.group())
                .flatMap(crops::find)
                .map(Crop::id)
                .orElse(Field.NO_CROP);
    }

    /**
     * Runs one lookup, treating a failure of the data backend or of an outside
     * service as missing data.
     *
     * @param what   what is being fetched, for the log
     * @param lookup the lookup to run
     * @param <T>    the response type
     * @return the response, or {@code null} when the lookup failed
     */
    private <T> T fetch(String what, Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (DataBackendException e) {
            log.warn("Planning without {}: the data backend answered HTTP {}", what, e.getStatusCode());
            return null;
        } catch (ExternalDataException | InvalidRequestException e) {
            log.warn("Planning without {}: {}", what, e.getMessage());
            return null;
        }
    }
}
