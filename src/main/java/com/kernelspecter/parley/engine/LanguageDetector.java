package com.kernelspecter.parley.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Detects the language of a chat line. Non-Latin scripts are decided by script. Latin and
 * Cyrillic text is scored with naive Bayes over word frequencies and 1 to 3 character grams,
 * using the language profiles from Shuyo's language-detection (as packaged by optimaize, Apache 2.0).
 */
public final class LanguageDetector {
    /** Result of detection. {@code code} is null when the text gives no usable signal. */
    public static final class Result {
        public final String code;
        public final double confidence;

        Result(String code, double confidence) { this.code = code; this.confidence = confidence; }

        public boolean isEnglish() { return "en".equals(code); }

        @Override public String toString() { return code + String.format(Locale.ROOT, "(%.2f)", confidence); }
    }

    public static final Result NONE = new Result(null, 0);

    /** Below this confidence a line is left alone rather than risk a wrong-language translation. */
    public static final double MIN_CONFIDENCE = 0.6;

    /** Languages with profiles bundled under assets/parley/langdetect. */
    public static final List<String> PROFILES = Arrays.asList(
            "en", "es", "pt", "fr", "de", "it", "nl", "pl", "ru", "uk", "bg", "tr", "cs", "sk", "sv", "da", "no",
            "fi", "et", "id", "tl", "ro", "hu", "hr", "sl", "lt", "lv", "ca", "af", "vi");

    private static final double SMOOTHING = 1e-5;
    static double CHAR_WEIGHT = 2.0, TEMPERATURE = 2.0;

    // Words that say nothing about the language: gaming slang and acronyms used everywhere.
    private static final Set<String> NEUTRAL = new HashSet<String>(Arrays.asList(
            "gg", "ggs", "ggwp", "ez", "ezz", "lol", "lmao", "lmfao", "xd", "xdd", "ok", "okay", "k", "kk", "gl", "hf",
            "glhf", "wp", "ty", "tysm", "thx", "np", "pls", "plz", "bruh", "omg", "wtf", "kb", "afk", "brb", "rip",
            "noob", "nub", "hacker", "hacks", "hax", "bedwars", "skywars", "duels", "bw", "sw", "uhc", "pvp", "pve",
            "hypixel", "minecraft", "mc", "discord", "yt", "youtube", "tiktok", "twitch", "fps", "ping", "lag", "op",
            "nerf", "buff", "ff", "gf", "bf", "irl", "idk", "imo", "tbh", "btw", "ikr", "smh", "fr", "ngl", "w", "l",
            "ha", "haha", "hahaha", "hah", "jaja", "jajaja", "kkk", "kkkk", "rs", "rsrs", "xdxd", "uwu", "owo", "hi",
            "hey", "yo", "sup", "bro", "dude", "lobby", "party", "guild", "clan", "team", "solo", "duo", "trio",
            "vip", "mvp", "admin", "mod", "staff", "server", "ip", "lvl", "level", "xp", "exp", "coins", "kill",
            "kills", "bed", "rush", "spawn", "tp", "1v1", "2v2", "4v4", "cps", "qwerty", "hmm", "hm", "mm", "ah",
            "oh", "eh", "uh", "um", "o", "a", "e", "y", "i"));

    private final Map<String, double[]> gramProbs = new HashMap<String, double[]>();
    private final Map<String, double[]> wordProbs = new HashMap<String, double[]>();
    private final double[] wordFloor;
    private final String[] langs;

    public LanguageDetector() throws IOException {
        this(PROFILES);
    }

    public LanguageDetector(List<String> codes) throws IOException {
        langs = codes.toArray(new String[0]);
        wordFloor = new double[langs.length];
        for (int i = 0; i < langs.length; i++) {
            loadProfile(i);
            loadWords(i);
        }
        boostEnglishShorthand();
    }

