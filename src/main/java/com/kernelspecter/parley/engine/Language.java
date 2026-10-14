package com.kernelspecter.parley.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A chat language and the Opus-MT models that translate it. Dedicated pair models are used where
 * one exists; the rest fall back to the multilingual models (mul-en, en-mul), which are weaker.
 */
public final class Language {
    public final String code, name;
    /** Model folder translating this language into English. */
    public final String toEnglish;
    /** Model folder translating English into this language, or null if sending isn't supported. */
    public final String fromEnglish;
    /** Target token for multi-target models (e.g. {@code >>pt_BR<<}), or null. */
    public final String token;

    private Language(String code, String name, String toEnglish, String fromEnglish, String token) {
        this.code = code;
        this.name = name;
        this.toEnglish = toEnglish;
        this.fromEnglish = fromEnglish;
        this.token = token;
    }

    private static final Map<String, Language> ALL = new LinkedHashMap<String, Language>();

    private static void add(String code, String name, String in, String out, String token) {
        ALL.put(code, new Language(code, name, in, out, token));
    }

    static {
        add("es", "Spanish", "es-en", "en-es", null);
        add("pt", "Portuguese", "ROMANCE-en", "en-ROMANCE", ">>pt_BR<<");
        add("fr", "French", "fr-en", "en-fr", null);
        add("de", "German", "de-en", "en-de", null);
        add("it", "Italian", "it-en", "en-it", null);
        add("nl", "Dutch", "nl-en", "en-nl", null);
        add("pl", "Polish", "pl-en", "en-mul", ">>pol<<");
        add("ru", "Russian", "ru-en", "en-ru", null);
        add("uk", "Ukrainian", "uk-en", "en-uk", null);
        add("tr", "Turkish", "mul-en", "en-mul", ">>tur<<");
        add("cs", "Czech", "cs-en", "en-cs", null);
        add("sk", "Slovak", "mul-en", null, null);
        add("sv", "Swedish", "sv-en", "en-sv", null);
        add("da", "Danish", "da-en", "en-da", null);
        add("no", "Norwegian", "mul-en", "en-mul", ">>nob<<");
        add("fi", "Finnish", "fi-en", "en-fi", null);
        add("et", "Estonian", "et-en", "en-mul", ">>est<<");
        add("id", "Indonesian", "id-en", "en-id", null);
        add("tl", "Tagalog", "mul-en", null, null);
        add("ro", "Romanian", "ROMANCE-en", "en-ro", null);
        add("hu", "Hungarian", "hu-en", "en-hu", null);
        add("hr", "Croatian", "mul-en", "en-mul", ">>hrv<<");
        add("sl", "Slovenian", "mul-en", "en-mul", ">>slv<<");
        add("lt", "Lithuanian", "mul-en", "en-mul", ">>lit<<");
        add("lv", "Latvian", "mul-en", "en-mul", ">>lav<<");
        add("ca", "Catalan", "ROMANCE-en", "en-ROMANCE", ">>ca<<");
        add("af", "Afrikaans", "af-en", "en-af", null);
        add("vi", "Vietnamese", "vi-en", "en-vi", ">>vie<<");
        add("bg", "Bulgarian", "mul-en", "en-mul", ">>bul<<");
        add("ja", "Japanese", "ja-en", "en-mul", ">>jpn<<");
        add("zh", "Chinese", "zh-en", "en-zh", ">>cmn_Hans<<");
        add("ko", "Korean", "ko-en", null, null);
        add("ar", "Arabic", "ar-en", "en-ar", ">>ara<<");
        add("th", "Thai", "th-en", "en-mul", ">>tha<<");
        add("hi", "Hindi", "hi-en", "en-hi", null);
        add("fa", "Persian", "mul-en", "en-mul", ">>pes<<");
        add("el", "Greek", "mul-en", "en-mul", ">>ell<<");
        add("he", "Hebrew", "mul-en", "en-mul", ">>heb<<");
    }

    /** Looks up by code ("es") or English name ("spanish"); null if unknown. */
    public static Language find(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);
        Language l = ALL.get(k);
        if (l != null) return l;
        for (Language x : ALL.values()) if (x.name.toLowerCase(Locale.ROOT).equals(k)) return x;
        return null;
    }

    public static List<Language> all() {
        return Collections.unmodifiableList(new ArrayList<Language>(ALL.values()));
    }

    public boolean canSend() { return fromEnglish != null; }

    /** True when sending uses the weaker multilingual model. */
    public boolean sendIsRough() { return "en-mul".equals(fromEnglish); }

    public String tag() { return code.toUpperCase(Locale.ROOT); }

    @Override public String toString() { return name + " (" + code + ")"; }
}
