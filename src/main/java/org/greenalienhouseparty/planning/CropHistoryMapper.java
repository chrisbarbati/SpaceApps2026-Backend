package org.greenalienhouseparty.planning;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Translates a crop-map class from the data backend's crop history into a crop
 * id from the catalog.
 *
 * <p>Crop maps name classes differently in each country ("Corn for grain" in
 * Canada, "Corn" in the United States), so the provider's class name is matched
 * by keyword first because it is the most specific. When the name says nothing
 * useful, the backend's cross-country {@code group} is used. Groups are coarser
 * than the catalog: {@code hay} is read as alfalfa, which is an approximation,
 * and {@code other_grains}, {@code vegetables} and {@code fruit} are left
 * unmapped rather than guessed.</p>
 */
public final class CropHistoryMapper {

    /** Keyword in the class name and the crop id it means; first match wins. */
    private static final List<Map.Entry<String, String>> NAME_RULES = List.of(
            Map.entry("wheat", "wheat"),      // before soybeans: "Dbl Crop WinWht/Soybeans" is a wheat year
            Map.entry("winwht", "wheat"),
            Map.entry("sweet potato", ""),    // not a potato for rotation purposes
            Map.entry("corn", "corn"),
            Map.entry("soybean", "soybeans"),
            Map.entry("barley", "barley"),
            Map.entry("oats", "oats"),
            Map.entry("canola", "canola"),
            Map.entry("rapeseed", "canola"),
            Map.entry("potato", "potatoes"),
            Map.entry("alfalfa", "alfalfa"),
            Map.entry("grape", "grapes"),
            Map.entry("vineyard", "grapes"));

    /** Backend crop group and the crop id it is read as when the class name does not match. */
    private static final Map<String, String> GROUP_RULES = Map.of(
            "corn", "corn",
            "soybeans", "soybeans",
            "wheat", "wheat",
            "canola", "canola",
            "hay", "alfalfa");

    private CropHistoryMapper() {
    }

    /**
     * Finds the catalog crop id for one crop-map class.
     *
     * @param className the provider's class name, for example {@code Corn for grain}; may be {@code null}
     * @param group     the backend's crop group, for example {@code corn}; may be {@code null}
     * @return the crop id, or empty when the class is not a crop the catalog knows
     */
    public static Optional<String> cropId(String className, String group) {
        String name = className == null ? "" : className.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> rule : NAME_RULES) {
            if (name.contains(rule.getKey())) {
                return rule.getValue().isEmpty() ? Optional.empty() : Optional.of(rule.getValue());
            }
        }
        return Optional.ofNullable(group == null ? null : GROUP_RULES.get(group));
    }
}
