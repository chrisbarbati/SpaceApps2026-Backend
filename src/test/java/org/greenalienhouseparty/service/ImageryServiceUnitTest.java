package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.client.ImageryRenderClient;
import org.greenalienhouseparty.client.RenderedImage;
import org.greenalienhouseparty.dto.FieldImageryResponse;
import org.greenalienhouseparty.dto.ImageryRequest;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

/**
 * Unit tests for the imagery service: image addresses are rewritten to this
 * application's image route, and only allowed provider addresses are fetched.
 */
@ExtendWith(MockitoExtension.class)
class ImageryServiceUnitTest {

    private static final String PREFIX = "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/";

    private static final String IMAGE = PREFIX + "-88.95,40.45,-88.947,40.453.png?collection=hls2-l30&item=A&assets=B04";

    @Mock
    private DataBackendClient client;

    @Mock
    private ImageryRenderClient renderClient;

    private ImageryService service;

    /**
     * Creates the service with mocked clients and the default provider prefix.
     */
    @BeforeEach
    void setUp() {
        service = new ImageryService(client, renderClient, PREFIX);
    }

    /**
     * Verifies field imagery passes its options through, routes allowed images
     * through this application and keeps the provider address.
     */
    @Test
    void rewritesFieldImageUrlsToTheImageRoute() {
        LocalDate start = LocalDate.of(2025, 6, 1);
        LocalDate end = LocalDate.of(2025, 9, 1);
        when(client.fieldImagery("field-1", "hls", start, end, 20.0, 2))
                .thenReturn(response("field-1", IMAGE, "https://evil.example/x.png"));

        FieldImageryResponse result = service.fieldImagery("field-1", "hls", start, end, 20.0, 2);

        assertEquals("field-1", result.id());
        FieldImageryResponse.Image served = result.images().get(0);
        assertEquals(IMAGE, served.sourceImageUrl());
        String route = ImageryService.IMAGE_ROUTE + "?src=";
        assertTrue(served.imageUrl().startsWith(route));
        assertEquals(IMAGE, URLDecoder.decode(served.imageUrl().substring(route.length()), StandardCharsets.UTF_8));
        assertEquals("A", served.sceneId());
        FieldImageryResponse.Image refused = result.images().get(1);
        assertNull(refused.imageUrl());
        assertEquals("https://evil.example/x.png", refused.sourceImageUrl());
        assertEquals(true, result.hasMore());
    }

    /**
     * Verifies outline imagery is requested with the caller's typed request and
     * rewritten the same way.
     */
    @Test
    void rewritesOutlineImageUrls() {
        ImageryRequest request = new ImageryRequest();
        when(client.imagery(request)).thenReturn(response(null, IMAGE));

        FieldImageryResponse result = service.imagery(request);

        assertNull(result.id());
        assertEquals(IMAGE, result.images().get(0).sourceImageUrl());
        verify(client).imagery(request);
    }

    /**
     * Verifies a response without images is returned unchanged.
     */
    @Test
    void leavesEmptyResultsUnchanged() {
        FieldImageryResponse empty = new FieldImageryResponse(null, null, null, null, false, "hls", null, null);
        when(client.imagery(any())).thenReturn(empty);

        assertSame(empty, service.imagery(new ImageryRequest()));
    }

    /**
     * Verifies an allowed address is fetched and the image returned unchanged.
     */
    @Test
    void rendersAllowedAddresses() {
        RenderedImage image = new RenderedImage(new byte[] {1, 2}, MediaType.IMAGE_PNG);
        when(renderClient.fetch(URI.create(IMAGE))).thenReturn(image);

        assertSame(image, service.render(IMAGE));
    }

    /**
     * Verifies addresses outside the provider prefix, or with tricks to escape it,
     * are rejected without any request being made.
     */
    @Test
    void rejectsAddressesOutsideTheProvider() {
        for (String src : Arrays.asList(
                null, "", "not a uri",
                "https://evil.example/api/data/v1/item/bbox/a.png",
                "http://planetarycomputer.microsoft.com/api/data/v1/item/bbox/a.png",
                "https://planetarycomputer.microsoft.com.evil.example/api/data/v1/item/bbox/a.png",
                "https://user@planetarycomputer.microsoft.com/api/data/v1/item/bbox/a.png",
                "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/a.tif",
                "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/../../stac/a.png",
                "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%2E%2E/a.png",
                "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/a.png#x",
                "/imagery/image?src=x.png")) {
            assertThrows(InvalidRequestException.class, () -> service.render(src), String.valueOf(src));
        }
        verify(renderClient, never()).fetch(any());
    }

    /**
     * Creates a data backend response with one image per address.
     *
     * @param id   the field ID, or {@code null} for an outline
     * @param urls provider image addresses
     * @return an imagery response
     */
    private static FieldImageryResponse response(String id, String... urls) {
        List<FieldImageryResponse.Image> images = Arrays.stream(urls)
                .map(url -> new FieldImageryResponse.Image(url.equals(IMAGE) ? "A" : "B", null, "hls2-l30",
                        "landsat-8", 4.0, url, null, List.of(-89.0, 40.0, -88.0, 41.0), "https://example.test/stac"))
                .toList();
        return new FieldImageryResponse(id, new FieldImageryResponse.Period(LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 9, 1)), List.of(-88.95, 40.45, -88.947, 40.453), images, true, "hls",
                new FieldImageryResponse.Source("NASA HLS", "Microsoft Planetary Computer",
                        List.of("hls2-l30", "hls2-s30"), 30, "https://hls.gsfc.nasa.gov/"), "note");
    }
}
