package com.kernelspecter.parley;

/**
 * Splits a formatted chat line ("§7[VIP] §aSteve§f: hola") into the sender part, kept with its
 * colours, and the message body as plain text.
 */
final class ChatLine {
    private static final String[] SEPARATORS = {": ", " » ", "» ", " > ", "> "};
    /** Longer prefixes are unlikely to be a name and rank, so the whole line is the message. */
    private static final int MAX_PREFIX = 64;

    final String prefix, prefixPlain, body;

    private ChatLine(String prefix, String prefixPlain, String body) {
        this.prefix = prefix;
        this.prefixPlain = prefixPlain;
        this.body = body;
    }

    static ChatLine parse(String formatted) {
        String plain = strip(formatted);
        // Separators in order of preference: "Guild > [MVP+] Steve: hi" splits at the colon.
        int cut = -1;
        for (String sep : SEPARATORS) {
            int i = plain.indexOf(sep);
            if (i > 0 && i < MAX_PREFIX && hasLetter(plain, i)) {
                cut = i + sep.length();
                break;
            }
        }
        if (cut < 0) return new ChatLine("", "", plain.trim());
        return new ChatLine(formatted.substring(0, formattedIndex(formatted, cut)), plain.substring(0, cut), plain.substring(cut).trim());
    }

    /** Removes § formatting codes. */
    static String strip(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) i++;
            else b.append(c);
        }
        return b.toString();
    }

    /** Index in {@code formatted} where plain character number {@code plainIndex} starts. */
    private static int formattedIndex(String formatted, int plainIndex) {
        int plain = 0, i = 0;
        while (i < formatted.length()) {
            if (formatted.charAt(i) == '§' && i + 1 < formatted.length()) { i += 2; continue; }
            if (plain == plainIndex) break;
            plain++;
            i++;
        }
        // Keep colour codes that directly follow the separator so the body colour carries over.
        while (i + 1 < formatted.length() && formatted.charAt(i) == '§') i += 2;
        return i;
    }

    private static boolean hasLetter(String s, int end) {
        for (int i = 0; i < end; i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }
}
