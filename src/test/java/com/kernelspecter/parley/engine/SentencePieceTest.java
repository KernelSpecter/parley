package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/** Compares against pieces produced by the reference sentencepiece library (golden/tok_golden.json). */
public class SentencePieceTest {
    @Test public void matchesReferenceEsEn() throws Exception { check("es-en"); }
    @Test public void matchesReferenceRuEn() throws Exception { check("ru-en"); }
    @Test public void matchesReferenceMulEn() throws Exception { check("mul-en"); }
    @Test public void matchesReferenceEnEs() throws Exception { check("en-es"); }

    private void check(String pair) throws Exception {
        SentencePiece sp = SentencePiece.load(TestModels.dir(pair).resolve("source.spm"));
        JsonObject golden = new JsonParser().parse(new InputStreamReader(
                getClass().getResourceAsStream("/golden/tok_golden.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray cases = golden.getAsJsonArray("cases");
        JsonArray expected = golden.getAsJsonObject("pieces").getAsJsonArray(pair);
        int failures = 0;
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < cases.size(); i++) {
            String text = cases.get(i).getAsString();
            List<String> want = new ArrayList<String>();
            for (JsonElement e : expected.get(i).getAsJsonArray()) want.add(e.getAsString());
            List<String> got = sp.encode(text);
            if (!want.equals(got)) {
                failures++;
                report.append("\n  ").append(escape(text)).append("\n    want ").append(escape(want.toString()))
                      .append("\n    got  ").append(escape(got.toString()));
            }
        }
        assertEquals(pair + " mismatches:" + report, 0, failures);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || (c >= 0x7F && c < 0xA0) || c > 0x7E) b.append(String.format("<U+%04x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }
}
