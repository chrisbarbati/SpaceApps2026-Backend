package org.greenalienhouseparty.planning;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.greenalienhouseparty.planning.model.Crop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Fixed crop list with <strong>example values</strong>.
 *
 * <p>The Python data backend describes fields (soil, terrain, weather, crop
 * history) but nothing about crops themselves, so every agronomic and economic
 * attribute here is an illustrative placeholder chosen to be plausible for
 * southern Ontario. None of it is sourced, and it must be replaced with
 * reviewed regional figures before plans are presented as recommendations.</p>
 *
 * <p>Gaps between what the rotation planner needs and what the data backend
 * provides:</p>
 * <ul>
 *   <li><strong>Crop attributes</strong> (family, nitrogen fixed and demanded, residue, cover months,
 *       pH range, net margin, disease susceptibility): no source; all example data.</li>
 *   <li><strong>Erosion risk</strong>: the backend rates terrain only as low, moderate or high, so the
 *       0..1 value is a three-step approximation.</li>
 *   <li><strong>Crop history</strong>: the newest crop map is last season, so the current crop comes from
 *       the farmer; map classes are coarser than this list (barley and oats can arrive as "other
 *       grains", alfalfa as "hay"); there is no history outside Canada and the United States.</li>
 *   <li><strong>Soil</strong>: pH, texture and organic matter are 250 m model estimates, not soil tests,
 *       and are missing over water or built-up land.</li>
 *   <li><strong>Weights</strong>: the farmer cannot yet state soil-versus-income priorities.</li>
 *   <li><strong>Unused data</strong>: weather, water balance, frost dates, crop heat units, SMAP soil
 *       moisture and NDVI are available but the planner does not read them yet.</li>
 * </ul>
 */
@Repository
public class ExampleCropRepository implements CropRepository {

    private static final Logger log = LoggerFactory.getLogger(ExampleCropRepository.class);

    //                                       id          name        family          nFix  nDem  resid cover pHmin pHmax margin disease
    private static final List<Crop> CROPS = List.of(
            new Crop("corn", "Corn", "Poaceae", 0, 170, 0.8, 5, 5.8, 7.5, 450, 0.5),
            new Crop("soybeans", "Soybeans", "Fabaceae", 120, 100, 0.3, 4, 6.0, 7.5, 400, 0.6),
            new Crop("wheat", "Wheat", "Poaceae", 0, 110, 0.6, 9, 5.5, 7.5, 250, 0.5),
            new Crop("barley", "Barley", "Poaceae", 0, 90, 0.5, 4, 6.0, 8.0, 180, 0.5),
            new Crop("oats", "Oats", "Poaceae", 0, 80, 0.5, 4, 5.0, 7.5, 150, 0.3),
            new Crop("canola", "Canola", "Brassicaceae", 0, 130, 0.4, 4, 5.5, 7.5, 350, 0.8),
            new Crop("potatoes", "Potatoes", "Solanaceae", 0, 180, 0.1, 4, 4.8, 6.5, 1500, 0.9),
            new Crop("alfalfa", "Alfalfa", "Fabaceae", 150, 0, 0.7, 12, 6.2, 7.8, 300, 0.3),
            new Crop("grapes", "Grapes", "Vitaceae", 0, 50, 0.2, 12, 5.5, 7.0, 3000, 0.4));

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Crop> findAll() {
        log.trace("Entering findAll()");
        log.debug("Returning the example crop list");
        try {
            return CROPS;
        } finally {
            log.trace("Exiting findAll()");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Crop> find(String idOrName) {
        log.trace("Entering find()");
        try {
            if (idOrName == null || idOrName.isBlank()) {
                return Optional.empty();
            }
            String wanted = idOrName.strip().toLowerCase(Locale.ROOT);
            return CROPS.stream()
                    .filter(crop -> crop.id().equals(wanted) || crop.name().toLowerCase(Locale.ROOT).equals(wanted))
                    .findFirst();
        } finally {
            log.trace("Exiting find()");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean exampleData() {
        return true;
    }
}
