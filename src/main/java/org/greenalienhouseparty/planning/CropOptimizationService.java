package org.greenalienhouseparty.planning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.greenalienhouseparty.dto.CropCatalogResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.Crop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates crop catalog access and Java-side crop-rotation planning.
 */
@Service
public class CropOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(CropOptimizationService.class);

    private static final String EXAMPLE_DATA_NOTE = "Crop figures are illustrative examples, not sourced regional "
            + "data. Use them to explore how crops compare, and confirm real values with local experts.";

    private static final String SOURCED_DATA_NOTE = "Crop figures are typical values; confirm them with local experts.";

    private final CropRepository crops;

    private final CropRotationPlanner planner;

    /**
     * Creates the crop optimization service.
     *
     * @param crops   catalog of supported crops and their attributes
     * @param planner implementation that creates the future crop plan
     */
    public CropOptimizationService(CropRepository crops, CropRotationPlanner planner) {
        log.trace("Entering CropOptimizationService constructor");
        this.crops = crops;
        this.planner = planner;
        log.debug("Initialized crop optimization service");
        log.trace("Exiting CropOptimizationService constructor");
    }

    /**
     * Returns the supported crops with the attributes the planner uses, for
     * current-crop and growable-crop controls and for display.
     *
     * @return catalog response containing every supported crop
     */
    public CropCatalogResponse catalog() {
        log.trace("Entering catalog()");
        log.debug("Loading supported crop choices");
        try {
            boolean example = crops.exampleData();
            return new CropCatalogResponse(crops.findAll(), example, example ? EXAMPLE_DATA_NOTE : SOURCED_DATA_NOTE);
        } finally {
            log.trace("Exiting catalog()");
        }
    }

    /**
     * Validates all submitted field contexts and creates a plan for each field
     * with the active planner.
     *
     * <p>Crops may be submitted by id or display name; planners always receive
     * display names.</p>
     *
     * @param request batch of selected fields and farmer crop choices
     * @return five-year plans, one for each submitted field
     * @throws CropOptimizationValidationException when request data is incomplete
     *         or contains an unsupported crop
     */
    public CropOptimizationResponse optimize(CropOptimizationRequest request) {
        log.trace("Entering optimize()");
        log.debug("Validating crop-planning request");
        try {
            validate(request);
            List<CropOptimizationResponse.FieldPlan> plans = request.fields().stream()
                    .map(this::withCropNames)
                    .map(planner::plan)
                    .toList();
            return new CropOptimizationResponse(
                    plans,
                    planner.id(),
                    planner.temporary(),
                    planner.disclaimer());
        } finally {
            log.trace("Exiting optimize()");
        }
    }

    /**
     * Checks required field context and crop names before invoking the planner.
     *
     * @param request crop optimization request to validate
     * @throws CropOptimizationValidationException when any field is invalid
     */
    private void validate(CropOptimizationRequest request) {
        log.trace("Entering validate()");
        try {
            if (request == null || request.fields() == null || request.fields().isEmpty()) {
                throw new CropOptimizationValidationException("At least one field is required");
            }
            var fieldIds = new HashSet<String>();
            for (int index = 0; index < request.fields().size(); index++) {
                CropOptimizationRequest.FieldRequest field = request.fields().get(index);
                String prefix = "fields[" + index + "]";
                if (field == null) {
                    throw new CropOptimizationValidationException(prefix + " is required");
                }
                if (field.fieldId() == null || field.fieldId().isBlank()) {
                    throw new CropOptimizationValidationException(prefix + ".fieldId is required");
                }
                if (!fieldIds.add(field.fieldId())) {
                    throw new CropOptimizationValidationException(prefix + ".fieldId must be unique");
                }
                if (field.geometry() == null
                        || field.geometry().isNull()
                        || !field.geometry().isObject()
                        || !field.geometry().hasNonNull("type")
                        || !List.of("Polygon", "MultiPolygon").contains(field.geometry().get("type").asString())
                        || !field.geometry().hasNonNull("coordinates")) {
                    throw new CropOptimizationValidationException(
                            prefix + ".geometry must be a GeoJSON Polygon or MultiPolygon");
                }
                if (crops.find(field.currentCrop()).isEmpty()) {
                    throw new CropOptimizationValidationException(prefix + ".currentCrop is not supported");
                }
                if (field.growableCrops() == null || field.growableCrops().isEmpty()) {
                    throw new CropOptimizationValidationException(prefix + ".growableCrops must not be empty");
                }
                var distinct = new HashSet<String>();
                for (String crop : field.growableCrops()) {
                    String cropId = crops.find(crop).map(Crop::id).orElseThrow(() ->
                            new CropOptimizationValidationException(prefix + ".growableCrops contains an unsupported crop"));
                    if (!distinct.add(cropId)) {
                        throw new CropOptimizationValidationException(prefix + ".growableCrops must not contain duplicates");
                    }
                }
            }
        } finally {
            log.trace("Exiting validate()");
        }
    }

    /**
     * Rewrites a validated field's crops as catalog display names, so a
     * request may use ids or names and planners see one form.
     *
     * @param field validated field input
     * @return the same field with crops given as display names
     */
    private CropOptimizationRequest.FieldRequest withCropNames(CropOptimizationRequest.FieldRequest field) {
        List<String> growable = new ArrayList<>(field.growableCrops().size());
        for (String crop : field.growableCrops()) {
            growable.add(crops.find(crop).orElseThrow().name());
        }
        return new CropOptimizationRequest.FieldRequest(
                field.fieldId(),
                field.geometry(),
                crops.find(field.currentCrop()).orElseThrow().name(),
                growable,
                field.readyDate());
    }
}
