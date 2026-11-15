package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;

/**
 * Calendar rules shared by the crop planners.
 */
final class PlanYears {

    /** Number of future years every plan covers. */
    static final int PLANNING_HORIZON_YEARS = 5;

    private PlanYears() {
    }

    /**
     * Finds the first calendar year a plan covers: next year, or the year the
     * field becomes ready for planting when that is later.
     *
     * @param clock     calendar source
     * @param readyDate date the field is ready for planting, or {@code null} if unknown
     * @return the first planned calendar year
     */
    static int firstPlanYear(Clock clock, LocalDate readyDate) {
        int nextCalendarYear = Year.now(clock).getValue() + 1;
        return readyDate == null ? nextCalendarYear : Math.max(nextCalendarYear, readyDate.getYear());
    }
}
