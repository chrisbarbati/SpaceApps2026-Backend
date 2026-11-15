package org.greenalienhouseparty.outlook;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

/**
 * HTTP client for MSC GeoMet, Environment and Climate Change Canada's open
 * web map service, which needs no key.
 *
 * <p>Only one request is used: WMS {@code GetFeatureInfo}, which returns a
 * layer's value at a point as JSON. Layers that vary in time take a
 * {@code time} (what the value is for) and a {@code reference_time} (the
 * model run it comes from); leaving them out selects the newest.</p>
 */
@Component
public class GeoMetClient {

    private static final Logger log = LoggerFactory.getLogger(GeoMetClient.class);

    /** Half-width in degrees of the small box drawn around the point being asked about. */
    private static final double BOX_DEG = 0.05;

    private final RestClient restClient;

    /**
     * Creates the client.
     *
     * @param baseUrl GeoMet WMS endpoint (see {@code geomet.wms.url})
     */
    public GeoMetClient(@Value("${geomet.wms.url:https://geo.weather.gc.ca/geomet}") String baseUrl) {
        log.trace("Entering GeoMetClient constructor");
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
        log.debug("Initialized MSC GeoMet client");
        log.trace("Exiting GeoMetClient constructor");
    }

    /**
     * Reads a layer's value at a point.
     *
     * @param layer         layer name
     * @param lat           latitude in degrees
     * @param lon           longitude in degrees
     * @param time          the time the value is for, ISO 8601, or {@code null} for the layer's default
     * @param referenceTime the model run, ISO 8601, or {@code null} for the newest
     * @return the value with the time and run it belongs to, or empty when the layer has nothing at the point
     * @throws ExternalDataException when the service cannot be reached or answers with an error
     */
    public Optional<PointValue> valueAt(String layer, double lat, double lon, String time, String referenceTime) {
        log.trace("Entering valueAt()");
        log.debug("Calling MSC GeoMet GetFeatureInfo for layer {}", layer);
        try {
            // WMS 1.3.0 with EPSG:4326 orders the box as south, west, north, east.
            String bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                    lat - BOX_DEG, lon - BOX_DEG, lat + BOX_DEG, lon + BOX_DEG);
            FeatureInfo info = restClient.get()
                    .uri(builder -> {
                        builder.queryParam("service", "WMS")
                                .queryParam("version", "1.3.0")
                                .queryParam("request", "GetFeatureInfo")
                                .queryParam("layers", layer)
                                .queryParam("query_layers", layer)
                                .queryParam("crs", "EPSG:4326")
                                .queryParam("bbox", bbox)
                                .queryParam("width", 3)
                                .queryParam("height", 3)
                                .queryParam("i", 1)
                                .queryParam("j", 1)
                                .queryParam("info_format", "application/json");
                        if (time != null) {
                            builder.queryParam("time", time);
                        }
                        if (referenceTime != null) {
                            builder.queryParam("dim_reference_time", referenceTime);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(FeatureInfo.class);
            if (info == null || info.features() == null || info.features().isEmpty()
                    || info.features().getFirst().properties() == null) {
                return Optional.empty();
            }
            Properties properties = info.features().getFirst().properties();
            return properties.value() == null
                    ? Optional.empty()
                    : Optional.of(new PointValue(properties.value(), properties.time(), properties.referenceTime()));
        } catch (RestClientException e) {
            log.error("MSC GeoMet request failed: {}: {}", e.getClass().getSimpleName(),
                    e.getMostSpecificCause().getMessage());
            throw new ExternalDataException(HttpStatus.BAD_GATEWAY.value(),
                    "Environment Canada's forecast service is unavailable");
        } finally {
            log.trace("Exiting valueAt()");
        }
    }

    /**
     * A layer's value at a point.
     *
     * @param value         the value
     * @param time          the time the value is for, ISO 8601
     * @param referenceTime the model run the value comes from, ISO 8601
     */
    public record PointValue(double value, String time, String referenceTime) {
    }

    /**
     * The service's GeoJSON answer.
     *
     * @param features features at the point; empty when the layer has no value there
     */
    record FeatureInfo(List<Feature> features) {
    }

    /**
     * One feature of the answer.
     *
     * @param properties the feature's properties
     */
    record Feature(Properties properties) {
    }

    /**
     * Properties of a feature.
     *
     * @param value         the layer value
     * @param time          the time the value is for
     * @param referenceTime the model run
     */
    record Properties(
            Double value,
            String time,
            @com.fasterxml.jackson.annotation.JsonProperty("dim_reference_time") String referenceTime) {
    }
}
