package org.greenalienhouseparty.planning;

import java.util.List;
import java.util.Optional;

import org.greenalienhouseparty.planning.model.Crop;

/**
 * Source of the crops the application knows how to plan with.
 */
public interface CropRepository {

    /**
     * Returns every known crop in display order.
     *
     * @return immutable list of crops
     */
    List<Crop> findAll();

    /**
     * Looks a crop up by its id, or failing that by its display name, ignoring
     * case and surrounding whitespace.
     *
     * @param idOrName crop id such as {@code corn}, or display name such as {@code Corn}
     * @return the crop, or empty when it is unknown or the input is blank
     */
    Optional<Crop> find(String idOrName);

    /**
     * Says whether the crop attributes are illustrative placeholders rather
     * than sourced figures.
     *
     * @return {@code true} when the data is example data
     */
    boolean exampleData();
}
