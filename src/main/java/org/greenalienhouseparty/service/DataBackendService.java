package org.greenalienhouseparty.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldCoverageResponse;
import org.greenalienhouseparty.dto.FieldCropHistoryBatchRequest;
import org.greenalienhouseparty.dto.FieldCropHistoryBatchResponse;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.dto.HealthResponse;
import org.greenalienhouseparty.dto.ReadinessResponse;
import org.greenalienhouseparty.dto.SoilMoistureResponse;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Service facade over the Python data backend.
 *
 * <p>This layer currently re-exposes the data backend endpoints one-to-one while
 * deserializing to typed models. It is the intended home for application-specific
 * business logic that should never live in the Python backend or the frontend.</p>
 */
@Service
public class DataBackendService {

    private static final Logger log = LoggerFactory.getLogger(DataBackendService.class);

    private final DataBackendClient client;

    /**
     * Creates the service.
     *
     * @param client the data backend client
     */
    public DataBackendService(DataBackendClient client) {
        log.trace("Entering DataBackendService constructor");
        this.client = client;
        log.debug("Initialized data backend service");
        log.trace("Exiting DataBackendService constructor");
    }

    /**
     * @return the data backend liveness response
     */
    public HealthResponse health() {
        log.trace("Entering health()");
        log.debug("Requesting data backend health status");
        try {
            return client.health();
        } finally {
            log.trace("Exiting health()");
        }
    }

    /**
     * Retrieves field-data readiness while preserving the upstream HTTP status.
     *
     * @return the upstream readiness status and typed response
     */
    public ResponseEntity<ReadinessResponse> readiness() {
        log.trace("Entering readiness()");
        log.debug("Requesting field-data readiness from the data backend");
        try {
            return client.readiness();
        } finally {
            log.trace("Exiting readiness()");
        }
    }

    /**
     * Retrieves the SMAP soil-moisture layer through the Python data backend.
     *
     * @return GeoJSON cells with typed SMAP metadata
     */
    public SoilMoistureResponse soilMoisture() {
        log.trace("Entering soilMoisture()");
        log.debug("Requesting the SMAP soil-moisture layer from the data backend");
        try {
            return client.soilMoisture();
        } finally {
            log.trace("Exiting soilMoisture()");
        }
    }

    /**
     * Retrieves NASA POWER daily point data.
     *
     * @param lat        the latitude to sample
     * @param lon        the longitude to sample
     * @param start      the inclusive start date
     * @param end        the inclusive end date
     * @param parameters the comma-separated POWER parameters
     * @return the daily point data
     */
    public DailyPointResponse dailyPoint(double lat, double lon, String start, String end, String parameters) {
        log.trace("Entering dailyPoint()");
        log.debug("Requesting daily point data from the data backend");
        try {
            return client.dailyPoint(lat, lon, start, end, parameters);
        } finally {
            log.trace("Exiting dailyPoint()");
        }
    }

    /**
     * Lists field outlines in a map viewport.
     *
     * @param bbox   the optional bounding box
     * @param limit  the page size
     * @param cursor the pagination cursor
     * @return a page of field outlines
     */
    public FieldsResponse listFields(String bbox, int limit, int cursor) {
        log.trace("Entering listFields()");
        log.debug("Requesting a field boundary page from the data backend");
        try {
            return client.listFields(bbox, limit, cursor);
        } finally {
            log.trace("Exiting listFields()");
        }
    }

    /**
     * Retrieves the estimated area of a selected field.
     *
     * @param fieldId the field identifier
     * @return the estimated hectares and acres
     */
    public FieldAreaResponse fieldArea(String fieldId) {
        log.trace("Entering fieldArea()");
        log.debug("Requesting selected field area from the data backend");
        try {
            return client.fieldArea(fieldId);
        } finally {
            log.trace("Exiting fieldArea()");
        }
    }

    /**
     * @return the loaded field coverage and build status
     */
    public FieldCoverageResponse fieldCoverage() {
        log.trace("Entering fieldCoverage()");
        log.debug("Requesting field coverage metadata from the data backend");
        try {
            return client.fieldCoverage();
        } finally {
            log.trace("Exiting fieldCoverage()");
        }
    }

