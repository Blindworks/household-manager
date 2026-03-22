package com.household.manager.ankersolix.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Example showing how to use {@link AnkerSolixClient}.
 * <p>
 * Set your credentials via environment variables before running:
 * <pre>
 *   ANKER_USER=your@email.com
 *   ANKER_PASSWORD=yourPassword
 *   ANKER_COUNTRY=DE        (2-char ISO country code)
 * </pre>
 */
public class AnkerSolixExample {

    public static void main(String[] args) throws Exception {
        // --- Credentials from environment (never hard-code!) ---
        String email    = System.getenv().getOrDefault("ANKER_USER",     "exampl@example.de");
        String password = System.getenv().getOrDefault("ANKER_PASSWORD", "strong_password");
        String country  = System.getenv().getOrDefault("ANKER_COUNTRY",  "DE");

        ObjectMapper mapper = new ObjectMapper();

        // 1. Create client
        AnkerSolixClient client = new AnkerSolixClient(email, password, country);

        // 2. Authenticate explicitly (optional – request() does this automatically)
        client.authenticate();

        // -----------------------------------------------------------------
        // 3. Get site list
        // -----------------------------------------------------------------
        JsonNode siteListResponse = client.request(AnkerApiEndpoints.GET_SITE_LIST);
        System.out.println("=== Site List ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(siteListResponse));

        // Extract the first site_id for further requests
        JsonNode sites = siteListResponse.path("data").path("site_list");
        if (sites.isEmpty()) {
            System.out.println("No sites found for this account.");
            return;
        }
        String siteId = sites.get(0).path("site_id").asText();
        System.out.println("\nUsing site_id: " + siteId);

        // -----------------------------------------------------------------
        // 4. Get site homepage (overview incl. power values)
        // -----------------------------------------------------------------
        ObjectNode homepagePayload = mapper.createObjectNode();
        homepagePayload.put("site_id", siteId);

        JsonNode homepage = client.request(AnkerApiEndpoints.GET_SITE_HOMEPAGE, homepagePayload);
        System.out.println("\n=== Site Homepage ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(homepage));

        // -----------------------------------------------------------------
        // 5. Get device parameters (e.g. Solarbank schedule / output watt)
        // -----------------------------------------------------------------
        ObjectNode parmPayload = mapper.createObjectNode();
        parmPayload.put("site_id", siteId);
        parmPayload.put("param_type", "4");  // 4 = schedule/output settings

        JsonNode deviceParm = client.request(AnkerApiEndpoints.GET_DEVICE_PARM, parmPayload);
        System.out.println("\n=== Device Parameters ===");
        System.out.println(mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(deviceParm));

        // -----------------------------------------------------------------
        // 6. Optional: Ausgangsleistung setzen (ANKER_OUTPUT_WATTS env var)
        // -----------------------------------------------------------------
        String outputWattsEnv = System.getenv("ANKER_OUTPUT_WATTS");
        if (outputWattsEnv != null) {
            int outputWatts = Integer.parseInt(outputWattsEnv);
            SolarbankController controller = new SolarbankController(client, mapper);
            controller.setOutputPower(siteId, outputWatts);
            System.out.println("\n=== Output Power Set ===");
            System.out.println("Ausgangsleistung erfolgreich auf " + outputWatts + "W gesetzt.");
        }
    }
}
