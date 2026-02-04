package com.nilsson.imagetoolbox.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 Unit tests for {@link MetadataService} focusing on the extraction and parsing
 of AI generation metadata from various raw string formats.

 <p>This test suite validates:
 <ul>
 <li><b>ComfyUI Workflow Parsing:</b> Ensures complex JSON graphs (Node/Link based) are correctly interpreted.</li>
 <li><b>Parameter Extraction:</b> Verifies that key generation parameters (Seed, Steps, CFG) are extracted from nested nodes (e.g., KSampler).</li>
 <li><b>Software Identification:</b> Checks that the service correctly identifies the software source based on JSON structure.</li>
 </ul>
 </p>
*/
class MetadataServiceTest {

    private final MetadataService service = new MetadataService();

    // ------------------------------------------------------------------------
    // ComfyUI Logic Tests
    // ------------------------------------------------------------------------

    /**
     Verifies that a ComfyUI JSON structure (commonly found in API calls or wrapped metadata)
     is parsed correctly.
     <p>
     The test ensures that:
     <ul>
     <li>The software is correctly identified as "ComfyUI".</li>
     <li>The positive prompt is extracted from the "text" input of the CLIPTextEncode node.</li>
     <li>Numeric parameters (Seed, Steps, CFG) are extracted from the KSampler inputs.</li>
     <li>Sampler and Scheduler names are correctly mapped.</li>
     </ul>
     </p>
     */
    @Test
    void testParseComfyUIJson() {
        String json = """
                    {
                        "prompt": {
                            "3": {
                                "inputs": {
                                    "seed": 847593291,
                                    "steps": 25,
                                    "cfg": 7.0,
                                    "sampler_name": "euler",
                                    "scheduler": "normal",
                                    "denoise": 1.0,
                                    "model": ["4", 0],
                                    "positive": ["6", 0],
                                    "negative": ["7", 0],
                                    "latent_image": ["5", 0]
                                },
                                "class_type": "KSampler"
                            },
                            "6": {
                                "inputs": { "text": "A beautiful landscape, mountains" },
                                "class_type": "CLIPTextEncode"
                            }
                        }
                    }
                """;

        Map<String, String> results = service.processRawMetadata(json);

        assertNotNull(results, "Result map should not be null");

        // 1. Identity
        assertEquals("ComfyUI", results.get("Software"), "Should identify software as ComfyUI");

        // 2. Prompt Extraction
        assertTrue(results.containsKey("Prompt"), "Should contain Prompt key");
        assertTrue(results.get("Prompt").contains("beautiful landscape"), "Prompt should contain the text input");

        // 3. Technical Parameters
        assertEquals("847593291", results.get("Seed"), "Seed should match KSampler input");
        assertEquals("25", results.get("Steps"), "Steps should match KSampler input");

        String cfg = results.get("CFG");
        assertNotNull(cfg, "CFG should be present");
        assertEquals(7.0, Double.parseDouble(cfg), 0.001, "CFG value should be numerically equivalent to 7.0");

        assertEquals("euler", results.get("Sampler"), "Sampler name should be extracted");
        assertEquals("normal", results.get("Scheduler"), "Scheduler should be extracted");
    }

    // ------------------------------------------------------------------------
    // Edge Case Tests
    // ------------------------------------------------------------------------

    /**
     Verifies behavior when the raw metadata is null or empty.
     */
    @Test
    void testEmptyMetadata() {
        Map<String, String> results = service.processRawMetadata("");
        assertEquals("No metadata found in this image.", results.get("Prompt"));

        results = service.processRawMetadata(null);
        assertEquals("No metadata found in this image.", results.get("Prompt"));
    }
}