    /**
     * Retrieves an environmental summary for a selected field.
     *
     * @param request the field summary request
     * @return the environmental summary
     */
    public FieldSummaryResponse fieldSummary(FieldSummaryRequest request) {
        log.trace("Entering fieldSummary()");
        log.debug("Requesting an environmental field summary from the data backend");
        try {
            return client.fieldSummary(request);
        } finally {
            log.trace("Exiting fieldSummary()");
        }
    }

    /**
     * Retrieves crop history for an indexed field.
     *
     * @param fieldId the field identifier
     * @return year-by-year crop classes and source metadata
     */
    public CropHistoryResponse fieldCropHistory(String fieldId) {
        log.trace("Entering fieldCropHistory()");
        log.debug("Requesting field crop history from the data backend");
        try {
            return client.fieldCropHistory(fieldId);
        } finally {
            log.trace("Exiting fieldCropHistory()");
        }
    }

    /**
     * Retrieves crop history for a user-provided outline.
     *
     * @param request the polygon request
     * @return year-by-year crop classes and source metadata
     */
    public CropHistoryResponse cropHistory(CropHistoryRequest request) {
        log.trace("Entering cropHistory()");
        log.debug("Requesting crop history for a provided outline from the data backend");
        try {
            return client.cropHistory(request);
        } finally {
            log.trace("Exiting cropHistory()");
        }
    }

    /** Field IDs the data backend accepts per request. */
    static final int DATA_BACKEND_BATCH_SIZE = 100;

    /**
     * Field IDs accepted per request to this application. Uncached fields take
     * about 0.3 seconds each, so 300 stays within the 100-second limit of the
     * proxy in front of this service even when nothing is cached.
     */
    static final int MAX_FIELDS_PER_REQUEST = 300;

    /**
     * Retrieves crop history for several stored fields.
     *
     * <p>IDs are trimmed and de-duplicated in first-seen order, then sent to the
     * data backend in groups of {@value #DATA_BACKEND_BATCH_SIZE}, one group at a
     * time so the crop map providers behind it are not flooded. The groups'
     * results are combined in request order. A field that fails is reported in
     * its own result; only a failure of the whole data backend call fails the
     * request.</p>
     *
     * @param request the field IDs
     * @return one result per unique requested field, in request order
     * @throws InvalidRequestException when no IDs, a blank ID, or more than
     *                                 {@value #MAX_FIELDS_PER_REQUEST} unique IDs are given
     */
    public FieldCropHistoryBatchResponse fieldCropHistories(FieldCropHistoryBatchRequest request) {
        log.trace("Entering fieldCropHistories()");
        try {
            List<String> ids = uniqueFieldIds(request);
            log.debug("Requesting crop history for {} fields in groups of {}", ids.size(), DATA_BACKEND_BATCH_SIZE);
            List<FieldCropHistoryBatchResponse.Result> results = new ArrayList<>(ids.size());
            for (int from = 0; from < ids.size(); from += DATA_BACKEND_BATCH_SIZE) {
                List<String> group = ids.subList(from, Math.min(from + DATA_BACKEND_BATCH_SIZE, ids.size()));
                results.addAll(client.fieldCropHistories(group).results());
            }
            int succeeded = (int) results.stream().filter(FieldCropHistoryBatchResponse.Result::isOk).count();
            return new FieldCropHistoryBatchResponse(results, results.size(), succeeded, results.size() - succeeded);
        } finally {
            log.trace("Exiting fieldCropHistories()");
        }
    }

    /**
     * Validates, trims and de-duplicates requested field IDs, keeping their order.
     *
     * @param request the field IDs
     * @return unique, trimmed field IDs
     * @throws InvalidRequestException when the IDs are missing, blank or too many
     */
    private List<String> uniqueFieldIds(FieldCropHistoryBatchRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new InvalidRequestException("ids must contain at least one field ID");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : request.ids()) {
            if (id == null || id.isBlank()) {
                throw new InvalidRequestException("ids must not contain blank field IDs");
            }
            unique.add(id.strip());
        }
        if (unique.size() > MAX_FIELDS_PER_REQUEST) {
            throw new InvalidRequestException("ids must contain at most " + MAX_FIELDS_PER_REQUEST + " field IDs");
        }
        return List.copyOf(unique);
    }
}
