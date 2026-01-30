package com.nilsson.imagetoolbox.service.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nilsson.imagetoolbox.service.strategy.*;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Parses text-based image generation metadata into structured key-value data.
 *
 * <p>Supports multiple metadata formats including ComfyUI (UI and API JSON),
 * Automatic1111-style parameter blocks, InvokeAI, NovelAI, and SwarmUI.
 * Parsing is delegated to format-specific strategies when applicable.</p>
 */
public class TextParamsParser {

    // ==================================================================================
    // CONFIGURATION
    // ==================================================================================

    private static final ObjectMapper mapper = new ObjectMapper();

    // ==================================================================================
    // PUBLIC API
    // ==================================================================================

    public static Map<String, String> parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new HashMap<>();
        }

        if (text.trim().startsWith("{")) {
            try {
                JsonNode root = mapper.readTree(text);
                Map<String, String> results = new HashMap<>();
                ComfyUIStrategy strategy = new ComfyUIStrategy();

                if (root.has("nodes")) {
                    for (JsonNode node : root.get("nodes")) {
                        processComfyNode(node, strategy, results);
                    }
                    strategy.extract("nodes_wrapper", root, null, results);
                }
                else {
                    boolean isApi = false;
                    Iterator<JsonNode> it = root.elements();
                    while (it.hasNext()) {
                        if (it.next().has("class_type")) {
                            isApi = true;
                            break;
                        }
                    }

                    if (isApi) {
                        strategy.extract("api_nodes", root, null, results);
                    }
                    else {
                        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> entry = fields.next();
                            JsonNode node = entry.getValue();
                            if (node.has("inputs") && node.has("class_type")) {
                                processComfyNode(node, strategy, results);
                            }
                        }
                    }
                }

                if (!results.isEmpty()) return results;

            } catch (Exception ignored) {}
        }

        if (text.contains("Steps: ") && text.contains("Sampler: ")) {
            return new CommonStrategy().parse(text);
        }

        if (text.contains("\"app_version\":") && text.contains("invokeai")) {
            return new InvokeAIStrategy().parse(text);
        }

        if (text.contains("NovelAI")) {
            return new NovelAIStrategy().parse(text);
        }

        if (text.contains("sui_image_params")) {
            return new SwarmUIStrategy().parse(text);
        }

        return new HashMap<>();
    }

    // ==================================================================================
    // INTERNAL HELPERS
    // ==================================================================================

    private static void processComfyNode(
            JsonNode node,
            ComfyUIStrategy strategy,
            Map<String, String> results
    ) {
        Iterator<Map.Entry<String, JsonNode>> nodeFields = node.fields();
        while (nodeFields.hasNext()) {
            Map.Entry<String, JsonNode> field = nodeFields.next();
            strategy.extract(field.getKey(), field.getValue(), node, results);
        }
    }
}
