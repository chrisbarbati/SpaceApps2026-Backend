package org.greenalienhouseparty.planning.model;

/**
 * How much the planner values soil health, income and crop reliability
 * against each other. The three weights should sum to 1.
 *
 * @param soil        weight of the yearly soil score
 * @param income      weight of the yearly income score
 * @param reliability weight of the yearly reliability score (how well the field's climate and soil suit the crop)
 */
public record Weights(double soil, double income, double reliability) {

    /**
     * Creates weights that ignore crop reliability.
     *
     * @param soil   weight of the yearly soil score
     * @param income weight of the yearly income score
     */
    public Weights(double soil, double income) {
        this(soil, income, 0.0);
    }
}
