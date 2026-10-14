package com.kernelspecter.parley.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads Opus-MT models from Hugging Face on first use and keeps the most recently used
 * ones loaded. Each model is about 110 MB on disk and a few hundred MB of memory once loaded,
 * so only {@link #MAX_LOADED} stay in memory.
 */
public final class ModelStore {
    public interface Listener {
        void downloadStarted(String model);
        void downloadFinished(String model);
        void downloadFailed(String model, String reason);
        /** A translation threw; the line is skipped. */
        default void translationFailed(Throwable error) { }
    }

    static final int MAX_LOADED = 2;
    private static final String DONE_MARKER = ".complete";

    private final Path root;
    private final Listener listener;
    private final Map<String, MarianModel> loaded = new LinkedHashMap<String, MarianModel>(4, 0.75f, true);
    private final Map<String, Long> lastUsed = new java.util.HashMap<String, Long>();
    private final Set<String> downloading = new HashSet<String>();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Parley model download");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    public ModelStore(Path root, Listener listener) {
        this.root = root;
        this.listener = listener;
    }

    public boolean isDownloaded(String model) {
        return Files.isRegularFile(root.resolve(model).resolve(DONE_MARKER));
    }

    public synchronized boolean isDownloading(String model) {
        return downloading.contains(model);
    }

    /** Starts a background download unless the model is present or already on its way. */
    public synchronized void download(final String model) {
        if (isDownloaded(model) || !downloading.add(model)) return;
        downloads.submit(() -> {
            try {
                fetch(model);
                listener.downloadFinished(model);
            } catch (Throwable t) {
                listener.downloadFailed(model, t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                synchronized (ModelStore.this) {
                    downloading.remove(model);
                }
            }
        });
    }

    /** The loaded model, loading it from disk if needed. Call from the translation thread only. */
    public MarianModel get(String model) throws Exception {
        MarianModel m;
        synchronized (this) {
            m = loaded.get(model);
            lastUsed.put(model, System.currentTimeMillis());
        }
        if (m != null) return m;
        if (!isDownloaded(model)) throw new IllegalStateException(model + " is not downloaded");
        m = new MarianModel(root.resolve(model));
        synchronized (this) {
            loaded.put(model, m);
            Iterator<Map.Entry<String, MarianModel>> it = loaded.entrySet().iterator();
            while (loaded.size() > MAX_LOADED && it.hasNext()) {
                Map.Entry<String, MarianModel> old = it.next();
                it.remove();
                lastUsed.remove(old.getKey());
                old.getValue().close();
            }
        }
        return m;
    }

    /** Frees models nobody has used for {@code idleMillis}. Call from the translation thread only. */
    public synchronized void unloadIdle(long idleMillis) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, MarianModel>> it = loaded.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MarianModel> e = it.next();
            Long used = lastUsed.get(e.getKey());
            if (used == null || now - used >= idleMillis) {
                it.remove();
                lastUsed.remove(e.getKey());
                e.getValue().close();
            }
        }
    }

    public synchronized void close() {
        for (MarianModel m : loaded.values()) m.close();
        loaded.clear();
        downloads.shutdownNow();
    }

    private void fetch(String model) throws IOException {
        Path dir = root.resolve(model);
        Files.createDirectories(dir);
        String[][] files = {
            {"https://huggingface.co/Xenova/opus-mt-" + model + "/resolve/main/onnx/" + MarianModel.ENCODER, MarianModel.ENCODER},
            {"https://huggingface.co/Xenova/opus-mt-" + model + "/resolve/main/onnx/" + MarianModel.DECODER, MarianModel.DECODER},
            {"https://huggingface.co/Xenova/opus-mt-" + model + "/resolve/main/source.spm", "source.spm"},
            {"https://huggingface.co/Xenova/opus-mt-" + model + "/resolve/main/target.spm", "target.spm"},
            {"https://huggingface.co/Helsinki-NLP/opus-mt-" + model + "/resolve/main/vocab.json", "vocab.json"},
        };
        listener.downloadStarted(model);
        for (String[] f : files) {
            Path target = dir.resolve(f[1]);
            if (Files.isRegularFile(target)) continue;
            Path tmp = dir.resolve(f[1] + ".part");
            HttpURLConnection c = open(f[0]);
            try {
                long expected = c.getContentLengthLong(), got = 0;
                InputStream in = c.getInputStream();
                OutputStream out = Files.newOutputStream(tmp);
                try {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        got += n;
                    }
                } finally {
                    out.close();
                    in.close();
                }
                if (expected > 0 && got != expected) {
                    Files.deleteIfExists(tmp);
                    throw new IOException(f[1] + " was cut off (" + got + " of " + expected + " bytes)");
                }
            } finally {
                c.disconnect();
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(dir.resolve(DONE_MARKER), new byte[0]);
    }

    private static HttpURLConnection open(String url) throws IOException {
        // Hugging Face answers with redirects to its CDN; follow them by hand so cross-host hops work.
        for (int hops = 0; hops < 8; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Parley-Minecraft-Mod");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("redirect without location from " + url);
                url = new URL(new URL(url), next).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code + " for " + url);
            }
            return c;
        }
        throw new IOException("too many redirects for " + url);
    }
}
