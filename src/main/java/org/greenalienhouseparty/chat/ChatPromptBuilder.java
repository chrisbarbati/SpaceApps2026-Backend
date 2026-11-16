package org.greenalienhouseparty.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.greenalienhouseparty.dto.ChatRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Writes the assistant's instructions for one message.
 *
 * <p>The fixed instructions live in {@code chat/system-prompt.md}. Each message adds today's date and a
 * plain-text description of what the farmer is looking at in the app (selected field, other fields, pin,
 * province and growable crops), so the model can refer to them. Field outlines are not included: tools read
 * them from the tool context when needed. Text supplied by the app, such as field names, is shortened and
 * kept to one line so it cannot reshape the instructions.</p>
 */
@Component
public class ChatPromptBuilder {

    static final String SYSTEM_PROMPT = "chat/system-prompt.md";

    /** Longest app-supplied text written into the instructions. */
    private static final int MAX_TEXT = 80;

    /** Most fields listed. */
    private static final int MAX_FIELDS = 20;

    private final String instructions;

    private final Clock clock;

    /**
     * Creates the builder using the system clock.
     */
    @Autowired
    public ChatPromptBuilder() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Creates the builder with an explicit clock for repeatable testing.
     *
     * @param clock source of today's date
     */
    ChatPromptBuilder(Clock clock) {
        this.clock = clock;
        this.instructions = read(SYSTEM_PROMPT);
    }

    /**
     * Writes the instructions for one message.
     *
     * @param context what the farmer is looking at, or {@code null}
     * @return the system message text
     */
    public String systemPrompt(ChatRequest.Context context) {
        StringBuilder prompt = new StringBuilder(instructions.strip());
        prompt.append("\n\n## Today\n").append(LocalDate.now(clock)).append('\n');
        prompt.append("\n## What the farmer is looking at in the app\n");
        List<String> lines = describe(context);
        if (lines.isEmpty()) {
            prompt.append("- Nothing is selected.\n");
        } else {
            lines.forEach(line -> prompt.append("- ").append(line).append('\n'));
        }
        return prompt.toString();
    }

    /**
     * Describes the on-screen context as short lines.
     *
     * @param context the context, or {@code null}
     * @return one line per fact
     */
    static List<String> describe(ChatRequest.Context context) {
        List<String> lines = new ArrayList<>();
        if (context == null) {
            return lines;
        }
        String selected = clean(context.selectedFieldId());
        List<ChatRequest.FieldContext> fields = context.fields() == null ? List.of() : context.fields();
        for (ChatRequest.FieldContext field : fields.stream().limit(MAX_FIELDS).toList()) {
            if (field == null || clean(field.fieldId()) == null) {
                continue;
            }
            String id = clean(field.fieldId());
            StringBuilder line = new StringBuilder(id.equals(selected) ? "Selected field: " : "Other field: ");
            line.append(id);
            if (clean(field.name()) != null) {
                line.append(" (named \"").append(clean(field.name())).append("\")");
            }
            line.append(clean(field.currentCrop()) != null
                    ? ", current crop: " + clean(field.currentCrop())
                    : ", current crop not set");
            lines.add(line.toString());
        }
        if (selected != null && fields.stream().noneMatch(field -> field != null && selected.equals(clean(field.fieldId())))) {
            lines.addFirst("Selected field: " + selected);
        }
        if (fields.size() > MAX_FIELDS) {
            lines.add((fields.size() - MAX_FIELDS) + " more fields not listed");
        }
        ChatRequest.Location location = context.location();
        if (location != null && location.lat() != null && location.lon() != null) {
            lines.add(String.format(Locale.ROOT, "Pin or searched location: lat %.5f, lon %.5f", location.lat(), location.lon()));
        }
        if (clean(context.provinceCode()) != null) {
            lines.add("Province: " + clean(context.provinceCode()));
        }
        if (context.growableCrops() != null && !context.growableCrops().isEmpty()) {
            lines.add("Crops the farmer can grow: " + String.join(", ",
                    context.growableCrops().stream().map(ChatPromptBuilder::clean).filter(crop -> crop != null).toList()));
        }
        return lines;
    }

    /**
     * Keeps app-supplied text to one short line.
     *
     * @param text the text, or {@code null}
     * @return the cleaned text, or {@code null} when blank
     */
    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("[\\r\\n\\t]+", " ").replace("#", "").strip();
        if (line.isEmpty()) {
            return null;
        }
        return line.length() > MAX_TEXT ? line.substring(0, MAX_TEXT) + "..." : line;
    }

    /**
     * Reads a text resource from the class path.
     *
     * @param path resource path
     * @return the text
     */
    private static String read(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }
}
