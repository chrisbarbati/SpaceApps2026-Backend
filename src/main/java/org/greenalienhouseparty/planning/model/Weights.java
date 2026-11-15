package org.greenalienhouseparty.planning.model;

/**
 * How much the planner values soil health against income. The two weights
 * should sum to 1.
 *
 * @param soil   weight of the yearly soil score
 * @param income weight of the yearly income score
 */
public record Weights(double soil, double income) {
}
