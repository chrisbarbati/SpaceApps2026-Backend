package org.greenalienhouseparty.service;

import java.util.HashSet;
import java.util.List;

import org.greenalienhouseparty.dto.CropCatalogResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates crop catalog access and Java-side crop-rotation planning.
 */
@Service
public class CropOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(CropOptimizationService.class);

    private static final String PLACEHOLDER_NOTE = "Temporary random plan only; it is not optimized for soil health, "
            + "weather, yield, income, or crop reliability, and may repeat crops. "
            + "Review any planting decision with local experts.";

    private final CropCatalog catalog;

    private final CropRotationPlanner planner;

    /**
     * Creates the crop optimization service.
     *
     * @param catalog canonical set of currently supported crops
     * @param planner implementation that creates the future crop plan
     */
    public CropOptimizationService(CropCatalog catalog, CropRotationPlanner planner) {
        log.trace("Entering CropOptimizationService constructor");
        this.catalog = catalog;
        this.planner = planner;
        log.debug("Initialized crop optimization service");
        log.trace("Exiting CropOptimizationService constructor");
    }

    /**
     * Returns the fixed crop list for current-crop and growable-crop controls.
     *
     * @return catalog response containing supported display names
     */
    public CropCatalogResponse catalog() {
        log.trace("Entering catalog()");
        log.debug("Loading supported crop choices");
        try {
            return new CropCatalogResponse(catalog.crops());
        } finally {
            log.trace("Exiting catalog()");
        }
    }

    /**
     * Validates all submitted field contexts and creates a temporary plan for
     * each field.
     *
     * @param request batch of selected fields and farmer crop choices
     * @return five-year placeholder plans, one for each submitted field
     * @throws CropOptimizationValidationException when request data is incomplete
     *         or contains an unsupported crop
     */
    public CropOptimizationResponse optimize(CropOptimizationRequest request) {
        log.trace("Entering optimize()");
        log.debug("Validating crop-planning request");
        try {
            validate(request);
            List<CropOptimizationResponse.FieldPlan> plans = request.fields().stream()
                    .map(planner::plan)
                    .toList();
            return new CropOptimizationResponse(
                    plans,
                    "random-placeholder",
                    true,
                    PLACEHOLDER_NOTE);
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
                if (!catalog.supports(field.currentCrop())) {
                    throw new CropOptimizationValidationException(prefix + ".currentCrop is not supported");
                }
                if (field.growableCrops() == null || field.growableCrops().isEmpty()) {
                    throw new CropOptimizationValidationException(prefix + ".growableCrops must not be empty");
                }
                var distinct = new HashSet<String>();
                for (String crop : field.growableCrops()) {
                    if (!catalog.supports(crop)) {
                        throw new CropOptimizationValidationException(prefix + ".growableCrops contains an unsupported crop");
                    }
                    if (!distinct.add(crop)) {
                        throw new CropOptimizationValidationException(prefix + ".growableCrops must not contain duplicates");
                    }
                }
            }
        } finally {
            log.trace("Exiting validate()");
        }
    }
}
