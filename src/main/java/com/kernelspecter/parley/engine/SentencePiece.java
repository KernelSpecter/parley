package com.kernelspecter.parley.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal SentencePiece unigram encoder: reads a .spm model and reproduces
 * {@code SentencePieceProcessor.encode_as_pieces} (normalization via the model's
 * precompiled charsmap, then Viterbi over piece scores).
 */
public final class SentencePiece {
    private static final int TYPE_NORMAL = 1, TYPE_USER_DEFINED = 4;
    private static final float UNK_PENALTY = 10f;
    private static final String SPACE = "▁";

    private final Map<String, Float> scores = new HashMap<String, Float>();
    private int maxPieceChars = 1;
    private float unkScore;

    private int[] trie = new int[0];
    private byte[] normalized = new byte[0];
    private boolean addDummyPrefix = true, removeExtraWhitespaces = true, escapeWhitespaces = true;

    public static SentencePiece load(Path file) throws IOException {
        return new SentencePiece(Files.readAllBytes(file));
    }

    SentencePiece(byte[] model) {
        float minScore = Float.MAX_VALUE;
        Proto p = new Proto(model, 0, model.length);
        while (p.more()) {
            int tag = p.varint32(), field = tag >>> 3;
            if (field == 1 && (tag & 7) == 2) {
                Proto piece = p.message();
                String text = null;
                float score = 0;
                int type = TYPE_NORMAL;
                while (piece.more()) {
                    int t = piece.varint32();
                    switch (t >>> 3) {
                        case 1: text = piece.string(); break;
                        case 2: score = piece.fixed32Float(); break;
                        case 3: type = (int) piece.varint(); break;
                        default: piece.skip(t);
                    }
                }
                if (text != null && (type == TYPE_NORMAL || type == TYPE_USER_DEFINED)) {
                    scores.put(text, score);
                    maxPieceChars = Math.max(maxPieceChars, text.codePointCount(0, text.length()));
                    if (type == TYPE_NORMAL) minScore = Math.min(minScore, score);
                }
            } else if (field == 3 && (tag & 7) == 2) {
                readNormalizer(p.message());
            } else {
                p.skip(tag);
            }
        }
        unkScore = minScore - UNK_PENALTY;
    }

    private void readNormalizer(Proto n) {
        while (n.more()) {
            int t = n.varint32();
            switch (t >>> 3) {
                case 2: setCharsmap(n.bytes()); break;
                case 3: addDummyPrefix = n.varint() != 0; break;
                case 4: removeExtraWhitespaces = n.varint() != 0; break;
                case 5: escapeWhitespaces = n.varint() != 0; break;
                default: n.skip(t);
            }
        }
    }

    private void setCharsmap(byte[] blob) {
        if (blob.length < 4) return;
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int trieBytes = b.getInt();
        trie = new int[trieBytes / 4];
        for (int i = 0; i < trie.length; i++) trie[i] = b.getInt();
        normalized = new byte[blob.length - 4 - trieBytes];
        b.get(normalized);
    }

