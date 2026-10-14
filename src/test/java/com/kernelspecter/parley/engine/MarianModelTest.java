package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/** Greedy output must equal the Python/onnxruntime reference (golden/tr_*.json). */
public class MarianModelTest {
    @Test public void spanishToEnglish() throws Exception { check("es-en", null); }
    @Test public void russianToEnglish() throws Exception { check("ru-en", null); }
    @Test public void englishToSpanish() throws Exception { check("en-es", null); }
    @Test public void englishToPortugueseViaRomance() throws Exception { check("en-ROMANCE", ">>pt_BR<<"); }

    private void check(String pair, String token) throws Exception {
        MarianModel m = new MarianModel(TestModels.dir(pair));
        try {
            JsonArray cases = new JsonParser().parse(new InputStreamReader(
                    getClass().getResourceAsStream("/golden/tr_" + pair + ".json"), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : cases) {
                JsonObject c = e.getAsJsonObject();
                long t0 = System.nanoTime();
                String got = m.translate(c.get("input").getAsString(), token);
                System.out.printf("%5d ms  %s -> %s%n", (System.nanoTime() - t0) / 1000000, c.get("input").getAsString(), got);
                assertEquals(c.get("output").getAsString(), got);
            }
        } finally {
            m.close();
        }
    }
}
