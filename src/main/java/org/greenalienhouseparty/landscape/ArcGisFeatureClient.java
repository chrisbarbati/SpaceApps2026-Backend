package org.greenalienhouseparty.landscape;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.greenalienhouseparty.exception.ExternalDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * HTTP client for the open ArcGIS feature services published by Agriculture
 * and Agri-Food Canada, which need no key.
 *
 * <p>Only one request is used: a feature query for the polygon that contains
 * a point, returning that polygon's attributes.</p>
 */
@Component
public class ArcGisFeatureClient {

    private static final Logger log = LoggerFactory.getLogger(ArcGisFeatureClient.class);

    private final RestClient restClient;

    /**
     * Creates the client.
     *
     * @param baseUrl root of the feature services (see {@code aafc.arcgis.url})
     */
    public ArcGisFeatureClient(
            @Value("${aafc.arcgis.url:https://services.arcgis.com/lGOekm0RsNxYnT3j/arcgis/rest/services}")
            String baseUrl) {
        log.trace("Entering ArcGisFeatureClient constructor");
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .build();
        log.debug("Initialized AAFC ArcGIS feature client");
        log.trace("Exiting ArcGisFeatureClient constructor");
    }

    /**
     * Reads the attributes of the polygon that contains a point in a
     * service's first layer.
     *
     * @param service feature service name, for example {@code cli_agr_cap_250k}
     * @param lat     latitude in degrees
     * @param lon     longitude in degrees
     * @return the polygon's attributes, or empty when the layer has nothing at the point
     * @throws ExternalDataException when the service cannot be reached or answers with an error
     */
    public Optional<Map<String, JsonNode>> attributesAt(String service, double lat, double lon) {
        log.trace("Entering attributesAt()");
        log.debug("Querying AAFC feature service {}", service);
        try {
            String point = String.format(Locale.ROOT, "%.6f,%.6f", lon, lat);
            QueryResult result = restClient.get()
                    .uri(builder -> builder.path("/{service}/FeatureServer/0/query")
                            .queryParam("geometry", point)
                            .queryParam("geometryType", "esriGeometryPoint")
                            .queryParam("inSR", 4326)
                            .queryParam("spatialRel", "esriSpatialRelIntersects")
                            .queryParam("outFields", "*")
                            .queryParam("returnGeometry", false)
                            .queryParam("f", "json")
                            .build(service))
                    .retrieve()
                    .body(QueryResult.class);
            // ArcGIS reports failures in a 200 response with an "error" member.
            if (result == null || result.error() != null) {
                log.error("AAFC feature service {} answered with an error", service);
                throw new ExternalDataException(HttpStatus.BAD_GATEWAY.value(),
                        "Agriculture and Agri-Food Canada's soil service is unavailable");
            }
            if (result.features() == null || result.features().isEmpty()
                    || result.features().getFirst().attributes() == null) {
                return Optional.empty();
            }
            return Optional.of(result.features().getFirst().attributes());
        } catch (RestClientException e) {
            log.error("AAFC feature service request failed: {}: {}", e.getClass().getSimpleName(),
                    e.getMostSpecificCause().getMessage());
            throw new ExternalDataException(HttpStatus.BAD_GATEWAY.value(),
                    "Agriculture and Agri-Food Canada's soil service is unavailable");
        } finally {
            log.trace("Exiting attributesAt()");
        }
    }

    /**
     * The service's answer to a query.
     *
     * @param features matching features; empty when nothing contains the point
     * @param error    present when the service rejected the query
     */
    record QueryResult(List<Feature> features, JsonNode error) {
    }

    /**
     * One matching feature.
     *
     * @param attributes the feature's attributes by field name
     */
    record Feature(Map<String, JsonNode> attributes) {
    }
}
