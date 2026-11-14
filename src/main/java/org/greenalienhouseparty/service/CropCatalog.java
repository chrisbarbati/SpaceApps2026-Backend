package org.greenalienhouseparty.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Canonical fixed crop list used by the initial crop-planning experience.
 */
@Component
public class CropCatalog {

    private static final Logger log = LoggerFactory.getLogger(CropCatalog.class);

    private static final List<String> CROPS = List.of(
            "Corn",
            "Soybeans",
            "Wheat",
            "Barley",
            "Oats",
            "Canola",
            "Potatoes",
            "Alfalfa",
            "Grapes");

    /**
     * Returns the supported crop names in display order.
     *
     * @return immutable list of crop names
     */
    public List<String> crops() {
        log.trace("Entering crops()");
        log.debug("Returning the fixed crop catalog");
        try {
            return CROPS;
        } finally {
            log.trace("Exiting crops()");
        }
    }

    /**
     * Checks whether a crop name belongs to the supported fixed catalog.
     *
     * @param crop crop name to check
     * @return {@code true} when the crop is supported
     */
    public boolean supports(String crop) {
        log.trace("Entering supports()");
        log.debug("Checking crop catalog membership");
        try {
            return crop != null && CROPS.contains(crop);
        } finally {
            log.trace("Exiting supports()");
        }
    }
}
