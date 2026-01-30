package com.nilsson.imagetoolbox.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Metadata parsing strategy for InvokeAI-generated JSON.
 *
 * <p>This strategy reads InvokeAI metadata directly from JSON text,
 * extracting model information, prompts, and generation parameters
 * into a normalized key-value structure.</p>
 *
 * <p>The parser is resilient to partial or variant schemas and avoids
 * overwriting already-resolved fields when higher-fidelity data exists.</p>
 */
public class InvokeAIStrategy implements MetadataStrategy {

    private static final ObjectMapper mapper = new ObjectMapper();

    /* ============================================================
       Public API
       ============================================================ */

    public Map<String, String> parse(String text) {
        Map<String, String> results = new HashMap<>();
        try {
            JsonNode root = mapper.readTree(text);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                extract(field.getKey(), field.getValue(), root, results);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    /* ============================================================
       MetadataStrategy Implementation
       ============================================================ */

    @Override
    public void extract(String key, JsonNode value, JsonNode parentNode, Map<String, String> results) {
        if (!value.isTextual()) return;
        String text = value.asText();

        if (key.equals("model_name") || key.equals("model_weights")) {
            results.put("Model", text);
        }

        else if (key.equals("positive_prompt") || (key.equals("prompt") && !results.containsKey("Prompt"))) {
            results.put("Prompt", text);
        }
        else if (key.equals("negative_prompt")) {
            results.put("Negative", text);
        }

        else if (key.equals("cfg_scale") || key.equals("cfg_rescale_multiplier")) {
            results.put("CFG", text);
        }

        else if ((key.equals("sampler_name") || key.equals("scheduler")) && !results.containsKey("Sampler")) {
            results.put("Sampler", text);
        }

        else if (key.equals("variant") && !results.containsKey("Model")) {
            results.put("Model", text);
        }
    }
}
