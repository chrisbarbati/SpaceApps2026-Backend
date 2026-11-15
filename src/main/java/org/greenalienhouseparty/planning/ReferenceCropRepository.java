package org.greenalienhouseparty.planning;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/**
 * Crop catalog loaded from the reference file {@code crops/crop-reference.json}.
 *
 * <p>The file holds, per crop, environmental requirements taken from FAO
 * ECOCROP (soil pH, temperature, rainfall, growing cycle, rooting depth) and
 * the agronomic and economic values that no open dataset provides (nitrogen,
 * residue, cover, net margin, disease susceptibility), which remain
 * illustrative estimates. The planner's {@link Crop} takes its tolerated pH
 * range from ECOCROP and everything else from the estimates, so
 * {@link #exampleData()} stays {@code true} until those are reviewed.</p>
 */
@Repository
public class ReferenceCropRepository implements CropRepository {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCropRepository.class);

    static final String RESOURCE = "crops/crop-reference.json";

    private final CropReferenceCatalog catalog;

    private final List<Crop> crops;

    /**
     * Loads the reference file from the classpath.
     *
     * @param objectMapper JSON mapper used to read the file
     * @throws UncheckedIOException when the file is missing or unreadable
     */
    public ReferenceCropRepository(ObjectMapper objectMapper) {
        log.trace("Entering ReferenceCropRepository constructor");
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            this.catalog = objectMapper.readValue(in, CropReferenceCatalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Crop reference file " + RESOURCE + " could not be read", e);
        }
        this.crops = catalog.crops().stream().map(ReferenceCropRepository::toCrop).toList();
        log.debug("Loaded {} crops from the reference file", crops.size());
        log.trace("Exiting ReferenceCropRepository constructor");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Crop> findAll() {
        return crops;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Crop> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String wanted = idOrName.strip().toLowerCase(Locale.ROOT);
        return crops.stream()
                .filter(crop -> crop.id().equals(wanted) || crop.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Still {@code true}: the pH range is sourced, the other planner values are estimates.</p>
     */
    @Override
    public boolean exampleData() {
        return true;
    }

    /**
     * Returns the full reference data, including requirements the planner
     * does not use yet and the provenance of each group of values.
     *
     * @return the loaded reference catalog
     */
    public CropReferenceCatalog reference() {
        return catalog;
    }

    /**
     * Builds the planner's crop from a reference entry: tolerated pH range
     * from the sourced requirements, the rest from the estimates.
     *
     * @param reference one crop's reference data
     * @return the planner crop
     */
    private static Crop toCrop(CropReference reference) {
        CropReference.Requirements needs = reference.requirements();
        CropReference.Estimates estimates = reference.estimates();
        return new Crop(
                reference.id(),
                reference.name(),
                reference.family(),
                estimates.nFixKgHa(),
                estimates.nDemandKgHa(),
                estimates.residueScore(),
                estimates.coverMonths(),
                needs.phMin(),
                needs.phMax(),
                estimates.netMarginPerHa(),
                estimates.diseaseSusceptibility());
    }
}
