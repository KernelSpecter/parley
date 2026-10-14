package com.kernelspecter.parley.engine;

import java.io.IOException;
import java.io.Reader;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One Opus-MT (Marian) translation direction exported to ONNX: an encoder plus a merged
 * decoder with a KV cache. Decoding is greedy, which is the cheapest option and good enough
 * for short chat lines. Not thread-safe; use from one worker thread.
 */
public final class MarianModel implements AutoCloseable {
    static final String ENCODER = "encoder_model_quantized.onnx";
    static final String DECODER = "decoder_model_merged_quantized.onnx";
    public static final String[] FILES = {ENCODER, DECODER, "source.spm", "target.spm", "vocab.json"};

    private static final int MAX_SOURCE_TOKENS = 256;

    private final OrtEnvironment env;
    private final OrtSession encoder, decoder;
    private final SentencePiece source;
    private final Map<String, Integer> vocab = new HashMap<String, Integer>();
    private final String[] pieces;
    private final int eos, pad, unk, layers, heads, headDim;

    public MarianModel(Path dir) throws IOException, OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // One thread each: translation is background work and must not compete with the render thread.
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // No arena: freed tensors go back to the system instead of staying reserved between messages.
        opts.setCPUArenaAllocator(false);
        opts.setMemoryPatternOptimization(false);
        OrtSession enc = null;
        try {
            enc = env.createSession(dir.resolve(ENCODER).toString(), opts);
            decoder = env.createSession(dir.resolve(DECODER).toString(), opts);
        } catch (OrtException e) {
            if (enc != null) enc.close();
            throw e;
        } finally {
            opts.close();
        }
        encoder = enc;
        source = SentencePiece.load(dir.resolve("source.spm"));

