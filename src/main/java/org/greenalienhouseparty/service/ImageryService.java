package org.greenalienhouseparty.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.client.ImageryRenderClient;
import org.greenalienhouseparty.client.RenderedImage;
import org.greenalienhouseparty.dto.FieldImageryResponse;
import org.greenalienhouseparty.dto.ImageryRequest;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Satellite RGB imagery for fields and outlines.
 *
 * <p>Scene searches go to the Python data backend, which returns provider
 * rendering addresses. This service rewrites each address to this application's
 * image route ({@value #IMAGE_ROUTE}) so the frontend only ever talks to this
 * application, and serves that route by fetching the image from the provider.
 * Only addresses under {@code imagery.render.allowed-prefix} (default: Planetary
 * Computer's bounding-box renderer) ending in {@code .png} are rewritten or
 * fetched, so the image route cannot be used to reach other hosts.</p>
 */
@Service
public class ImageryService {

    private static final Logger log = LoggerFactory.getLogger(ImageryService.class);

    /** Route on this application that returns one image, given its provider address as {@code src}. */
    public static final String IMAGE_ROUTE = "/imagery/image";

    private final DataBackendClient client;

    private final ImageryRenderClient renderClient;

    private final String allowedPrefix;

    /**
     * Creates the service.
     *
     * @param client        the data backend client
     * @param renderClient  the client that downloads rendered images
     * @param allowedPrefix the only provider address prefix that may be served
     */
    public ImageryService(
            DataBackendClient client,
            ImageryRenderClient renderClient,
            @Value("${imagery.render.allowed-prefix:https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/}")
            String allowedPrefix) {
        log.trace("Entering ImageryService constructor");
        this.client = client;
        this.renderClient = renderClient;
        this.allowedPrefix = allowedPrefix;
        log.debug("Initialized imagery service");
        log.trace("Exiting ImageryService constructor");
    }

    /**
     * Finds satellite images of a stored field.
     *
     * @param fieldId  the field identifier
     * @param dataset  {@code hls} or {@code sentinel-2}, or {@code null} for HLS
     * @param start    first acquisition date, or {@code null}
     * @param end      last acquisition date, or {@code null}
     * @param maxCloud maximum whole-scene cloud percentage, or {@code null}
     * @param limit    maximum number of scenes, or {@code null}
     * @return matching scenes whose images are served by this application
     */
    public FieldImageryResponse fieldImagery(String fieldId, String dataset, LocalDate start, LocalDate end,
                                             Double maxCloud, Integer limit) {
        log.trace("Entering fieldImagery()");
        log.debug("Requesting field imagery from the data backend");
        try {
            return servedLocally(client.fieldImagery(fieldId, dataset, start, end, maxCloud, limit));
        } finally {
            log.trace("Exiting fieldImagery()");
        }
    }

    /**
     * Finds satellite images of a user-provided outline.
     *
     * @param request the polygon and optional search options
     * @return matching scenes whose images are served by this application
     */
    public FieldImageryResponse imagery(ImageryRequest request) {
        log.trace("Entering imagery()");
        log.debug("Requesting imagery for a provided outline from the data backend");
        try {
            return servedLocally(client.imagery(request));
        } finally {
            log.trace("Exiting imagery()");
        }
    }

    /**
     * Fetches one image for the image route.
     *
     * @param src the provider rendering address from {@code sourceImageUrl}
     * @return the image bytes and media type
     * @throws InvalidRequestException when {@code src} is not an allowed provider address
     */
    public RenderedImage render(String src) {
        log.trace("Entering render()");
        try {
            URI uri = allowedUri(src);
            if (uri == null) {
                throw new InvalidRequestException("src must be an image address returned by the imagery routes");
            }
            return renderClient.fetch(uri);
        } finally {
            log.trace("Exiting render()");
        }
    }

    /**
     * Points every image in a response at this application's image route. An
     * image whose provider address is not allowed gets a {@code null} image URL
     * rather than a link that would fail.
     *
     * @param response the data backend response
     * @return the response with rewritten image addresses
     */
    private FieldImageryResponse servedLocally(FieldImageryResponse response) {
        if (response == null || response.images() == null) {
            return response;
        }
        List<FieldImageryResponse.Image> images = response.images().stream().map(image -> {
            String source = image.imageUrl();
            if (allowedUri(source) == null) {
                log.warn("Imagery provider address for scene {} is not allowed; omitting its image URL", image.sceneId());
                return image.withUrls(null, source);
            }
            return image.withUrls(IMAGE_ROUTE + "?src=" + URLEncoder.encode(source, StandardCharsets.UTF_8), source);
        }).toList();
        return response.withImages(images);
    }

    /**
     * Checks that an address is an absolute PNG address under the allowed prefix,
     * without user info, fragments or path traversal.
     *
     * @param src the address to check
     * @return the parsed address, or {@code null} when it is not allowed
     */
    URI allowedUri(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(src);
            String path = uri.getRawPath();
            if (!uri.isAbsolute() || uri.getRawUserInfo() != null || uri.getRawFragment() != null || path == null
                    || !path.endsWith(".png") || path.contains("..") || uri.getPath().contains("..")) {
                return null;
            }
            String base = uri.getScheme() + "://" + uri.getRawAuthority() + path;
            return base.startsWith(allowedPrefix) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
