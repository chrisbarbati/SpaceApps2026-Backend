package org.greenalienhouseparty.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.greenalienhouseparty.exception.ImageryProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * HTTP client that downloads rendered satellite images from the imagery provider
 * (Microsoft Planetary Computer) so this application can serve them to the browser.
 *
 * <p>The Python data backend finds scenes and builds the provider rendering
 * addresses; this client only fetches addresses the service layer has already
 * checked. Redirects are never followed, only PNG, JPEG and WebP responses are
 * accepted, and responses larger than {@code imagery.render.max-bytes} are
 * rejected, so the route cannot be used to fetch arbitrary content.</p>
 */
@Component
public class ImageryRenderClient {

    private static final Logger log = LoggerFactory.getLogger(ImageryRenderClient.class);

    /** Image types served from this application's origin; SVG is excluded because it can carry scripts. */
    private static final List<MediaType> RASTER_TYPES = List.of(
            MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.parseMediaType("image/webp"));

    private final HttpClient httpClient;

    private final Duration readTimeout;

    private final int maxBytes;

    /**
     * Creates the client.
     *
     * @param readTimeout maximum wait for the provider to render an image
     *                    ({@code imagery.render.read-timeout}, default 60 seconds)
     * @param maxBytes    largest image accepted ({@code imagery.render.max-bytes}, default 10 MiB)
     */
    public ImageryRenderClient(
            @Value("${imagery.render.read-timeout:60s}") Duration readTimeout,
            @Value("${imagery.render.max-bytes:10485760}") int maxBytes) {
        log.trace("Entering ImageryRenderClient constructor");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.readTimeout = readTimeout;
        this.maxBytes = maxBytes;
        log.debug("Initialized imagery render HTTP client");
        log.trace("Exiting ImageryRenderClient constructor");
    }

    /**
     * Downloads one rendered image.
     *
     * @param uri a provider rendering address already checked by the caller
     * @return the image bytes and media type
     * @throws ImageryProviderException when the provider cannot be reached, answers
     *                                  with an error, returns something other than
     *                                  an image, or returns too large an image
     */
    public RenderedImage fetch(URI uri) {
        log.trace("Entering fetch()");
        log.debug("Fetching a rendered image from {}", uri.getHost());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "image/png,image/*;q=0.8")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    log.error("Imagery provider returned HTTP {}", response.statusCode());
                    throw new ImageryProviderException(
                            "Imagery provider returned HTTP " + response.statusCode() + "; the scene could not be rendered");
                }
                MediaType contentType = imageType(response.headers().firstValue("Content-Type").orElse(null));
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    log.error("Imagery provider response exceeded {} bytes", maxBytes);
                    throw new ImageryProviderException("Imagery provider returned an image larger than " + maxBytes + " bytes");
                }
                return new RenderedImage(bytes, contentType);
            }
        } catch (IOException e) {
            log.error("Imagery provider request failed: {}", e.getClass().getSimpleName());
            throw new ImageryProviderException("Imagery provider is unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageryProviderException("Imagery request was interrupted", e);
        } finally {
            log.trace("Exiting fetch()");
        }
    }

    /**
     * Parses the provider's content type and requires an image.
     *
     * @param header the {@code Content-Type} header value, or {@code null}
     * @return the image media type
     * @throws ImageryProviderException when the response is not an image
     */
    private static MediaType imageType(String header) {
        try {
            if (header != null) {
                MediaType type = MediaType.parseMediaType(header);
                for (MediaType allowed : RASTER_TYPES) {
                    if (allowed.equalsTypeAndSubtype(type)) {
                        return allowed;
                    }
                }
            }
        } catch (InvalidMediaTypeException e) {
            log.error("Imagery provider sent an invalid content type");
        }
        throw new ImageryProviderException("Imagery provider did not return an image");
    }
}
