package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.greenalienhouseparty.planning.model.Crop;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the example crop catalog.
 */
class ExampleCropRepositoryUnitTest {

    private final ExampleCropRepository repository = new ExampleCropRepository();

    /**
     * Verifies every example crop has a unique id and values inside the
     * ranges the planner expects.
     */
    @Test
    void everyCropHasUniqueIdAndValuesInRange() {
        List<Crop> crops = repository.findAll();

        assertEquals(9, crops.size());
        assertEquals(crops.size(), new HashSet<>(crops.stream().map(Crop::id).toList()).size());
        assertTrue(repository.exampleData());
        for (Crop crop : crops) {
            assertTrue(crop.residueScore() >= 0 && crop.residueScore() <= 1, crop.id());
            assertTrue(crop.diseaseSusceptibility() >= 0 && crop.diseaseSusceptibility() <= 1, crop.id());
            assertTrue(crop.coverMonths() >= 0 && crop.coverMonths() <= 12, crop.id());
            assertTrue(crop.phMin() < crop.phMax(), crop.id());
            assertTrue(crop.nFixKgHa() >= 0 && crop.nDemandKgHa() >= 0, crop.id());
            assertTrue(!crop.family().isBlank() && !crop.name().isBlank(), crop.id());
        }
    }

    /**
     * Verifies crops are found by id or display name, ignoring case and
     * surrounding spaces, and that unknown or blank input finds nothing.
     */
    @Test
    void findsByIdOrNameIgnoringCase() {
        assertEquals("corn", repository.find("corn").orElseThrow().id());
        assertEquals("corn", repository.find("Corn").orElseThrow().id());
        assertEquals("soybeans", repository.find("  SOYBEANS ").orElseThrow().id());
        assertTrue(repository.find("Coffee").isEmpty());
        assertTrue(repository.find(" ").isEmpty());
        assertTrue(repository.find(null).isEmpty());
    }

    /**
     * Verifies every id the crop-history mapper can produce exists in the catalog.
     */
    @Test
    void historyMapperOnlyProducesKnownCropIds() {
        for (String className : List.of("Corn for grain", "Soybeans", "Winter wheat", "Barley", "Oats",
                "Canola/rapeseed", "Potatoes", "Alfalfa", "Vineyards")) {
            String id = CropHistoryMapper.cropId(className, null).orElseThrow();
            assertTrue(repository.find(id).isPresent(), className);
        }
        assertTrue(repository.find(CropHistoryMapper.cropId("Other hay", "hay").orElseThrow()).isPresent());
    }
}