        Reader r = Files.newBufferedReader(dir.resolve("vocab.json"), StandardCharsets.UTF_8);
        try {
            JsonObject json = new JsonParser().parse(r).getAsJsonObject();
            int max = 0;
            for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                int id = e.getValue().getAsInt();
                vocab.put(e.getKey(), id);
                max = Math.max(max, id);
            }
            pieces = new String[max + 1];
            for (Map.Entry<String, Integer> e : vocab.entrySet()) pieces[e.getValue()] = e.getKey();
        } finally {
            r.close();
        }
        eos = id("</s>");
        pad = id("<pad>");
        unk = id("<unk>");

        // Cache shape comes from the graph: past_key_values.N.decoder.key is [batch, heads, past, headDim].
        int n = 0;
        long[] shape = null;
        for (Map.Entry<String, ai.onnxruntime.NodeInfo> e : decoder.getInputInfo().entrySet()) {
            String name = e.getKey();
            if (name.startsWith("past_key_values.") && name.endsWith(".decoder.key")) {
                n++;
                shape = ((ai.onnxruntime.TensorInfo) e.getValue().getInfo()).getShape();
            }
        }
        if (shape == null) throw new IOException("decoder has no KV cache inputs");
        layers = n;
        heads = (int) shape[1];
        headDim = (int) shape[3];
    }

    private int id(String piece) throws IOException {
        Integer v = vocab.get(piece);
        if (v == null) throw new IOException("vocab.json has no " + piece);
        return v;
    }

    /** True when this model's vocabulary has the target-language token (e.g. {@code >>pt_BR<<}). */
    public boolean hasToken(String token) { return vocab.containsKey(token); }

    /**
     * Translates one chat line.
     *
     * @param targetToken language token for multi-target models such as {@code >>pt_BR<<}, or null
     */
    public String translate(String text, String targetToken) throws OrtException {
        List<String> src = source.encode(text);
        if (src.isEmpty()) return "";
        int prefix = targetToken == null ? 0 : 1;
        int len = Math.min(src.size(), MAX_SOURCE_TOKENS) + prefix + 1;
        long[] ids = new long[len];
        if (targetToken != null) {
            Integer t = vocab.get(targetToken);
            if (t == null) throw new IllegalArgumentException("model has no " + targetToken);
            ids[0] = t;
        }
        for (int i = 0; i < len - prefix - 1; i++) {
            Integer v = vocab.get(src.get(i));
            ids[i + prefix] = v == null ? unk : v;
        }
        ids[len - 1] = eos;
        long[] mask = new long[len];
        java.util.Arrays.fill(mask, 1L);

        List<Integer> out = decode(ids, mask, Math.min(3 * len + 10, 256));
        StringBuilder sb = new StringBuilder();
        for (int id : out) {
            if (id == unk || id >= pieces.length || pieces[id] == null) continue;
            sb.append(pieces[id]);
        }
        return sb.toString().replace('▁', ' ').trim();
    }

    private List<Integer> decode(long[] ids, long[] mask, int maxLen) throws OrtException {
        List<Integer> out = new ArrayList<Integer>();
        long[] shape = {1, ids.length};
        OnnxTensor inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
        OnnxTensor attention = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
        OrtSession.Result encoded = null, first = null, prev = null;
        List<OnnxTensor> temp = new ArrayList<OnnxTensor>();
        try {
            Map<String, OnnxTensor> encIn = new HashMap<String, OnnxTensor>();
            encIn.put("input_ids", inputIds);
            encIn.put("attention_mask", attention);
            encoded = encoder.run(encIn);
            OnnxTensor hidden = (OnnxTensor) encoded.get(0);

            OnnxTensor empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{1, heads, 0, headDim});
            temp.add(empty);
            OnnxTensor noCache = OnnxTensor.createTensor(env, new boolean[]{false});
            OnnxTensor useCache = OnnxTensor.createTensor(env, new boolean[]{true});
            temp.add(noCache);
            temp.add(useCache);

            long token = pad; // Marian starts decoding from the pad token.
            for (int step = 0; step < maxLen; step++) {
                Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
                in.put("encoder_attention_mask", attention);
                in.put("encoder_hidden_states", hidden);
                OnnxTensor tok = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{token}), new long[]{1, 1});
                in.put("input_ids", tok);
                in.put("use_cache_branch", step == 0 ? noCache : useCache);
                for (int l = 0; l < layers; l++) {
                    for (String kv : new String[]{"key", "value"}) {
                        String dec = "past_key_values." + l + ".decoder." + kv;
                        String enc = "past_key_values." + l + ".encoder." + kv;
                        if (step == 0) {
                            in.put(dec, empty);
                            in.put(enc, empty);
                        } else {
                            in.put(dec, tensor(prev, "present." + l + ".decoder." + kv));
                            in.put(enc, tensor(first, "present." + l + ".encoder." + kv));
                        }
                    }
                }
                OrtSession.Result res;
                try {
                    res = decoder.run(in);
                } finally {
                    tok.close();
                }
                // Keep the first step's result (it holds the encoder cache) and the previous one.
                if (prev != null && prev != first) prev.close();
                if (first == null) first = res;
                prev = res;

                OnnxTensor logits = (OnnxTensor) res.get("logits").get();
                long[] ls = logits.getInfo().getShape();
                token = argmax(logits.getFloatBuffer(), (int) ls[ls.length - 1]);
                if (token == eos) break;
                out.add((int) token);
                if (stuck(out)) return new ArrayList<Integer>();
            }
        } finally {
            if (prev != null && prev != first) prev.close();
            if (first != null) first.close();
            if (encoded != null) encoded.close();
            for (OnnxTensor t : temp) t.close();
            inputIds.close();
            attention.close();
        }
        return out;
    }

    /** A token repeated many times in a row means the model has derailed; its output is useless. */
    private static boolean stuck(List<Integer> out) {
        int n = out.size(), run = 1;
        for (int i = n - 1; i > 0 && out.get(i).equals(out.get(i - 1)); i--) run++;
        return run >= 8;
    }

    private static OnnxTensor tensor(OrtSession.Result r, String name) {
        OnnxValue v = r.get(name).orElseThrow(() -> new IllegalStateException("decoder output missing: " + name));
        return (OnnxTensor) v;
    }

    /** Best token at the last position, never the pad token (Marian's bad_words_ids). */
    private int argmax(FloatBuffer logits, int vocabSize) {
        int base = logits.limit() - vocabSize;
        int best = -1;
        float bestV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vocabSize; i++) {
            if (i == pad) continue;
            float v = logits.get(base + i);
            if (v > bestV) { bestV = v; best = i; }
        }
        return best;
    }

    @Override
    public void close() {
        try { encoder.close(); } catch (OrtException ignored) { }
        try { decoder.close(); } catch (OrtException ignored) { }
    }
}
