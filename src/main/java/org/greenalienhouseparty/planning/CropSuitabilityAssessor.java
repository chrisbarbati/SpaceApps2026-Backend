package org.greenalienhouseparty.planning;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;

/**
 * Judges how well a field's climate record and soil suit a crop.
 *
 * <p>Compares the field's growing-season temperature, yearly rainfall, frost-free season length, soil
 * texture, drainage and land capability class with what the crop needs (FAO ECOCROP). Each comparison
 * gives a {@link Fit} from 0 to 1, and the crop's {@link Reliability} is the lowest of them, because one
 * unsuitable condition limits a crop however good the others are. A condition with no data counts as a
 * fit of 1, so a missing value never rules a crop out.</p>
 *
 * <p>Shared by {@link DynamicCropRotationPlanner}, which scores crop choices with it, and by the chat
 * assistant's suitability tool, so both give the same answer for the same field. Stateless and safe to
 * share between threads.</p>
 */
public final class CropSuitabilityAssessor {

    /** Names of the conditions, in the order they are compared. */
    private static final String[] CONDITION_NAMES =
            {"temperature", "rainfall", "season length", "soil texture", "drainage", "land class"};

    /**
     * How well one condition of the field matches what a crop needs.
     *
     * @param value          1 when the field is inside the crop's optimal range, falling to 0 at the limit of what it tolerates
     * @param direction      -1 when the field is below what the crop prefers, 1 when above, 0 when it fits or is unknown
     * @param known          whether there was data to compare
     * @param years          number of past years compared, when the fit is a share of years
     * @param yearsTolerated how many of those years were inside the range the crop tolerates
     */
    public record Fit(double value, int direction, boolean known, int years, int yearsTolerated) {

        /** A fit that could not be checked: counted as fully suitable so a missing value never rules a crop out. */
        public static final Fit UNKNOWN = new Fit(1, 0, false, 0, 0);

        /**
         * Creates a fit that was checked against data.
         *
         * @param value     the fit, 0 to 1
         * @param direction -1 below what the crop prefers, 1 above, 0 otherwise
         * @return the fit
         */
        public static Fit of(double value, int direction) {
            return new Fit(value, direction, true, 0, 0);
        }
    }

    /**
     * How reliable a crop is in a field: the lowest of the individual fits, because the weakest condition
     * limits the crop however good the others are.
     *
     * @param value        reliability, 0 (unsuited) to 1 (well suited)
     * @param limitedBy    the condition with the lowest fit, or {@code null} when nothing limits the crop
     * @param known        whether any condition could be checked
     * @param temperature  growing-season temperature against the crop's range
     * @param rainfall     yearly rainfall against the crop's range, as a share of past years
     * @param seasonLength share of past years with enough frost-free days for the crop's shortest cycle
     * @param texture      soil texture against the crop's tolerance
     * @param drainage     soil drainage against the crop's tolerance
     * @param landClass    regional land capability class against the kind of crop
     */
    public record Reliability(
            double value,
            String limitedBy,
            boolean known,
            Fit temperature,
            Fit rainfall,
            Fit seasonLength,
            Fit texture,
            Fit drainage,
            Fit landClass) {}

    /**
     * Determines whether the crop may be planted in this field at all: the field's soil pH must be inside
     * the range the crop tolerates, and its climate and soil must not be outside what the crop tolerates
     * (a reliability of 0).
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return whether the crop may be planted in this field
     */
    public boolean isCropAllowed(Field field, Crop crop) {
        return acidityTolerated(field, crop) && assessReliability(field, crop, 0).value() > 0;
    }

    /**
     * Says whether the field's estimated soil pH is inside the range the crop tolerates.
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return {@code true} when the pH is tolerated
     */
    public boolean acidityTolerated(Field field, Crop crop) {
        return field.ph() >= crop.phMin() && field.ph() <= crop.phMax();
    }

