package org.greenalienhouseparty.planning;

import java.util.List;

import org.greenalienhouseparty.planning.model.Field;

/**
 * A field ready for the rotation planner, with a record of what had to be
 * assumed to build it.
 *
 * @param field       planner input built from data backend responses
 * @param assumptions plain-language notes, one per value that was missing and replaced by a default;
 *                    empty when every value came from data
 */
public record NormalizedField(Field field, List<String> assumptions) {
}
