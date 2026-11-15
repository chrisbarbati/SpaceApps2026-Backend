package org.greenalienhouseparty.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.controller.CropHistoryController;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse.CropYear;
import org.greenalienhouseparty.dto.FieldCropHistoryBatchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises all public inference routes and JSON serialization without opening network sockets. */
class CropHistoryApiUnitTest {
    private DataBackendClient client;
    private MockMvc mvc;

    /** Uses fixed application time and the real interpreter behind the MVC controller. */
    @BeforeEach
    void setUp() {
        client = mock(DataBackendClient.class);
        var inference = new CropHistoryInferenceService(Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new CropHistoryController(new DataBackendService(client, inference))).build();
    }

    /** Constructs the five acquired observations from the reported rotation example. */
    private static CropHistoryResponse observations() {
        List<CropYear> rows = new ArrayList<>();
        var names = List.of("Winter wheat", "Corn", "Soybeans", "Winter wheat", "Corn");
        for (int index = 0; index < names.size(); index++) {
            rows.add(new CropYear(2021 + index, names.get(index), index, true, null, null, 0.95, 1.0, List.of()));
        }
        return new CropHistoryResponse("field", null, rows, names, names, 5, List.of(), null, "Source observations");
    }

    /** The indexed-field endpoint returns Java's estimate and the distinct prior observation. */
    @Test
    void indexedFieldReturnsJavaInterpretation() throws Exception {
        when(client.fieldCropHistory("field")).thenReturn(observations());
        mvc.perform(get("/fields/field/crop-history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.currentCrop.crop").value("Soybeans"))
                .andExpect(jsonPath("$.summary.currentCrop.status").value("estimated"))
                .andExpect(jsonPath("$.summary.reviewYear").value(2026))
                .andExpect(jsonPath("$.summary.latestMapped.groupLabel").value("Corn"))
                .andExpect(jsonPath("$.summary.recentYears[0].year").value(2021));
    }

    /** The outline endpoint applies inference to raw provider observations as well. */
    @Test
    void outlineReturnsJavaInterpretation() throws Exception {
        when(client.cropHistory(any())).thenReturn(observations());
        mvc.perform(post("/crop-history").contentType(MediaType.APPLICATION_JSON)
                .content("{\"polygon\":{\"type\":\"Polygon\",\"coordinates\":[]}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.expectedThisYear.crop").value("Soybeans"));
    }

    /** Batch enrichment preserves order, errors and counts while interpreting each successful field. */
    @Test
    void batchReturnsJavaInterpretationAndPreservesErrors() throws Exception {
        when(client.fieldCropHistories(List.of("field", "missing"))).thenReturn(new FieldCropHistoryBatchResponse(List.of(
                new FieldCropHistoryBatchResponse.Result("field", "ok", observations(), null),
                new FieldCropHistoryBatchResponse.Result("missing", "error", null,
                        new FieldCropHistoryBatchResponse.Error(404, "Unknown field"))), 2, 1, 1));
        mvc.perform(post("/fields/crop-history").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"field\",\"missing\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].history.summary.currentCrop.crop").value("Soybeans"))
                .andExpect(jsonPath("$.results[1].error.code").value(404)).andExpect(jsonPath("$.failed").value(1));
    }
}