    /**
     * Works out how reliable a crop is in a field by comparing the field's conditions with what the crop needs.
     *
     * @param field    the field
     * @param crop     the crop being considered
     * @param planYear position of the year in a plan, starting at 1; 0 to ignore the seasonal outlook
     * @return the reliability, the condition that limits it and the individual fits
     */
    public Reliability assessReliability(Field field, Crop crop, int planYear) {
        Fit temperature = temperatureFit(field, crop);
        Fit rainfall = rainfallFit(field, crop, planYear);
        Fit seasonLength = seasonLengthFit(field, crop);
        Fit texture = textureFit(field, crop);
        Fit drainage = drainageFit(field, crop);
        Fit landClass = landClassFit(field, crop);

        // The reliability is the lowest fit; remember which condition it came from
        Fit[] fits = {temperature, rainfall, seasonLength, texture, drainage, landClass};
        double lowest = 1;
        String limitedBy = null;
        boolean known = false;
        for (int index = 0; index < fits.length; index++) {
            known = known || fits[index].known();
            if (fits[index].value() < lowest) {
                lowest = fits[index].value();
                limitedBy = CONDITION_NAMES[index];
            }
        }
        return new Reliability(lowest, limitedBy, known, temperature, rainfall, seasonLength, texture, drainage, landClass);
    }

    /**
     * Describes the condition that limits a crop's reliability in plain language.
     *
     * @param field       the field
     * @param reliability the crop's reliability in the field
     * @return the end of a sentence that begins "Less reliable here: " or "Suits this field, though "
     */
    public String describeLimit(Field field, Reliability reliability) {
        return switch (String.valueOf(reliability.limitedBy())) {
            case "temperature" -> reliability.temperature().direction() < 0
                    ? "the growing season is cooler than this crop prefers."
                    : "the growing season is warmer than this crop prefers.";
            case "rainfall" -> reliability.rainfall().direction() < 0
                    ? "most years are drier than this crop prefers."
                    : "most years are wetter than this crop prefers.";
            case "season length" -> "in some years the frost-free season is too short for it.";
            case "soil texture" -> "this " + String.valueOf(field.texture()).toLowerCase(Locale.ROOT)
                    + " soil is not the texture it does best in.";
            case "drainage" -> switch (String.valueOf(field.conditions().drainageCode()).toUpperCase(Locale.ROOT)) {
                case "R" -> "the regional soil map shows rapidly drained soils, which hold little water for it.";
                case "P" -> "the regional soil map shows poorly drained soils, which does not suit it unless the field is tile-drained.";
                default -> "the regional soil map shows imperfectly drained soils, which does not suit it unless the field is tile-drained.";
            };
            case "land class" -> "the regional soil map rates this area land class " + field.conditions().landClass()
                    + ", which limits annual crops; check that against the field itself.";
            default -> "the field's conditions are not ideal for it.";
        };
    }

    /**
     * Compares the mean temperature of the crop's growing months with the temperature range the crop needs.
     *
     * <p>The growing months are the crop calendar's planting-to-maturity months when the calendar covers the
     * crop and it is sown and harvested in one calendar year; otherwise the field's main growing season. Months
     * colder than the crop's lowest tolerated temperature are left out, because the crop is not growing then.</p>
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return the temperature fit, or {@link Fit#UNKNOWN} without a temperature record or crop requirements
     */
    Fit temperatureFit(Field field, Crop crop) {
        CropReference.Requirements needs = crop.requirements();
        List<Double> monthlyMeanTempC = field.conditions().monthlyMeanTempC();
        if (needs == null || monthlyMeanTempC.size() != 12 || needs.tempMinC() == null || needs.tempOptimalMinC() == null
                || needs.tempOptimalMaxC() == null || needs.tempMaxC() == null) {
            return Fit.UNKNOWN;
        }
        double total = 0;
        int warmMonths = 0;
        int monthsWithData = 0;
        for (int month : growingMonths(field.conditions(), crop)) {
            Double meanTempC = monthlyMeanTempC.get(month - 1);
            if (meanTempC == null) {
                continue;
            }
            monthsWithData++;
            if (meanTempC >= needs.tempMinC()) {
                total += meanTempC;
                warmMonths++;
            }
        }
        if (monthsWithData == 0) {
            return Fit.UNKNOWN;
        }
        if (warmMonths == 0) {
            // No growing month is warm enough for the crop to grow at all
            return Fit.of(0, -1);
        }
        return rangeFit(total / warmMonths, needs.tempMinC(), needs.tempOptimalMinC(), needs.tempOptimalMaxC(), needs.tempMaxC());
    }

