package org.greenalienhouseparty.planning;

import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.planning.model.CropReference;

/**
 * The crop reference file as loaded: every crop and where each group of
 * values came from.
 *
 * @param sources provenance of the value groups, keyed {@code requirements}, {@code estimates}
 *                and {@code notYetSourced}
 * @param crops   reference data per crop, in display order
 */
public record CropReferenceCatalog(Map<String, Source> sources, List<CropReference> crops) {

    /**
     * Where a group of values came from.
     *
     * @param name dataset name
     * @param url  dataset URL, or {@code null} for estimates
     * @param note how to read the values and how they were obtained
     */
    public record Source(String name, String url, String note) {
    }
}
