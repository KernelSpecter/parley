package com.kernelspecter.parley.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Expands chat shorthand ("u", "xq", "vc") into full words and writes game names in brand
 * casing, so the translation models see text closer to what they were trained on.
 */
public final class Slang {
    private final Map<String, Map<String, String>> byLang = new HashMap<String, Map<String, String>>();
    private final Map<String, String> terms;

    public Slang() throws IOException {
        terms = load("terms");
    }

    /** Rewrites {@code text}, written in {@code lang}, word by word. */
    public String expand(String text, String lang) {
        Map<String, String> words = forLang(lang);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0, n = text.length();
        while (i < n) {
            if (!isWordChar(text.charAt(i))) { out.append(text.charAt(i++)); continue; }
            int j = i;
            while (j < n && isWordChar(text.charAt(j))) j++;
            String word = text.substring(i, j);
            String key = word.toLowerCase(Locale.ROOT);
            String rep = terms.get(key);
            if (rep == null) rep = words.get(key);
            out.append(rep != null ? rep : word);
            i = j;
        }
        return out.toString();
    }

    /** True when {@code word} is a known English shorthand ("rn", "idk"). */
    public boolean isEnglishShorthand(String word) {
        return forLang("en").containsKey(word.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> forLang(String lang) {
        synchronized (byLang) {
            Map<String, String> m = byLang.get(lang);
            if (m == null) {
                try {
                    m = load(lang);
                } catch (IOException e) {
                    m = Collections.emptyMap();
                }
                byLang.put(lang, m);
            }
            return m;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'';
    }

    private static Map<String, String> load(String name) throws IOException {
        InputStream in = Slang.class.getResourceAsStream("/assets/parley/slang/" + name + ".txt");
        if (in == null) return Collections.emptyMap();
        Map<String, String> m = new HashMap<String, String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab > 0) m.put(line.substring(0, tab).toLowerCase(Locale.ROOT), line.substring(tab + 1));
            }
        } finally {
            r.close();
        }
        return m;
    }
}