    /**
     * Compares each past year's rainfall with the rainfall range the crop needs and averages the result, so the
     * fit reads as the share of years that suited the crop.
     *
     * <p>In the first plan year, when there is a seasonal outlook, the driest, middle and wettest thirds of
     * past years are weighted by the outlook's chances of a dry, normal and wet season instead of equally.</p>
     *
     * @param field    the field
     * @param crop     the crop being considered
     * @param planYear position of the year in the plan, starting at 1
     * @return the rainfall fit, or {@link Fit#UNKNOWN} without a rainfall record or crop requirements
     */
    Fit rainfallFit(Field field, Crop crop, int planYear) {
        CropReference.Requirements needs = crop.requirements();
        FieldConditions conditions = field.conditions();
        if (needs == null || conditions.yearlyRainfallMm().isEmpty() || needs.rainMinMm() == null
                || needs.rainOptimalMinMm() == null || needs.rainOptimalMaxMm() == null || needs.rainMaxMm() == null) {
            return Fit.UNKNOWN;
        }

        // Driest year first, so the thirds below are the dry, normal and wet years
        List<Double> rainfallByYear = conditions.yearlyRainfallMm().stream().sorted().toList();
        int years = rainfallByYear.size();
        double[] fitByYear = new double[years];
        int yearsTolerated = 0;
        int drierYears = 0;
        int wetterYears = 0;
        for (int index = 0; index < years; index++) {
            double rainfallMm = rainfallByYear.get(index);
            Fit yearFit = rangeFit(rainfallMm, needs.rainMinMm(), needs.rainOptimalMinMm(), needs.rainOptimalMaxMm(), needs.rainMaxMm());
            fitByYear[index] = yearFit.value();
            if (rainfallMm >= needs.rainMinMm() && rainfallMm <= needs.rainMaxMm()) {
                yearsTolerated++;
            }
            if (yearFit.direction() < 0) {
                drierYears++;
            } else if (yearFit.direction() > 0) {
                wetterYears++;
            }
        }

        double value = mean(fitByYear, 0, years);
        FieldConditions.RainfallChances outlook = conditions.firstYearRainfall();
        if (planYear == 1 && outlook != null && years >= 3) {
            int third = years / 3;
            double dryYearsFit = mean(fitByYear, 0, third);
            double normalYearsFit = mean(fitByYear, third, years - third);
            double wetYearsFit = mean(fitByYear, years - third, years);
            value = outlook.below() * dryYearsFit + outlook.near() * normalYearsFit + outlook.above() * wetYearsFit;
        }
        int direction = drierYears == wetterYears ? 0 : (drierYears > wetterYears ? -1 : 1);
        return new Fit(clamp(value, 0, 1), direction, true, years, yearsTolerated);
    }

    /**
     * Finds the share of past years with enough frost-free days for the crop's shortest cycle. Perennials and
     * crops that overwinter are not checked, because frost does not end their season.
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return the season-length fit, or {@link Fit#UNKNOWN} when it does not apply or cannot be checked
     */
    Fit seasonLengthFit(Field field, Crop crop) {
        CropReference.Requirements needs = crop.requirements();
        List<Double> yearlyFrostFreeDays = field.conditions().yearlyFrostFreeDays();
        FieldConditions.CropSeason usualSeason = field.conditions().cropSeasons().get(crop.id());
        if (needs == null || needs.cycleMinDays() == null || yearlyFrostFreeDays.isEmpty() || needs.perennial()
                || (usualSeason != null && usualSeason.crossesNewYear())) {
            return Fit.UNKNOWN;
        }
        int longEnough = 0;
        for (double frostFreeDays : yearlyFrostFreeDays) {
            if (frostFreeDays >= needs.cycleMinDays()) {
                longEnough++;
            }
        }
        return new Fit((double) longEnough / yearlyFrostFreeDays.size(), -1, true, yearlyFrostFreeDays.size(), longEnough);
    }

