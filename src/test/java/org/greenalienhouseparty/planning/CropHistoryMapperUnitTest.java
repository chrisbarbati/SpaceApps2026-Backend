package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for translating crop-map classes into catalog crop ids.
 */
class CropHistoryMapperUnitTest {

    /**
     * Verifies Canadian and US class names for the same crop map to one id.
     */
    @Test
    void mapsProviderClassNamesByKeyword() {
        assertEquals(Optional.of("corn"), CropHistoryMapper.cropId("Corn for grain", "corn"));
        assertEquals(Optional.of("corn"), CropHistoryMapper.cropId("Corn", null));
        assertEquals(Optional.of("wheat"), CropHistoryMapper.cropId("Winter wheat", "wheat"));
        assertEquals(Optional.of("wheat"), CropHistoryMapper.cropId("Dbl Crop WinWht/Soybeans", "wheat"));
        assertEquals(Optional.of("soybeans"), CropHistoryMapper.cropId("Soybeans", "soybeans"));
        assertEquals(Optional.of("canola"), CropHistoryMapper.cropId("Canola/rapeseed", "canola"));
        assertEquals(Optional.of("grapes"), CropHistoryMapper.cropId("Vineyards", "fruit"));
    }

    /**
     * Verifies the class name separates crops the backend puts in one coarse group.
     */
    @Test
    void classNameIsMoreSpecificThanTheGroup() {
        assertEquals(Optional.of("barley"), CropHistoryMapper.cropId("Barley", "other_grains"));
        assertEquals(Optional.of("oats"), CropHistoryMapper.cropId("Oats", "other_grains"));
        assertEquals(Optional.of("potatoes"), CropHistoryMapper.cropId("Potatoes", "vegetables"));
        assertEquals(Optional.of("alfalfa"), CropHistoryMapper.cropId("Alfalfa", "hay"));
    }

    /**
     * Verifies the group is used when the name says nothing, and that classes
     * outside the catalog stay unmapped.
     */
    @Test
    void fallsBackToTheGroupAndLeavesUnknownClassesUnmapped() {
        assertEquals(Optional.of("alfalfa"), CropHistoryMapper.cropId("Other hay", "hay"));
        assertEquals(Optional.of("alfalfa"), CropHistoryMapper.cropId("Pasture/forages", "hay"));
        assertTrue(CropHistoryMapper.cropId("Rye", "other_grains").isEmpty());
        assertTrue(CropHistoryMapper.cropId("Sweet Potatoes", "vegetables").isEmpty());
        assertTrue(CropHistoryMapper.cropId("Forest (undifferentiated)", "natural").isEmpty());
        assertTrue(CropHistoryMapper.cropId(null, null).isEmpty());
    }
}
