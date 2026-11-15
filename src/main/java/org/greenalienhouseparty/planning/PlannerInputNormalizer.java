package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SoilRequest;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Field;
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
 *   <li>erosion risk: the field summary's terrain rating, mapped low 0.2, moderate 0.5, high 0.9.</li>
 *   <li>history: the farmer's current crop for this year, then the crop maps
 *       ({@code POST /crop-history}) year by year back from last year.</li>
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

    private final DataBackendService dataBackend;

    private final CropRepository crops;

    private final Clock clock;

    /**
     * Creates the normalizer using the system clock.
     *
     * @param dataBackend access to the Python data backend
     * @param crops       crop catalog used to recognise crops in a field's history
     */
    @Autowired
    public PlannerInputNormalizer(DataBackendService dataBackend, CropRepository crops) {
        this(dataBackend, crops, Clock.systemDefaultZone());
    }

    /**
     * Creates the normalizer with an explicit clock for repeatable testing.
     *
     * @param dataBackend access to the Python data backend
     * @param crops       crop catalog used to recognise crops in a field's history
     * @param clock       calendar source used to decide which year is "last year"
     */
    PlannerInputNormalizer(DataBackendService dataBackend, CropRepository crops, Clock clock) {
        this.dataBackend = dataBackend;
        this.crops = crops;
        this.clock = clock;
    }

    /**
     * Fetches soil, crop history and the environmental summary for a field's
     * outline and turns them into planner input.
     *
     * <p>The three lookups run one after another. A lookup that fails is
     * logged and treated as missing data.</p>
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

            return toField(request.fieldId(), request.currentCrop(), soil, history, summary);
        } finally {
            log.trace("Exiting normalize()");
        }
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
     * @return planner input and the assumptions made to build it
     */
    public NormalizedField toField(
            String fieldId,
            String currentCrop,
            FieldSoilResponse soil,
            CropHistoryResponse history,
            FieldSummaryResponse summary) {
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

            String rating = summary == null || summary.terrain() == null ? null : summary.terrain().erosionRating();
            Double erosionRisk = rating == null ? null : EROSION_RISK_BY_RATING.get(rating.toLowerCase(Locale.ROOT));
            if (erosionRisk == null) {
                erosionRisk = DEFAULT_EROSION_RISK;
                assumptions.add("No terrain rating was available for this field, so a moderate erosion risk was assumed.");
            }

            List<String> cropHistory = history(currentCrop, history, assumptions);

            Field field = new Field(fieldId, lat, lon, area, ph, texture, organicMatter, erosionRisk, cropHistory);
            log.debug("Normalized field with {} history years and {} assumptions", cropHistory.size(), assumptions.size());
            return new NormalizedField(field, List.copyOf(assumptions));
        } finally {
            log.trace("Exiting toField()");
        }
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
     * Runs one data backend lookup, treating a failure as missing data.
     *
     * @param what   what is being fetched, for the log
     * @param lookup the lookup to run
     * @param <T>    the response type
     * @return the response, or {@code null} when the data backend failed
     */
    private <T> T fetch(String what, Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (DataBackendException e) {
            log.warn("Planning without {}: the data backend answered HTTP {}", what, e.getStatusCode());
            return null;
        }
    }
}
