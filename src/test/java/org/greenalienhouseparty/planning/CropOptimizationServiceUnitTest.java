package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for crop catalog and optimization request validation/service flow.
 */
@ExtendWith(MockitoExtension.class)
class CropOptimizationServiceUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private CropRotationPlanner planner;

    private CropRepository crops;

    private CropOptimizationService service;

    /**
     * Creates the service with the example crop catalog and a mocked planner.
     */
    @BeforeEach
    void setUp() {
        crops = new ExampleCropRepository();
        service = new CropOptimizationService(crops, planner);
    }

    /**
     * Verifies the catalog returns the established crop choices as rich crop
     * entities and flags the figures as example data.
     */
    @Test
    void returnsCropCatalogWithAttributes() {
        var catalog = service.catalog();

        assertEquals(List.of("Corn", "Soybeans", "Wheat", "Barley", "Oats", "Canola", "Potatoes", "Alfalfa", "Grapes"),
                catalog.crops().stream().map(crop -> crop.name()).toList());
        assertEquals("corn", catalog.crops().getFirst().id());
        assertEquals("Poaceae", catalog.crops().getFirst().family());
        assertEquals(true, catalog.exampleData());
        assertEquals(true, catalog.note().contains("examples"));
    }

    /**
     * Verifies crops submitted by id reach the planner as display names, and
     * that an id and a name for the same crop count as a duplicate.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void acceptsCropIdsAndPassesDisplayNamesToThePlanner() throws Exception {
        var submitted = new CropOptimizationRequest.FieldRequest(
                "field-1", field("field-1", List.of()).geometry(), "corn", List.of("soybeans", " Wheat "), null);
        var expected = new CropOptimizationRequest.FieldRequest(
                "field-1", submitted.geometry(), "Corn", List.of("Soybeans", "Wheat"), null);

        service.optimize(new CropOptimizationRequest(List.of(submitted)));

        verify(planner).plan(expected);
        var duplicate = new CropOptimizationRequest(List.of(field("field-1", List.of("soybeans", "Soybeans"))));
        assertThrows(CropOptimizationValidationException.class, () -> service.optimize(duplicate));
    }

    /**
     * Verifies each valid field is sent to the planner and the response labels
     * the result as temporary.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void createsTemporaryPlansForEveryField() throws Exception {
        var first = field("field-1", List.of("Soybeans", "Wheat"));
        var second = field("field-2", List.of("Oats"));
        var firstPlan = new CropOptimizationResponse.FieldPlan("field-1", "Corn", List.of());
        var secondPlan = new CropOptimizationResponse.FieldPlan("field-2", "Soybeans", List.of());
        when(planner.plan(first)).thenReturn(firstPlan);
        when(planner.plan(second)).thenReturn(secondPlan);
        when(planner.id()).thenReturn("random-placeholder");
        when(planner.temporary()).thenReturn(true);

        CropOptimizationResponse response = service.optimize(new CropOptimizationRequest(List.of(first, second)));

        assertEquals(List.of(firstPlan, secondPlan), response.plans());
        assertEquals("random-placeholder", response.planner());
        assertEquals(true, response.temporary());
        verify(planner).plan(first);
        verify(planner).plan(second);
    }

    /**
     * Verifies unsupported crops are rejected before the planner is called.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void rejectsUnsupportedCropNames() throws Exception {
        var invalid = new CropOptimizationRequest(List.of(field("field-1", List.of("Coffee"))));

        CropOptimizationValidationException exception = assertThrows(
                CropOptimizationValidationException.class, () -> service.optimize(invalid));

        assertEquals("fields[0].growableCrops contains an unsupported crop", exception.getMessage());
        verifyNoInteractions(planner);
    }

    /**
     * Verifies every field must provide a non-empty growable-crop list.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void rejectsEmptyGrowableCropList() throws Exception {
        var invalid = new CropOptimizationRequest(List.of(field("field-1", List.of())));

        assertThrows(CropOptimizationValidationException.class, () -> service.optimize(invalid));
        verifyNoInteractions(planner);
    }

    /**
     * Builds a field input with a polygon and a known current crop.
     *
     * @param id           field identifier
     * @param growableCrops crops the farmer can grow
     * @return field planning input
     * @throws Exception if the geometry fixture cannot be parsed
     */
    private CropOptimizationRequest.FieldRequest field(String id, List<String> growableCrops) throws Exception {
        return new CropOptimizationRequest.FieldRequest(
                id,
                JSON.readTree("{\"type\":\"Polygon\",\"coordinates\":["
                        + "[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"),
                "Corn",
                growableCrops,
                LocalDate.of(2027, 4, 1));
    }
}
