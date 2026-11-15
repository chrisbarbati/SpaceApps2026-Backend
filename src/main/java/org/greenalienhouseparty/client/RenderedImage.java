package org.greenalienhouseparty.client;

import org.springframework.http.MediaType;

/**
 * An image fetched from the imagery provider, ready to return to the browser.
 *
 * @param bytes       the encoded image
 * @param contentType the image media type reported by the provider, e.g. {@code image/png}
 */
public record RenderedImage(byte[] bytes, MediaType contentType) {
}
