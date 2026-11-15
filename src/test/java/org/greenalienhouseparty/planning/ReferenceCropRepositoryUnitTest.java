package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the crop catalog loaded from the reference file.
 */
class ReferenceCropRepositoryUnitTest {

    private final ReferenceCropRepository repository = new ReferenceCropRepository(JsonMapper.builder().build());

    /**
     * Verifies the file loads the same nine crops as before, in order, with
     * the tolerated pH range taken from the sourced requirements.
     */
    @Test
    void loadsNineCropsWithSourcedPhRange() {
        assertEquals(new ExampleCropRepository().findAll().stream().map(Crop::id).toList(),
                repository.findAll().stream().map(Crop::id).toList());
        Crop corn = repository.find("Corn").orElseThrow();
        assertEquals(4.5, corn.phMin());
        assertEquals(8.5, corn.phMax());
        assertEquals("Poaceae", corn.family());
        assertEquals(170, corn.nDemandKgHa());
        assertTrue(repository.find("coffee").isEmpty());
        assertTrue(repository.exampleData());
    }

    /**
     * Verifies every crop has complete, ordered requirements and that both
     * value groups name their source.
     */
    @Test
    void referenceHasOrderedRequirementsAndSources() {
        CropReferenceCatalog catalog = repository.reference();
        assertEquals("FAO ECOCROP", catalog.sources().get("requirements").name());
        assertNull(catalog.sources().get("estimates").url());
        for (CropReference crop : catalog.crops()) {
            CropReference.Requirements needs = crop.requirements();
            assertTrue(needs.phMin() <= needs.phOptimalMin() && needs.phOptimalMax() <= needs.phMax(), crop.id());
            assertTrue(needs.tempMinC() <= needs.tempOptimalMinC() && needs.tempOptimalMaxC() <= needs.tempMaxC(), crop.id());
            assertTrue(needs.rainMinMm() <= needs.rainOptimalMinMm() && needs.rainOptimalMaxMm() <= needs.rainMaxMm(), crop.id());
            assertTrue(needs.cycleMinDays() <= needs.cycleMaxDays(), crop.id());
            assertTrue(!crop.scientificName().isBlank(), crop.id());
        }
        assertEquals("Glycine max", catalog.crops().get(1).scientificName());
        assertNull(catalog.crops().get(0).requirements().killingTempC());
    }
}