    /**
     * English chat shorthand ("rn", "idk", "wdym") is rare in subtitles, so the word lists
     * undersell it. Shorthand that isn't also a word in another language counts as common English.
     */
    private void boostEnglishShorthand() throws IOException {
        int en = Arrays.asList(langs).indexOf("en");
        InputStream in = LanguageDetector.class.getResourceAsStream("/assets/parley/slang/en.txt");
        if (en < 0 || in == null) return;
        java.io.BufferedReader r = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('	');
                if (line.startsWith("#") || tab <= 0) continue;
                String w = fold(line.substring(0, tab));
                double[] p = wordProbs.get(w);
                if (p == null) wordProbs.put(w, p = new double[langs.length]);
                boolean foreign = false;
                for (int l = 0; l < langs.length; l++) if (l != en && p[l] > 0) foreign = true;
                if (!foreign) p[en] = Math.max(p[en], 1e-3);
            }
        } finally {
            r.close();
        }
    }

    /** Top words from OpenSubtitles frequency lists (hermitdave/FrequencyWords, CC BY-SA 4.0). */
    private void loadWords(int index) throws IOException {
        String path = "/assets/parley/langdetect/words/" + langs[index] + ".txt";
        InputStream in = LanguageDetector.class.getResourceAsStream(path);
        if (in == null) throw new IOException("missing word list " + path);
        Map<String, Double> counts = new HashMap<String, Double>();
        double total = 0, min = Double.MAX_VALUE;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                int sp = line.lastIndexOf(' ');
                if (sp <= 0) continue;
                double c = Double.parseDouble(line.substring(sp + 1).trim());
                String w = fold(line.substring(0, sp));
                Double old = counts.get(w);
                counts.put(w, old == null ? c : old + c);
                total += c;
                min = Math.min(min, c);
            }
        } finally {
            in.close();
        }
        for (Map.Entry<String, Double> e : counts.entrySet()) {
            double[] p = wordProbs.get(e.getKey());
            if (p == null) wordProbs.put(e.getKey(), p = new double[langs.length]);
            p[index] = e.getValue() / total;
        }
        // A word missing from the list is rarer than the list's last entry.
        wordFloor[index] = 0.5 * min / total;
    }

    /** Lowercase and strip Latin diacritics: chat is often typed without them. */
    static String fold(String word) {
        String s = word.toLowerCase(Locale.ROOT);
        boolean plain = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) { plain = false; break; }
        if (plain) return s;
        StringBuilder b = new StringBuilder(s.length());
        String nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        for (int i = 0; i < nfd.length(); i++) {
            char c = nfd.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK && i > 0
                    && Character.UnicodeScript.of(nfd.charAt(i - 1)) == Character.UnicodeScript.LATIN) continue;
            switch (c) {
                case 'ł': b.append('l'); break;
                case 'ß': b.append("ss"); break;
                case 'ı': b.append('i'); break;
                case 'ø': b.append('o'); break;
                case 'æ': b.append("ae"); break;
                case 'đ': b.append('d'); break;
                case 'œ': b.append("oe"); break;
                default: b.append(c);
            }
        }
        return java.text.Normalizer.normalize(b, java.text.Normalizer.Form.NFC);
    }

    private void loadProfile(int index) throws IOException {
        String path = "/assets/parley/langdetect/" + langs[index];
        InputStream in = LanguageDetector.class.getResourceAsStream(path);
        if (in == null) throw new IOException("missing language profile " + path);
        try {
            JsonObject json = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray nWords = json.getAsJsonArray("n_words");
            double[] totals = {nWords.get(0).getAsDouble(), nWords.get(1).getAsDouble(), nWords.get(2).getAsDouble()};
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("freq").entrySet()) {
                String gram = e.getKey().toLowerCase(Locale.ROOT);
                int n = gram.length();
                if (n < 1 || n > 3) continue;
                double[] probs = gramProbs.get(gram);
                if (probs == null) gramProbs.put(gram, probs = new double[langs.length]);
                // Upper- and lower-case variants of a gram fold together.
                probs[index] += e.getValue().getAsDouble() / totals[n - 1];
            }
        } finally {
            in.close();
        }
    }

    public Result detect(String text) {
        String script = scriptLanguage(text);
        if (script != null) return new Result(script, 1.0);

        List<String> meaningful = new ArrayList<String>();
        for (String w : words(text)) if (!NEUTRAL.contains(w) && !isNumberLike(w)) meaningful.add(w);
        if (meaningful.isEmpty()) return NONE;

        int n = langs.length;
        double[] wordScore = new double[n], charScore = new double[n];
        int grams = 0;
        for (String w : meaningful) {
            double[] wp = wordProbs.get(fold(w));
            for (int l = 0; l < n; l++) wordScore[l] += Math.log(wp != null && wp[l] > 0 ? wp[l] : wordFloor[l]);
            String padded = " " + w + " ";
            for (int len = 1; len <= 3; len++) {
                for (int i = 0; i + len <= padded.length(); i++) {
                    String g = padded.substring(i, i + len);
                    if (g.trim().isEmpty()) continue;
                    double[] p = gramProbs.get(g);
                    if (p == null) continue;
                    grams++;
                    for (int l = 0; l < n; l++) charScore[l] += Math.log(p[l] + SMOOTHING);
                }
            }
        }
        // Per-word scale: the char model contributes about one "word" of evidence per word.
        double charWeight = grams == 0 ? 0 : CHAR_WEIGHT * meaningful.size() / grams;
        double[] score = new double[n];
        for (int l = 0; l < n; l++) score[l] = (wordScore[l] + charWeight * charScore[l]) / TEMPERATURE;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : score) max = Math.max(max, v);
        double sum = 0;
        double[] post = new double[n];
        for (int l = 0; l < n; l++) sum += post[l] = Math.exp(score[l] - max);
        int best = 0;
        for (int l = 1; l < n; l++) if (post[l] > post[best]) best = l;
        return new Result(langs[best], post[best] / sum);
    }

    /** Lowercased letter runs (apostrophes kept inside words). */
    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder w = new StringBuilder();
        String s = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean inner = (c == '\'' || c == '’') && w.length() > 0 && i + 1 < s.length() && Character.isLetter(s.charAt(i + 1));
            if (Character.isLetterOrDigit(c) || inner) {
                w.append(c == '’' ? '\'' : c);
            } else if (w.length() > 0) {
                out.add(w.toString());
                w.setLength(0);
            }
        }
        if (w.length() > 0) out.add(w.toString());
        return out;
    }

    private static boolean isNumberLike(String w) {
        for (int i = 0; i < w.length(); i++) if (Character.isLetter(w.charAt(i))) return false;
        return true;
    }

    /** Language decided by script alone, or null when the text is Latin/Cyrillic (or has no letters). */
    static String scriptLanguage(String text) {
        int kana = 0, han = 0, hangul = 0, thai = 0, arabic = 0, persian = 0, devanagari = 0, greek = 0, hebrew = 0, letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            switch (sc) {
                case HIRAGANA: case KATAKANA: kana++; break;
                case HAN: han++; break;
                case HANGUL: hangul++; break;
                case THAI: thai++; break;
                case ARABIC:
                    arabic++;
                    if (cp == 0x067E || cp == 0x0686 || cp == 0x0698 || cp == 0x06AF || cp == 0x06CC) persian++;
                    break;
                case DEVANAGARI: devanagari++; break;
                case GREEK: greek++; break;
                case HEBREW: hebrew++; break;
                default: break;
            }
        }
        if (letters == 0) return null;
        int other = kana + han + hangul + thai + arabic + devanagari + greek + hebrew;
        if (other * 2 < letters) return null; // mostly Latin/Cyrillic
        if (kana > 0) return "ja";
        if (hangul >= han && hangul > 0) return "ko";
        if (han > 0) return "zh";
        if (thai > 0) return "th";
        if (arabic > 0) return persian > 0 ? "fa" : "ar";
        if (devanagari > 0) return "hi";
        if (greek > 0) return "el";
        return "he";
    }
}
