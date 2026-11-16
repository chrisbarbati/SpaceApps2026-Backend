package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Weights;
import org.greenalienhouseparty.service.FieldDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the planners' field input for a stored field from its identifier alone.
 *
 * <p>{@link PlannerInputNormalizer#normalize} works from a drawn outline. This service instead reads the
 * stored field's soil, crop history, climate summary, crop calendar, regional soil ratings and seasonal
 * outlook through {@link FieldDetailsService} (all fetched at once, with failures reported per section) and
 * hands them to {@link PlannerInputNormalizer#toField}, so the result is the same input the optimizer would
 * use, with the same assumptions recorded for anything missing.</p>
 *
 * <p>It also offers a {@link DynamicCropRotationPlanner} and {@link CropSuitabilityAssessor} over the full
 * crop catalog, so callers outside the planner strategy, such as the chat assistant's tools, can score crops
 * and rotations consistently whichever planner serves {@code POST /crop-optimization}.</p>
 */
@Service
public class FieldInputService {

    private static final Logger log = LoggerFactory.getLogger(FieldInputService.class);

    private final FieldDetailsService details;

    private final PlannerInputNormalizer normalizer;

    private final CropRepository crops;

    private final Clock clock;

    private final DynamicCropRotationPlanner planner;

    private final CropSuitabilityAssessor suitability = new CropSuitabilityAssessor();

    /** The optimizer's default priorities: soil health first, reliability second, income not counted. */
    public static final Weights DEFAULT_WEIGHTS = DynamicCropRotationPlannerAdapter.DEFAULT_WEIGHTS;

    /**
     * Creates the service using the system clock.
     *
     * @param details    gathers everything known about a stored field
     * @param normalizer turns data backend responses into planner input
     * @param crops      crop catalog
     */
    @Autowired
    public FieldInputService(FieldDetailsService details, PlannerInputNormalizer normalizer, CropRepository crops) {
        this(details, normalizer, crops, Clock.systemDefaultZone());
    }

    /**
     * Creates the service with an explicit clock for repeatable testing.
     *
     * @param details    gathers everything known about a stored field
     * @param normalizer turns data backend responses into planner input
     * @param crops      crop catalog
     * @param clock      calendar source used to decide the first plan year
     */
    FieldInputService(FieldDetailsService details, PlannerInputNormalizer normalizer, CropRepository crops, Clock clock) {
        this.details = details;
        this.normalizer = normalizer;
        this.crops = crops;
        this.clock = clock;
        this.planner = new DynamicCropRotationPlanner(crops.findAll(), PlanYears.PLANNING_HORIZON_YEARS);
    }

    /**
     * A stored field's planner input together with the data it was built from.
     *
     * @param field   planner input and the assumptions made for missing data
     * @param details everything known about the field, as returned by {@code GET /fields/{field_id}}
     */
    public record FieldInput(NormalizedField field, FieldDetailsResponse details) {
    }

    /**
     * Loads a stored field and builds planner input for it.
     *
     * @param fieldId         stored field identifier
     * @param currentCrop     id or display name of the crop growing this year, or {@code null} when not known
     * @param growableCropIds crops the farmer can grow, used to keep notes relevant; {@code null} or empty for all
     * @return the planner input and the field details it came from
     * @throws org.greenalienhouseparty.client.DataBackendException when the field does not exist
     */
    public FieldInput load(String fieldId, String currentCrop, Collection<String> growableCropIds) {
        log.trace("Entering load()");
        try {
            FieldDetailsResponse fieldDetails = details.details(fieldId, null);
            Set<String> growable = resolveCropIds(growableCropIds);
            NormalizedField field = normalizer.toField(
                    fieldId,
                    currentCrop,
                    fieldDetails.soil(),
                    fieldDetails.cropHistory(),
                    fieldDetails.climate(),
                    fieldDetails.cropCalendar(),
                    fieldDetails.soilLandscape(),
                    fieldDetails.seasonalOutlook(),
                    firstPlanYear(),
                    growable.isEmpty() ? null : growable);
            return new FieldInput(field, fieldDetails);
        } finally {
            log.trace("Exiting load()");
        }
    }

    /**
     * Resolves crop ids or display names to catalog ids, dropping unknown entries.
     *
     * @param cropIdsOrNames crop ids or names; may be {@code null}
     * @return known catalog ids in the given order
     */
    public Set<String> resolveCropIds(Collection<String> cropIdsOrNames) {
        Set<String> ids = new LinkedHashSet<>();
        if (cropIdsOrNames != null) {
            for (String crop : cropIdsOrNames) {
                crops.find(crop).map(Crop::id).ifPresent(ids::add);
            }
        }
        return ids;
    }

    /**
     * Returns the first calendar year a plan built today would cover.
     *
     * @return next calendar year
     */
    public int firstPlanYear() {
        return PlanYears.firstPlanYear(clock, null);
    }

    /**
     * Returns the number of years every plan covers.
     *
     * @return the planning horizon in years
     */
    public int planningYears() {
        return PlanYears.PLANNING_HORIZON_YEARS;
    }

    /**
     * Returns every crop in the catalog.
     *
     * @return the crop catalog in display order
     */
    public List<Crop> crops() {
        return crops.findAll();
    }

    /**
     * Returns an optimizer over the whole crop catalog, independent of the configured planner strategy.
     *
     * @return the soil-health and reliability optimizer
     */
    public DynamicCropRotationPlanner planner() {
        return planner;
    }

    /**
     * Returns the climate and soil suitability check the optimizer uses.
     *
     * @return the suitability assessor
     */
    public CropSuitabilityAssessor suitability() {
        return suitability;
    }
}