    /**
     * Compares the field's topsoil texture with the textures the crop does best in and tolerates.
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return the texture fit, or {@link Fit#UNKNOWN} without a texture or crop requirements
     */
    Fit textureFit(Field field, Crop crop) {
        CropReference.Requirements needs = crop.requirements();
        String textureGroup = textureGroup(field.texture());
        if (needs == null || textureGroup == null) {
            return Fit.UNKNOWN;
        }
        return classFit(textureGroup, needs.textureOptimal(), needs.textureTolerated());
    }

    /**
     * Compares the area's dominant soil drainage with the drainage the crop does best in and tolerates.
     * Imperfectly drained soil sits between well and poorly drained, so it takes the average of the two.
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return the drainage fit, or {@link Fit#UNKNOWN} without a drainage class or crop requirements
     */
    Fit drainageFit(Field field, Crop crop) {
        CropReference.Requirements needs = crop.requirements();
        String drainageCode = field.conditions().drainageCode();
        if (needs == null || drainageCode == null) {
            return Fit.UNKNOWN;
        }
        return switch (drainageCode.toUpperCase(Locale.ROOT)) {
            case "R" -> classFit("excessive", needs.drainageOptimal(), needs.drainageTolerated());
            case "W" -> classFit("well", needs.drainageOptimal(), needs.drainageTolerated());
            case "P" -> classFit("poorly", needs.drainageOptimal(), needs.drainageTolerated());
            case "I" -> {
                Fit well = classFit("well", needs.drainageOptimal(), needs.drainageTolerated());
                Fit poorly = classFit("poorly", needs.drainageOptimal(), needs.drainageTolerated());
                yield well.known() && poorly.known() ? Fit.of((well.value() + poorly.value()) / 2, 0) : Fit.UNKNOWN;
            }
            default -> Fit.UNKNOWN;
        };
    }

    /**
     * Applies the Canada Land Inventory capability class. Classes 1 to 3 suit sustained annual cropping. Class 4
     * is marginal for it, classes 5 and 6 suit perennial forage only, and class 7 has no capability for crops.
     * The map is regional (1:250,000) and a field can differ from the land around it, so a poor class lowers
     * reliability in steps instead of ruling a crop out.
     *
     * @param field the field
     * @param crop  the crop being considered
     * @return the land class fit, or {@link Fit#UNKNOWN} without a land class
     */
    Fit landClassFit(Field field, Crop crop) {
        String landClass = field.conditions().landClass();
        if (landClass == null) {
            return Fit.UNKNOWN;
        }
        boolean perennial = crop.requirements() != null && crop.requirements().perennial();
        return switch (landClass.strip()) {
            case "1", "2", "3" -> Fit.of(1, 0);
            case "4" -> Fit.of(perennial ? 1 : 0.9, 0);
            case "5" -> Fit.of(perennial ? 1 : 0.7, 0);
            case "6" -> Fit.of(perennial ? 1 : 0.5, 0);
            case "7" -> Fit.of(0.3, 0);
            default -> Fit.UNKNOWN;
        };
    }

    /**
     * Grades a value against a crop's range: 1 inside the optimal range, falling in a straight line to 0 at the
     * edge of the range the crop tolerates.
     *
     * @param value      the field's value
     * @param minimum    lowest value the crop tolerates
     * @param optimalMin low end of the optimal range
     * @param optimalMax high end of the optimal range
     * @param maximum    highest value the crop tolerates
     * @return the fit and whether the value is below or above what the crop prefers
     */
    static Fit rangeFit(double value, double minimum, double optimalMin, double optimalMax, double maximum) {
        if (value >= optimalMin && value <= optimalMax) {
            return Fit.of(1, 0);
        }
        if (value < optimalMin) {
            return Fit.of(value <= minimum ? 0 : (value - minimum) / (optimalMin - minimum), -1);
        }
        return Fit.of(value >= maximum ? 0 : (maximum - value) / (maximum - optimalMax), 1);
    }

