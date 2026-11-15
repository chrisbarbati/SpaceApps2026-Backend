package org.greenalienhouseparty.market;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.exception.ExternalDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the Statistics Canada Web Data Service (WDS), an open REST
 * API that needs no key.
 *
 * <p>Only one method is used: the latest N periods of a series, identified by
 * a table (product ID) and a coordinate, which is the member ID chosen in each
 * of the table's dimensions joined by dots and padded to ten positions.</p>
 */
@Component
public class StatCanClient {

    private static final Logger log = LoggerFactory.getLogger(StatCanClient.class);

    private static final ParameterizedTypeReference<List<SeriesResult>> RESULTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    /**
     * Creates the client.
     *
     * @param baseUrl WDS REST base URL (see {@code statcan.wds.url})
     */
    public StatCanClient(@Value("${statcan.wds.url:https://www150.statcan.gc.ca/t1/wds/rest}") String baseUrl) {
        log.trace("Entering StatCanClient constructor");
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        // The service answers "deflate" with a raw stream that standard decoders reject, so ask for no compression.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
                .build();
        log.debug("Initialized Statistics Canada client");
        log.trace("Exiting StatCanClient constructor");
    }

    /**
     * Fetches the latest periods of several series in one request.
     *
     * <p>A series Statistics Canada does not have is returned with status
     * {@code FAILED} and no data points; it does not fail the others.</p>
     *
     * @param series  the series wanted
     * @param latestN number of most recent periods per series
     * @return one result per requested series, in the service's order
     * @throws ExternalDataException when the service cannot be reached or answers with an error
     */
    public List<SeriesResult> latestPeriods(List<SeriesKey> series, int latestN) {
        log.trace("Entering latestPeriods()");
        log.debug("Calling Statistics Canada WDS for {} series", series.size());
        try {
            List<Map<String, Object>> body = series.stream()
                    .map(key -> Map.<String, Object>of(
                            "productId", key.productId(), "coordinate", key.coordinate(), "latestN", latestN))
                    .toList();
            List<SeriesResult> results = restClient.post()
                    .uri("/getDataFromCubePidCoordAndLatestNPeriods")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RESULTS);
            if (results == null) {
                throw new ExternalDataException(HttpStatus.BAD_GATEWAY.value(), "Statistics Canada returned no data");
            }
            return results;
        } catch (RestClientException e) {
            log.error("Statistics Canada request failed: {}: {}", e.getClass().getSimpleName(), e.getMostSpecificCause().getMessage());
            throw new ExternalDataException(HttpStatus.BAD_GATEWAY.value(), "Statistics Canada is unavailable");
        } finally {
            log.trace("Exiting latestPeriods()");
        }
    }

    /**
     * Identifies one series.
     *
     * @param productId  table number without dashes, for example {@code 32100359}
     * @param coordinate member IDs per dimension, dot-separated and padded to ten positions
     */
    public record SeriesKey(int productId, String coordinate) {
    }

    /**
     * One series as returned by the service.
     *
     * @param status {@code SUCCESS} or {@code FAILED}
     * @param object the series, present for both statuses
     */
    public record SeriesResult(String status, Series object) {
    }

    /**
     * A series and its data points.
     *
     * @param productId       table number
     * @param coordinate      the series coordinate
     * @param vectorDataPoint data points, oldest first; empty when the series does not exist
     */
    public record Series(Integer productId, String coordinate, List<DataPoint> vectorDataPoint) {
    }

    /**
     * One period's value.
     *
     * @param refPer reference period start, {@code YYYY-MM-DD}
     * @param value  the value, or {@code null} when suppressed or not yet published
     */
    public record DataPoint(String refPer, Double value) {
    }
}
