package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

public class LanguageDetectorTest {
    /** The chat handler only translates when confidence reaches this. */
    static final double THRESHOLD = LanguageDetector.MIN_CONFIDENCE;

    private static LanguageDetector detector;

    @BeforeClass public static void load() throws Exception { detector = new LanguageDetector(); }

    @Test public void scripts() {
        assertEquals("ja", detector.detect("こんにちは、元気ですか").code);
        assertEquals("zh", detector.detect("你好，我们一起玩吧").code);
        assertEquals("ko", detector.detect("안녕하세요 같이 할래요").code);
        assertEquals("ar", detector.detect("مرحبا كيف حالك").code);
        assertEquals("th", detector.detect("สวัสดีครับ").code);
    }

    @Test public void slangOnlyHasNoLanguage() {
        assertEquals(null, detector.detect("gg ez").code);
        assertEquals(null, detector.detect("xD 123 !!").code);
    }

    @Test public void chatCorpus() throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/golden/chat_corpus.tsv"), StandardCharsets.UTF_8));
        int foreign = 0, foreignRight = 0, foreignWrongConfident = 0, english = 0, englishFalseForeign = 0;
        List<String> problems = new ArrayList<String>();
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", 2);
            LanguageDetector.Result res = detector.detect(f[1]);
            boolean confident = res.code != null && res.confidence >= THRESHOLD;
            if (f[0].equals("en")) {
                english++;
                if (confident && !res.isEnglish()) { englishFalseForeign++; problems.add("EN->" + res + "  " + f[1]); }
            } else {
                foreign++;
                if (confident && f[0].equals(res.code)) foreignRight++;
                else {
                    if (confident) foreignWrongConfident++;
                    problems.add(f[0] + "->" + res + "  " + f[1]);
                }
            }
        }
        System.out.println("foreign recognised " + foreignRight + "/" + foreign + ", confidently wrong " + foreignWrongConfident
                + ", english flagged foreign " + englishFalseForeign + "/" + english);
        for (String p : problems) System.out.println("  " + p);
        assertTrue("too few foreign lines recognised", foreignRight >= foreign * 0.85);
        assertTrue("English lines would be translated", englishFalseForeign <= english * 0.05);
    }

    @Test public void englishHoldoutStaysEnglish() throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/golden/english_holdout.txt"), StandardCharsets.UTF_8));
        List<String> flagged = new ArrayList<String>();
        int total = 0;
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty()) continue;
            total++;
            LanguageDetector.Result res = detector.detect(line);
            if (res.code != null && !res.isEnglish() && res.confidence >= THRESHOLD) flagged.add(res + "  " + line);
        }
        System.out.println("english holdout flagged " + flagged.size() + "/" + total + " " + flagged);
        assertTrue(flagged.toString(), flagged.size() <= 1);
    }
}
