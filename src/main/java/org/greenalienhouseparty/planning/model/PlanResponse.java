package org.greenalienhouseparty.planning.model;

import java.util.List;

/**
 * Several strategies side by side, with the recommended one and the farmer's
 * existing rotation as the baseline.
 *
 * <p>Not produced yet; see {@link Strategy}.</p>
 *
 * @param strategies  the strategies compared
 * @param recommended name of the recommended strategy
 * @param baseline    the existing rotation scored the same way
 */
public record PlanResponse(
        List<Strategy> strategies,
        String recommended,
        Strategy baseline) {
}