    /** Pieces for {@code text}, exactly as SentencePiece would split it. */
    public List<String> encode(String text) {
        String s = normalize(text);
        if (s.isEmpty()) return Collections.emptyList();
        int[] cps = s.codePoints().toArray();
        int n = cps.length;
        // Character offsets of each code point, to slice substrings cheaply.
        int[] at = new int[n + 1];
        for (int i = 0, off = 0; i < n; i++) { at[i] = off; off += Character.charCount(cps[i]); }
        at[n] = s.length();

        float[] best = new float[n + 1];
        int[] from = new int[n + 1];
        boolean[] unk = new boolean[n + 1];
        java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int i = 0; i < n; i++) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue;
            boolean single = false;
            int maxLen = Math.min(maxPieceChars, n - i);
            for (int len = 1; len <= maxLen; len++) {
                Float score = scores.get(s.substring(at[i], at[i + len]));
                if (score == null) continue;
                if (len == 1) single = true;
                float v = best[i] + score;
                if (v > best[i + len]) { best[i + len] = v; from[i + len] = i; unk[i + len] = false; }
            }
            if (!single) {
                float v = best[i] + unkScore;
                if (v > best[i + 1]) { best[i + 1] = v; from[i + 1] = i; unk[i + 1] = true; }
            }
        }
        // Walk back; SentencePiece merges runs of unknown characters into one piece.
        List<String> pieces = new ArrayList<String>();
        for (int end = n; end > 0; ) {
            int start = from[end];
            if (unk[end]) while (start > 0 && unk[start]) start = from[start];
            pieces.add(s.substring(at[start], at[end]));
            end = start;
        }
        Collections.reverse(pieces);
        return pieces;
    }

    /** Normalizes like SentencePiece's Normalizer, including whitespace escaping. */
    String normalize(String text) {
        byte[] in = text.getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        long r;
        if (removeExtraWhitespaces) {
            while (pos < in.length) {
                r = normalizePrefix(in, pos);
                if (!" ".equals(replacement(r, in, pos))) break;
                pos += consumed(r);
            }
        }
        if (pos >= in.length) return "";

        StringBuilder out = new StringBuilder();
        if (addDummyPrefix) out.append(escapeWhitespaces ? SPACE : " ");
        boolean prevSpace = removeExtraWhitespaces;
        while (pos < in.length) {
            r = normalizePrefix(in, pos);
            String piece = replacement(r, in, pos);
            int k = 0;
            while (prevSpace && k < piece.length() && piece.charAt(k) == ' ') k++;
            if (k < piece.length()) {
                for (int j = k; j < piece.length(); j++) {
                    char c = piece.charAt(j);
                    if (escapeWhitespaces && c == ' ') out.append(SPACE); else out.append(c);
                }
                prevSpace = piece.charAt(piece.length() - 1) == ' ';
            }
            pos += consumed(r);
            if (!removeExtraWhitespaces) prevSpace = false;
        }
        if (removeExtraWhitespaces) {
            String ws = escapeWhitespaces ? SPACE : " ";
            while (out.length() > 0 && out.lastIndexOf(ws) == out.length() - ws.length())
                out.setLength(out.length() - ws.length());
        }
        return out.toString();
    }

    // normalizePrefix packs (offset into normalized blob or -1-for-passthrough) and consumed bytes.
    private long normalizePrefix(byte[] in, int pos) {
        int matchLen = 0, matchValue = -1;
        if (trie.length > 0) {
            int node = offset(trie[0]);
            for (int i = pos; i < in.length; i++) {
                int c = in[i] & 0xFF;
                if (c == 0) break;
                node ^= c;
                if (node < 0 || node >= trie.length) break;
                int unit = trie[node];
                if (label(unit) != c) break;
                node ^= offset(unit);
                if (hasLeaf(unit) && node < trie.length) {
                    matchLen = i - pos + 1;
                    matchValue = trie[node] & 0x7FFFFFFF;
                }
            }
        }
        if (matchLen > 0) return ((long) matchValue << 32) | matchLen;
        int len = utf8Len(in, pos);
        return (-1L << 32) | (len & 0xFFFFFFFFL);
    }

    private static int consumed(long r) { return (int) r; }

    private String replacement(long r, byte[] in, int pos) {
        int value = (int) (r >> 32);
        if (value == -1) {
            int len = consumed(r);
            if (len == 1 && (in[pos] & 0x80) != 0) return "�"; // malformed byte
            return new String(in, pos, len, StandardCharsets.UTF_8);
        }
        int end = value;
        while (end < normalized.length && normalized[end] != 0) end++;
        return new String(normalized, value, end - value, StandardCharsets.UTF_8);
    }

    private static int utf8Len(byte[] in, int pos) {
        int b = in[pos] & 0xFF, need;
        if (b < 0x80) return 1;
        else if (b >= 0xC2 && b < 0xE0) need = 2;
        else if (b >= 0xE0 && b < 0xF0) need = 3;
        else if (b >= 0xF0 && b < 0xF5) need = 4;
        else return 1;
        if (pos + need > in.length) return 1;
        for (int i = 1; i < need; i++) if ((in[pos + i] & 0xC0) != 0x80) return 1;
        return need;
    }

    // darts-clone double-array unit accessors.
    private static boolean hasLeaf(int unit) { return ((unit >>> 8) & 1) == 1; }
    private static int label(int unit) { return unit & (0x80000000 | 0xFF); }
    private static int offset(int unit) { return (unit >>> 10) << ((unit & (1 << 9)) >>> 6); }

    /** Just enough protobuf wire-format reading for ModelProto. */
    private static final class Proto {
        private final byte[] b;
        private int pos;
        private final int end;

        Proto(byte[] b, int pos, int end) { this.b = b; this.pos = pos; this.end = end; }

        boolean more() { return pos < end; }

        long varint() {
            long v = 0;
            for (int shift = 0; ; shift += 7) {
                byte x = b[pos++];
                v |= (long) (x & 0x7F) << shift;
                if (x >= 0) return v;
            }
        }

        int varint32() { return (int) varint(); }

        float fixed32Float() {
            int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
            pos += 4;
            return Float.intBitsToFloat(v);
        }

        Proto message() {
            int len = varint32();
            Proto m = new Proto(b, pos, pos + len);
            pos += len;
            return m;
        }

        byte[] bytes() {
            int len = varint32();
            byte[] out = java.util.Arrays.copyOfRange(b, pos, pos + len);
            pos += len;
            return out;
        }

        String string() { return new String(bytes(), StandardCharsets.UTF_8); }

        void skip(int tag) {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: int len = varint32(); pos += len; break;
                case 5: pos += 4; break;
                default: throw new IllegalStateException("unsupported wire type " + (tag & 7));
            }
        }
    }
}
