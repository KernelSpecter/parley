package com.kernelspecter.parley.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything the mod needs: language detection, slang expansion, model management and one
 * low-priority worker thread that runs all translations. Callbacks run on that worker thread.
 */
public final class Translator {
    /** A translated incoming line. */
    public static final class Incoming {
        public final Language language;
        public final String original, translated;

        Incoming(Language language, String original, String translated) {
            this.language = language;
            this.original = original;
            this.translated = translated;
        }
    }

    private static final int MAX_QUEUED_INCOMING = 16, MAX_PENDING_PER_MODEL = 3, CACHE_SIZE = 500;
    /** Loaded models cost about 280 MB each, so ones that sit unused are freed. */
    private static final long UNLOAD_AFTER_MILLIS = 5 * 60 * 1000L, IDLE_CHECK_MILLIS = 60 * 1000L;

    private final LanguageDetector detector;
    private final Slang slang;
    private final ModelStore models;
    private final ModelStore.Listener listener;

    private final Deque<Runnable> incoming = new ArrayDeque<Runnable>();
    private final Deque<Runnable> outgoing = new ArrayDeque<Runnable>();
    private final Thread worker;
    private volatile boolean running = true;
    private long lastIdleCheck = System.currentTimeMillis();

    private final Map<String, Incoming> cache = new LinkedHashMap<String, Incoming>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Incoming> e) { return size() > CACHE_SIZE; }
    };
    private final Map<String, List<Runnable>> waitingForModel = new HashMap<String, List<Runnable>>();

    private volatile boolean autoDownload = true;
    private volatile Set<String> ignored = Collections.emptySet();

    public Translator(Path modelDir, ModelStore.Listener listener) throws IOException {
        this.listener = listener;
        detector = new LanguageDetector();
        slang = new Slang();
        models = new ModelStore(modelDir, new ModelStore.Listener() {
            @Override public void downloadStarted(String model) { Translator.this.listener.downloadStarted(model); }

            @Override public void downloadFinished(String model) {
                Translator.this.listener.downloadFinished(model);
                List<Runnable> waiting;
                synchronized (waitingForModel) {
                    waiting = waitingForModel.remove(model);
                }
                if (waiting != null) for (Runnable r : waiting) enqueue(incoming, r);
            }

            @Override public void downloadFailed(String model, String reason) {
                synchronized (waitingForModel) {
                    waitingForModel.remove(model);
                }
                Translator.this.listener.downloadFailed(model, reason);
            }
        });
        worker = new Thread(this::work, "Parley translator");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    public ModelStore models() { return models; }

    public LanguageDetector.Result detect(String text) { return detector.detect(text); }

    public void setAutoDownload(boolean on) { autoDownload = on; }

    /** Language codes whose messages are left untranslated. */
    public void setIgnored(Set<String> codes) { ignored = Collections.unmodifiableSet(new HashSet<String>(codes)); }

    /**
     * Translates a chat line into English if it's in another language. {@code done} is called only
     * when there is something worth showing: English, slang-only and unsure lines produce nothing.
     */
    public void translateIncoming(final String text, final Consumer<Incoming> done) {
        enqueue(incoming, new Runnable() {
            @Override public void run() {
                Incoming hit;
                synchronized (cache) {
                    hit = cache.get(text);
                }
                if (hit != null) { done.accept(hit); return; }

                LanguageDetector.Result r = detector.detect(text);
                if (r.code == null || r.isEnglish() || r.confidence < LanguageDetector.MIN_CONFIDENCE) return;
                Language lang = Language.find(r.code);
                if (lang == null || ignored.contains(lang.code)) return;

                if (!models.isDownloaded(lang.toEnglish)) {
                    if (!autoDownload) return;
                    synchronized (waitingForModel) {
                        List<Runnable> w = waitingForModel.get(lang.toEnglish);
                        if (w == null) waitingForModel.put(lang.toEnglish, w = new ArrayList<Runnable>());
                        if (w.size() < MAX_PENDING_PER_MODEL) w.add(this);
                    }
                    models.download(lang.toEnglish);
                    return;
                }
                String out;
                try {
                    out = models.get(lang.toEnglish).translate(slang.expand(text, lang.code), null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                // A translation that reads like the input means it was English (or a name) all along.
                if (out.isEmpty() || Text.similar(out, text)) return;
                Incoming result = new Incoming(lang, text, out);
                synchronized (cache) {
                    cache.put(text, result);
                }
                done.accept(result);
            }
        });
    }

    /**
     * Translates an English line into {@code to}. Exactly one of {@code done} or {@code failed}
     * is called. A line already in the target language is passed through unchanged.
     */
    public void translateOutgoing(final String text, final Language to, final Consumer<String> done, final Consumer<String> failed) {
        enqueue(outgoing, () -> {
            LanguageDetector.Result r = detector.detect(text);
            if (r.code != null && r.code.equals(to.code) && r.confidence >= LanguageDetector.MIN_CONFIDENCE) {
                done.accept(text);
                return;
            }
            if (!to.canSend()) {
                failed.accept("Parley can't write " + to.name + " yet, only read it.");
                return;
            }
            if (!models.isDownloaded(to.fromEnglish)) {
                models.download(to.fromEnglish);
                failed.accept("The English to " + to.name + " model is still downloading. Send it again once it's ready.");
                return;
            }
            try {
                String out = models.get(to.fromEnglish).translate(slang.expand(text, "en"), to.token);
                if (out.isEmpty()) failed.accept("The translation came out empty.");
                else done.accept(out);
            } catch (Throwable t) {
                failed.accept("Translation failed: " + t);
            }
        });
    }

    public void close() {
        running = false;
        worker.interrupt();
        models.close();
    }

    private void enqueue(Deque<Runnable> queue, Runnable task) {
        synchronized (this) {
            queue.addLast(task);
            // Busy lobbies can outrun the translator; old chat lines matter least.
            while (queue == incoming && incoming.size() > MAX_QUEUED_INCOMING) incoming.removeFirst();
            notifyAll();
        }
    }

    private void work() {
        while (running) {
            Runnable task;
            synchronized (this) {
                while (running && incoming.isEmpty() && outgoing.isEmpty()) {
                    try {
                        wait(IDLE_CHECK_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (incoming.isEmpty() && outgoing.isEmpty()) break;
                }
                if (!running) return;
                // The player's own messages go first.
                task = !outgoing.isEmpty() ? outgoing.pollFirst() : incoming.pollFirst();
            }
            if (task != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    // One bad line must never stop the worker.
                    listener.translationFailed(t);
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastIdleCheck >= IDLE_CHECK_MILLIS) {
                lastIdleCheck = now;
                models.unloadIdle(UNLOAD_AFTER_MILLIS);
            }
        }
    }

    /** Small text helpers. */
    static final class Text {
        private Text() {}

        /** True when two strings differ only in case, punctuation or a few letters. */
        static boolean similar(String a, String b) {
            String x = letters(a), y = letters(b);
            if (x.isEmpty() || y.isEmpty()) return x.equals(y);
            int d = distance(x, y);
            return d <= Math.max(1, Math.max(x.length(), y.length()) / 5);
        }

        private static String letters(String s) {
            StringBuilder b = new StringBuilder();
            for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
            return b.toString();
        }

        private static int distance(String a, String b) {
            int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                cur[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                    cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
                }
                int[] t = prev; prev = cur; cur = t;
            }
            return prev[b.length()];
        }
    }
}
