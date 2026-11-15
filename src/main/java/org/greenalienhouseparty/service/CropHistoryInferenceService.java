package org.greenalienhouseparty.service;

import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse.CalendarYear;
import org.greenalienhouseparty.dto.CropHistoryResponse.CropYear;
import org.greenalienhouseparty.dto.CropHistoryResponse.CurrentCrop;
import org.greenalienhouseparty.dto.CropHistoryResponse.ExpectedCrop;
import org.greenalienhouseparty.dto.CropHistoryResponse.Flags;
import org.greenalienhouseparty.dto.CropHistoryResponse.LastSeason;
import org.greenalienhouseparty.dto.CropHistoryResponse.Summary;
import org.springframework.stereotype.Service;

/**
 * Normalizes acquired crop classes and interprets their calendar-aware rotation.
 * Python supplies observations; upstream summaries and group assignments are never
 * used as application decisions. No satellite observations are invented here.
 */
@Service
public class CropHistoryInferenceService {
    private static final int RECENT_YEARS = 9;
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("corn", "Corn"), Map.entry("soybeans", "Soybeans"), Map.entry("wheat", "Wheat"),
            Map.entry("other_grains", "Other grains"), Map.entry("canola", "Canola"), Map.entry("pulses", "Beans"),
            Map.entry("hay", "Hay"), Map.entry("pasture", "Pasture"), Map.entry("fallow", "Fallow"),
            Map.entry("vegetables", "Vegetables"), Map.entry("fruit", "Fruit"), Map.entry("other_crops", "Other crops"),
            Map.entry("natural", "Woodland or wetland"), Map.entry("water", "Water"),
            Map.entry("developed", "Developed land"), Map.entry("barren", "Bare land"));
    private static final List<Rule> RULES = List.of(
            rule("dbl crop winwht|winter wheat|spring wheat|durum|^wheat", "wheat"),
            rule("corn", "corn"), rule("soybean", "soybeans"),
            rule("canola|rapeseed|mustard|flax|sunflower|safflower|camelina|borage|oilseed", "canola"),
            rule("wetland|peatland|forest|wood|shrub|coniferous|broadleaf|mixedwood|burnt", "natural"),
            rule("pasture|grassland", "pasture"),
            rule("\\bpeas?\\b|bean|lentil|chick ?pea|pulse|faba|vetch", "pulses"),
            rule("alfalfa|hay|clover|forage|sod|grass seed|switchgrass", "hay"), rule("grass", "pasture"),
            rule("fallow|idle|too wet", "fallow"),
            rule("barley|oats|rye|triticale|spelt|millet|sorghum|buckwheat|canaryseed|quinoa|cereal|grain|rice", "other_grains"),
            rule("potato|tomato|vegetable|sugarbeet|pepper|onion|cucumber|squash|pumpkin|lettuce|carrot|melon|cantaloupe|cabbage|broccoli|tobacco|ginseng|hops|herb|hemp|mint|garlic|asparagus|greenhouse|nursery|cotton|peanut", "vegetables"),
            rule("apple|cherr|peach|pear|grape|vineyard|orchard|berr|citrus|orange|almond|walnut|pecan|pistachio|olive|plum|apricot|fruit|nut", "fruit"),
            rule("water|aquaculture", "water"), rule("developed|urban", "developed"),
            rule("barren|exposed|ice|snow", "barren"));

    private final Clock clock;

    /** Creates the production interpreter using the UTC calendar. */
    public CropHistoryInferenceService() {
        this(Clock.systemUTC());
    }

    /** Creates an interpreter with a repeatable calendar for verification. */
    CropHistoryInferenceService(Clock clock) {
        this.clock = clock;
    }

    /** One provider-name matching rule; order resolves overlapping names. */
    private record Rule(Pattern expression, String group) { }

    /** Pattern support within a contiguous sequence, including a last-year break. */
    private record Cycle(int period, double fit, boolean holds, List<String> crops, int observations, boolean emerging) { }

    /** Compiles a case-insensitive crop-class normalization rule. */
    private static Rule rule(String expression, String group) {
        return new Rule(Pattern.compile(expression, Pattern.CASE_INSENSITIVE), group);
    }

    /** Maps provider classes to application groups without trusting upstream guesses. */
    private static String group(CropYear row) {
        for (Rule rule : RULES) {
            if (rule.expression().matcher(Objects.toString(row.crop(), "")).find()) return rule.group();
        }
        return Boolean.TRUE.equals(row.isCrop()) ? "other_crops" : "natural";
    }

    /** Returns the farmer-facing name for an application crop group. */
    private static String label(String group) {
        return LABELS.getOrDefault(group, group);
    }

    /** Whether a normalized group describes agricultural use rather than non-crop land. */
    private static boolean agricultural(String group) {
        return !List.of("natural", "water", "developed", "barren").contains(group);
    }

    /** Rebuilds all application interpretation from observations, including during mixed-version deployment. */
    public CropHistoryResponse interpret(CropHistoryResponse raw) {
        int year = Year.now(clock).getValue();
        List<CropYear> rows = (raw.years() == null ? List.<CropYear>of() : raw.years()).stream()
                .filter(row -> row != null && row.year() != null && row.year() >= 1900 && row.year() <= year)
                .sorted(Comparator.comparing(CropYear::year))
                .map(row -> new CropYear(row.year(), row.crop(), row.code(), row.isCrop(), group(row), label(group(row)),
                        row.share(), row.cropShare(), row.classes())).toList();
        Summary summary = summarize(rows, year);
        return new CropHistoryResponse(raw.id(), raw.source(), rows, raw.rotation(), raw.distinctCrops(),
                raw.cropYears(), raw.unavailableYears(), summary, raw.note());
    }

    /** Constructs display evidence, data quality flags and an explicit inference outcome. */
    private Summary summarize(List<CropYear> rows, int year) {
        CropYear latest = rows.isEmpty() ? null : rows.getLast();
        Map<Integer, CropYear> byYear = new TreeMap<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        for (CropYear row : rows) {
            byYear.put(row.year(), row);
            counts.merge(row.year(), 1, Integer::sum);
        }
        List<CropYear> sequence = new ArrayList<>();
        if (latest != null) {
            for (int cursor = latest.year(); cursor > latest.year() - RECENT_YEARS; cursor--) {
                CropYear row = byYear.get(cursor);
                if (row == null || counts.get(cursor) != 1 || !validShare(row.share()) || row.share() < 0.5) break;
                sequence.addFirst(row);
            }
        }
        boolean merged = rows.stream().filter(row -> validShare(row.share()) && row.share() < 0.6).count()
                >= Math.max(3, rows.size() / 2.0);
        List<Double> recentCropShares = rows.stream().skip(Math.max(0, rows.size() - 5))
                .map(CropYear::cropShare).filter(CropHistoryInferenceService::validShare).toList();
        boolean mostlyNotCrop = !recentCropShares.isEmpty()
                && recentCropShares.stream().mapToDouble(Double::doubleValue).average().orElse(1) < 0.5;
        List<String> groups = sequence.stream().map(CropYear::group).toList();
        Cycle cycle = detect(groups);
        org.greenalienhouseparty.dto.CropHistoryResponse.Pattern pattern = describe(cycle, groups);
        ExpectedCrop expected = null;
        CurrentCrop current = unavailable(year, latest == null
                ? "No crop-map observations are available for this field."
                : "There is not enough recent, consecutive crop-map evidence to estimate this season's crop.");
        List<String> notes = new ArrayList<>();
        if (latest != null) notes.add("The newest crop map is " + latest.year() + ". A mapped crop is a satellite classification, not a farmer-confirmed record.");
        if (merged) notes.add("Many years show mixed crops; this outline may cover more than one field.");
        if (mostlyNotCrop) notes.add("Recent maps classify much of this outline as non-crop land.");
        if (latest != null && latest.year() == year && !sequence.isEmpty() && agricultural(latest.group())
                && !merged && !mostlyNotCrop) {
            current = new CurrentCrop(year, "mapped", latest.group(), label(latest.group()), null,
                    "The " + year + " crop map classifies this field as " + label(latest.group()).toLowerCase() + ".");
        } else if (cycle != null && latest != null && latest.year() < year && year - latest.year() <= 2
                && cycle.holds() && !merged && !mostlyNotCrop) {
            int gap = year - latest.year();
            String guess = cycle.crops().get((gap - 1) % cycle.period());
            if (agricultural(guess)) {
                String confidence = cycle.fit() == 1 && cycle.observations() >= 2 * cycle.period() + 2
                        && !cycle.emerging() && gap == 1 ? "high" : "medium";
                String reason = pattern.text() + " " + label(latest.group()) + " was mapped in " + latest.year()
                        + "; the pattern points to " + label(guess).toLowerCase() + " in " + year + ".";
                expected = new ExpectedCrop(year, guess, label(guess), confidence, reason);
                current = new CurrentCrop(year, "estimated", guess, label(guess), confidence, reason);
                notes.add("Confidence describes support for a rotation pattern, not a calibrated probability or a current-season observation.");
            }
        }
        if (current.status().equals("unavailable") && latest != null) {
            String reason = merged ? "Mixed crop-map pixels make the field's current crop uncertain."
                    : mostlyNotCrop || !agricultural(latest.group()) ? "The recent crop maps do not establish an agricultural crop for this field."
                    : year - latest.year() > 2 ? "The newest crop map is too old to project this season's crop."
                    : cycle != null && !cycle.holds() ? "The most recent season breaks the earlier rotation; no crop is assumed."
                    : current.reason();
            current = unavailable(year, reason);
            notes.add(reason);
        }
        List<CalendarYear> timeline = latest == null ? List.of() : slots(byYear, rows.getFirst().year(), latest.year());
        List<CalendarYear> recent = latest == null ? List.of() : slots(byYear, latest.year() - 4, latest.year());
        return new Summary(rows.size(), rows.isEmpty() ? null : rows.getFirst().year(),
                latest == null ? null : new LastSeason(latest.year(), latest.crop(), latest.group(), latest.groupLabel()),
                pattern, expected, new Flags(merged, mostlyNotCrop), List.copyOf(notes), year, latest, current, recent, timeline);
    }

    /** Requires a finite fraction; missing or malformed shares never support inference. */
    private static boolean validShare(Double share) {
        return share != null && Double.isFinite(share) && share >= 0 && share <= 1;
    }

    /** Builds an unavailable outcome with no fallback crop. */
    private static CurrentCrop unavailable(int year, String reason) {
        return new CurrentCrop(year, "unavailable", null, null, null, reason);
    }

    /** Builds true calendar slots, preserving both missing and observed years. */
    private static List<CalendarYear> slots(Map<Integer, CropYear> rows, int start, int end) {
        List<CalendarYear> result = new ArrayList<>();
        for (int year = start; year <= end; year++) result.add(new CalendarYear(year, rows.get(year)));
        return List.copyOf(result);
    }

    /** Finds established patterns first, then an exact recent three-year candidate. */
    private static Cycle detect(List<String> groups) {
        if (groups.size() < 3) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        groups.forEach(group -> counts.merge(group, 1, Integer::sum));
        String mode = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        double modeShare = counts.get(mode) / (double) groups.size();
        if (modeShare >= 0.75) return new Cycle(1, modeShare, groups.getLast().equals(mode), List.of(mode), groups.size(), false);
        for (int period : List.of(2, 3)) {
            Cycle cycle = repeating(groups, period, false);
            if (cycle != null) return cycle;
        }
        for (int length = groups.size(); length >= 5; length--) {
            List<String> recent = groups.subList(groups.size() - length, groups.size());
            Cycle candidate = repeating(recent, 3, true);
            if (candidate != null) return candidate;
        }
        return null;
    }

    /** Scores repeats only across consecutive calendar observations; short candidates require exact agreement. */
    private static Cycle repeating(List<String> groups, int period, boolean emerging) {
        if (groups.size() < (emerging ? period + 2 : 2 * period + 1)) return null;
        int matches = 0;
        for (int index = period; index < groups.size(); index++) {
            if (groups.get(index).equals(groups.get(index - period))) matches++;
        }
        double fit = matches / (double) (groups.size() - period);
        boolean holds = groups.getLast().equals(groups.get(groups.size() - period - 1));
        List<String> cycle = groups.subList(groups.size() - period, groups.size());
        if (fit < (emerging ? 1 : 0.75) || cycle.stream().distinct().count() < 2) return null;
        return new Cycle(period, fit, holds, List.copyOf(cycle), groups.size(), emerging);
    }

    /** Turns a detected pattern into display text without inventing a crop when detection failed. */
    private static org.greenalienhouseparty.dto.CropHistoryResponse.Pattern describe(Cycle cycle, List<String> groups) {
        if (groups.isEmpty()) return null;
        if (cycle == null) return new org.greenalienhouseparty.dto.CropHistoryResponse.Pattern(
                "mixed", null, groups.stream().distinct().toList(), "No consistent rotation is established by the recent crop maps.");
        String kind = !cycle.holds() ? "changed" : cycle.period() == 1 ? "stable" : cycle.period() == 2 ? "alternating" : "rotation";
        String text;
        if (!cycle.holds()) text = "The most recent mapped crop breaks the earlier rotation.";
        else if (cycle.period() == 1) text = "Mostly " + label(cycle.crops().getFirst()).toLowerCase() + " in recent years.";
        else if (cycle.period() == 2) text = label(cycle.crops().getFirst()) + " and " + label(cycle.crops().getLast()).toLowerCase() + " take turns, one year each.";
        else text = (cycle.emerging() ? "A possible recent 3-year rotation: " : "A 3-year rotation: ")
                + String.join(", ", cycle.crops().subList(0, 2).stream().map(group -> label(group).toLowerCase()).toList())
                + ", then " + label(cycle.crops().getLast()).toLowerCase() + ".";
        return new org.greenalienhouseparty.dto.CropHistoryResponse.Pattern(kind, cycle.period(), cycle.crops().stream().distinct().toList(), text);
    }
}
