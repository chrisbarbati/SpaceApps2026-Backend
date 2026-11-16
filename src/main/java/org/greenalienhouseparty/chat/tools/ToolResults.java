package org.greenalienhouseparty.chat.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shrinks data backend responses before they are handed to the model.
 *
 * <p>The application's responses are made for charts and carry long daily, weekly and per-scene series. The
 * model needs the summaries, not every point, and every extra token costs time and money and crowds out the
 * conversation. Tools therefore convert a response to JSON, drop the named series and keep only the most
 * recent items of any other long list, noting how many were left out so the model knows the data is
 * partial.</p>
 */
@Component
public class ToolResults {

    /** Default number of items kept from a long list. */
    public static final int DEFAULT_MAX_ITEMS = 12;

    private final ObjectMapper objectMapper;

    /**
     * Creates the helper.
     *
     * @param objectMapper the application's JSON mapper, so dates and nulls are written as in API responses
     */
    public ToolResults(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts a value to JSON, removes the named properties and shortens long lists.
     *
     * <p>A drop path is a dotted list of property names, such as {@code climate.yearly.years}. When a step
     * reaches a list, the rest of the path is applied to every item. Lists longer than {@code maxItems} keep
     * their last {@code maxItems} items (data series are oldest first), and a sibling property
     * {@code <name>Omitted} gives the number removed.</p>
     *
     * @param value     the response to shrink; may be {@code null}
     * @param maxItems  most items kept from any list
     * @param dropPaths dotted paths of properties to remove
     * @return the shrunk JSON
     */
    public JsonNode compact(Object value, int maxItems, String... dropPaths) {
        JsonNode node = objectMapper.valueToTree(value);
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        for (String path : dropPaths) {
            drop(node, path.split("\\."), 0);
        }
        truncate(node, maxItems);
        return node;
    }

    /**
     * Converts a value to JSON without changing it.
     *
     * @param value the value
     * @return the JSON
     */
    public JsonNode json(Object value) {
        return objectMapper.valueToTree(value);
    }

    /**
     * Returns a new empty JSON object.
     *
     * @return an empty object node
     */
    public ObjectNode object() {
        return objectMapper.createObjectNode();
    }

    /**
     * Removes one dotted path from a JSON tree.
     *
     * @param node  current node
     * @param steps the path's property names
     * @param index position of the current step
     */
    private static void drop(JsonNode node, String[] steps, int index) {
        if (node == null || index >= steps.length) {
            return;
        }
        if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                drop(item, steps, index);
            }
            return;
        }
        if (node instanceof ObjectNode object) {
            if (index == steps.length - 1) {
                object.remove(steps[index]);
            } else {
                drop(object.get(steps[index]), steps, index + 1);
            }
        }
    }

    /**
     * Shortens every list in a JSON tree to its last {@code maxItems} items.
     *
     * @param node     current node
     * @param maxItems most items kept
     */
    private static void truncate(JsonNode node, int maxItems) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                names.add(entry.getKey());
            }
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child instanceof ArrayNode array && array.size() > maxItems) {
                    int omitted = array.size() - maxItems;
                    List<JsonNode> kept = new ArrayList<>();
                    for (int index = omitted; index < array.size(); index++) {
                        kept.add(array.get(index));
                    }
                    array.removeAll();
                    kept.forEach(array::add);
                    object.put(name + "Omitted", omitted);
                }
                truncate(child, maxItems);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) {
                truncate(item, maxItems);
            }
        }
    }
}
