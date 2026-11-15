package org.greenalienhouseparty.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.greenalienhouseparty.dto.CropMarketResponse;
import org.greenalienhouseparty.dto.CropMarketResponse.CropMarket;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.market.StatCanClient.DataPoint;
import org.greenalienhouseparty.market.StatCanClient.Series;
import org.greenalienhouseparty.market.StatCanClient.SeriesKey;
import org.greenalienhouseparty.market.StatCanClient.SeriesResult;
import org.greenalienhouseparty.planning.ExampleCropRepository;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for turning Statistics Canada series into per-crop yields,
 * prices and gross revenue.
 */
class CropMarketServiceUnitTest {

    private final StatCanClient client = mock(StatCanClient.class);

    private final CropMarketService service =
            new CropMarketService(client, new ExampleCropRepository(), Duration.ofHours(12));

    /**
     * Verifies yields and prices are averaged over published periods, the
     * latest published value is reported, and gross revenue is their product.
     */
    @Test
    void combinesYieldAndPriceIntoGrossRevenue() {
        answerWithFixtures();

        CropMarketResponse response = service.market("on");

        assertEquals("ON", response.province());
        assertEquals("Ontario", response.provinceName());
        assertEquals(9, response.crops().size());
        CropMarket corn = crop(response, "corn");
        assertEquals(11000.0, corn.yieldKgPerHa());
        assertEquals(2025, corn.yieldYear());
        assertEquals(10500.0, corn.yieldMeanKgPerHa());
        assertEquals(2, corn.yieldYears());
        assertEquals(260.0, corn.pricePerTonne());
        assertEquals("2026-09", corn.priceMonth());
        assertEquals(250.0, corn.priceMeanPerTonne());
        assertEquals(2625.0, corn.grossRevenuePerHa());
        assertEquals("Corn for grain", corn.yieldSeries());
        assertTrue(corn.notes().isEmpty());
    }

    /**
     * Verifies Ontario wheat uses its own price series, potato prices are
     * converted from hundredweight, and uncovered crops come back with nulls
     * and an explanation instead of being dropped.
     */
    @Test
    void handlesSpecialSeriesAndGaps() {
        List<List<SeriesKey>> requested = answerWithFixtures();

        CropMarketResponse response = service.market(null);

        assertTrue(requested.get(1).contains(new SeriesKey(CropMarketService.PRICE_TABLE, "6.2.0.0.0.0.0.0.0.0")));
        assertEquals("Ontario wheat excluding payments", crop(response, "wheat").priceSeries());

        CropMarket potatoes = crop(response, "potatoes");
        assertNull(potatoes.yieldKgPerHa());
        assertEquals(440.92, potatoes.pricePerTonne());
        assertNull(potatoes.grossRevenuePerHa());
        assertEquals(2, potatoes.notes().size());

        CropMarket alfalfa = crop(response, "alfalfa");
        assertEquals("Tame hay", alfalfa.yieldSeries());
        assertNull(alfalfa.pricePerTonne());

        CropMarket grapes = crop(response, "grapes");
        assertNull(grapes.yieldSeries());
        assertNull(grapes.priceSeries());
        assertEquals(1, grapes.notes().size());

        CropMarket oats = crop(response, "oats");
        assertNull(oats.yieldKgPerHa());
        assertNull(oats.priceMeanPerTonne());
        assertEquals(2, oats.notes().size());
    }

    /**
     * Verifies answers are cached per province, other provinces use their own
     * numbering, and unknown provinces are rejected before any request.
     */
    @Test
    void cachesPerProvinceAndValidatesTheCode() {
        List<List<SeriesKey>> requested = answerWithFixtures();

        service.market("ON");
        service.market("ON");
        verify(client, times(2)).latestPeriods(anyList(), anyInt());

        service.market("SK");
        verify(client, times(4)).latestPeriods(anyList(), anyInt());
        assertTrue(requested.get(2).contains(new SeriesKey(CropMarketService.YIELD_TABLE, "10.18.11.0.0.0.0.0.0.0")));
        assertTrue(requested.get(3).contains(new SeriesKey(CropMarketService.PRICE_TABLE, "8.3.0.0.0.0.0.0.0.0")));

        var fresh = mock(StatCanClient.class);
        var other = new CropMarketService(fresh, new ExampleCropRepository(), Duration.ofHours(12));
        assertThrows(InvalidRequestException.class, () -> other.market("ZZ"));
        verifyNoInteractions(fresh);
    }

    /**
     * Verifies a Statistics Canada outage is passed on and not cached.
     */
    @Test
    void passesOnOutagesWithoutCachingThem() {
        when(client.latestPeriods(anyList(), anyInt()))
                .thenThrow(new ExternalDataException(502, "Statistics Canada is unavailable"));

        assertThrows(ExternalDataException.class, () -> service.market("ON"));
        assertThrows(ExternalDataException.class, () -> service.market("ON"));
        verify(client, times(2)).latestPeriods(anyList(), anyInt());
    }

    /**
     * Verifies coordinates are padded to ten positions.
     */
    @Test
    void buildsTenPositionCoordinates() {
        assertEquals("7.18.11.0.0.0.0.0.0.0", CropMarketService.coordinate(7, 18, 11));
        assertEquals("6.16.0.0.0.0.0.0.0.0", CropMarketService.coordinate(6, 16));
    }

    /**
     * Makes the mocked client answer every requested series: corn with two
     * published yields and prices, potatoes priced per hundredweight, oats
     * failed, and everything else with one value.
     *
     * @return the series requested by each call, in call order
     */
    private List<List<SeriesKey>> answerWithFixtures() {
        List<List<SeriesKey>> requested = new ArrayList<>();
        when(client.latestPeriods(anyList(), anyInt())).thenAnswer(call -> {
            List<SeriesKey> keys = call.getArgument(0);
            requested.add(keys);
            return keys.stream().map(CropMarketServiceUnitTest::answer).toList();
        });
        return requested;
    }

    /**
     * Builds the fixture answer for one series.
     *
     * @param key the requested series
     * @return a successful or failed result
     */
    private static SeriesResult answer(SeriesKey key) {
        String[] parts = key.coordinate().split("\\.");
        boolean yield = key.productId() == CropMarketService.YIELD_TABLE;
        int item = Integer.parseInt(parts[yield ? 2 : 1]);
        List<DataPoint> points;
        if (yield && item == 11) {
            points = Arrays.asList(new DataPoint("2024-01-01", 10000.0), new DataPoint("2025-01-01", 11000.0),
                    new DataPoint("2026-01-01", null));
        } else if (!yield && item == 16) {
            points = List.of(new DataPoint("2026-08-01", 240.0), new DataPoint("2026-09-01", 260.0));
        } else if (!yield && item == 23) {
            points = Arrays.asList(new DataPoint("2026-07-01", 20.0), new DataPoint("2026-08-01", null));
        } else if (item == 5 || item == 13) {
            return new SeriesResult("FAILED", new Series(key.productId(), key.coordinate(), List.of()));
        } else {
            points = List.of(new DataPoint("2025-01-01", yield ? 3000.0 : 300.0));
        }
        return new SeriesResult("SUCCESS", new Series(key.productId(), key.coordinate(), points));
    }

    /**
     * Finds one crop's entry.
     *
     * @param response the market response
     * @param cropId   crop id wanted
     * @return the entry
     */
    private static CropMarket crop(CropMarketResponse response, String cropId) {
        return response.crops().stream().filter(crop -> crop.cropId().equals(cropId)).findFirst().orElseThrow();
    }
}