    /**
     * Grades a soil class against the classes a crop does best in and tolerates.
     *
     * @param soilClass the field's class, for example {@code medium} or {@code well}
     * @param optimal   classes the crop does best in
     * @param tolerated every class the crop tolerates
     * @return 1 for an optimal class, 0.8 for a tolerated one, 0.5 otherwise; unknown when the crop has no list
     */
    static Fit classFit(String soilClass, List<String> optimal, List<String> tolerated) {
        if (optimal == null || optimal.isEmpty() || tolerated == null || tolerated.isEmpty()) {
            return Fit.UNKNOWN;
        }
        if (optimal.contains(soilClass)) {
            return Fit.of(1, 0);
        }
        /*
            A soil class outside the crop's lists lowers reliability but only to a half. The texture class is an
            estimate and the drainage class comes from a regional map, and both can be managed on the farm (tile
            drains, for one), so on their own they should not make a crop look unworkable.
         */
        return Fit.of(tolerated.contains(soilClass) ? 0.8 : 0.5, 0);
    }

    /**
     * Groups a USDA soil texture class into the heavy, medium and light groups crop requirements use.
     *
     * @param texture USDA texture class, for example {@code Sandy loam}; may be {@code null}
     * @return {@code heavy}, {@code medium} or {@code light}, or {@code null} when the texture is not known
     */
    static String textureGroup(String texture) {
        if (texture == null) {
            return null;
        }
        return switch (texture.strip().toLowerCase(Locale.ROOT)) {
            case "sand", "loamy sand", "sandy loam" -> "light";
            case "clay", "silty clay", "sandy clay" -> "heavy";
            case "loam", "silt loam", "silt", "sandy clay loam", "clay loam", "silty clay loam" -> "medium";
            default -> null;
        };
    }

    /**
     * Finds the months a crop is growing in.
     *
     * @param conditions the field's conditions
     * @param crop       the crop being considered
     * @return months 1 to 12: the crop calendar's season when it lies within one calendar year, otherwise the
     *         field's main growing season, otherwise every month
     */
    static List<Integer> growingMonths(FieldConditions conditions, Crop crop) {
        FieldConditions.CropSeason usualSeason = conditions.cropSeasons().get(crop.id());
        if (usualSeason != null && !usualSeason.crossesNewYear() && usualSeason.maturityDay() >= usualSeason.plantingDay()) {
            int firstMonth = monthOfDay(usualSeason.plantingDay());
            int lastMonth = monthOfDay(usualSeason.maturityDay());
            List<Integer> months = new ArrayList<>();
            for (int month = firstMonth; month <= lastMonth; month++) {
                months.add(month);
            }
            return months;
        }
        if (!conditions.seasonMonths().isEmpty()) {
            return conditions.seasonMonths();
        }
        return List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    /**
     * Converts a day of the year to its month, in a year that is not a leap year.
     *
     * @param dayOfYear day of the year, 1 to 365
     * @return the month, 1 to 12
     */
    static int monthOfDay(int dayOfYear) {
        return LocalDate.ofYearDay(2023, (int) clamp(dayOfYear, 1, 365)).getMonthValue();
    }

    /**
     * Averages part of an array.
     *
     * @param values the values
     * @param from   first index, inclusive
     * @param to     last index, exclusive
     * @return the mean, or 0 for an empty part
     */
    static double mean(double[] values, int from, int to) {
        if (to <= from) {
            return 0;
        }
        double total = 0;
        for (int index = from; index < to; index++) {
            total += values[index];
        }
        return total / (to - from);
    }

    /**
     * Clamps the provided value between specified minimum and maximum.
     *
     * @param value   value to clamp
     * @param minimum lowest value returned
     * @param maximum highest value returned
     * @return the value, limited to the range
     */
    static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
