package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** End to end through the worker thread, with the models from PARLEY_MODELS. */
public class TranslatorTest {
    private static Translator t;

    @BeforeClass public static void start() throws Exception {
        t = new Translator(TestModels.dir("es-en").getParent(), new ModelStore.Listener() {
            public void downloadStarted(String model) { throw new AssertionError("unexpected download of " + model); }
            public void downloadFinished(String model) { }
            public void downloadFailed(String model, String reason) { throw new AssertionError(model + ": " + reason); }
        });
        t.setAutoDownload(false);
    }

    @AfterClass public static void stop() { if (t != null) t.close(); }

    private static Translator.Incoming in(String text) throws Exception {
        CompletableFuture<Translator.Incoming> f = new CompletableFuture<Translator.Incoming>();
        t.translateIncoming(text, f::complete);
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return null; // nothing to show
        }
    }

    @Test public void spanishIsTranslatedAndLabelled() throws Exception {
        Translator.Incoming r = in("alguien quiere jugar bedwars conmigo?");
        assertNotNull(r);
        assertEquals("es", r.language.code);
        System.out.println(r.translated);
        assertTrue(r.translated.contains("BedWars"));
    }

    @Test public void spanishSlangIsExpanded() throws Exception {
        // "tmb" and "porfa" are unknown to the model; expanded they translate cleanly.
        Translator.Incoming r = in("tmb quiero jugar porfa");
        assertNotNull(r);
        System.out.println(r.translated);
        assertTrue(r.translated, r.translated.toLowerCase().contains("also") || r.translated.toLowerCase().contains("too"));
        assertTrue(r.translated, r.translated.toLowerCase().contains("please"));
    }

    @Test public void englishIsNeverTranslated() throws Exception {
        assertNull(in("anyone want to play bedwars with me"));
        assertNull(in("im lagging so hard rn"));
        assertNull(in("gg ez"));
    }

    /** A clicked line: the translation, or the reason there is none as "failed: ...". */
    private static Object requested(String text) throws Exception {
        CompletableFuture<Object> f = new CompletableFuture<Object>();
        t.translateRequested(text, f::complete, e -> f.complete("failed: " + e));
        return f.get(10, TimeUnit.SECONDS);
    }

    @Test public void requestedSpanishIsLabelled() throws Exception {
        Translator.Incoming r = (Translator.Incoming) requested("alguien quiere jugar bedwars conmigo?");
        assertEquals("es", r.language.code);
    }

    @Test public void requestedEnglishSaysSo() throws Exception {
        assertEquals("failed: That line already reads as English.", requested("anyone want to play bedwars with me"));
    }

    @Test public void requestedUnsureLineGoesThroughMultilingual() throws Exception {
        // Too short for detection to be sure, so automatic translation skips it.
        String text = "vamos";
        assertTrue(t.detect(text).toString(), t.detect(text).confidence < LanguageDetector.MIN_CONFIDENCE);
        assertNull(in(text));
        Translator.Incoming r = (Translator.Incoming) requested(text);
        System.out.println(r.translated);
        assertNull(r.language);
    }

    @Test public void outgoingKeepsGameNames() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<String>();
        t.translateOutgoing("anyone wanna play bedwars with me?", Language.find("es"), f::complete, e -> f.completeExceptionally(new AssertionError(e)));
        String out = f.get(10, TimeUnit.SECONDS);
        System.out.println(out);
        assertTrue(out, out.contains("BedWars"));
    }

    @Test public void outgoingPortuguese() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<String>();
        t.translateOutgoing("can u help me pls", Language.find("pt"), f::complete, e -> f.completeExceptionally(new AssertionError(e)));
        String out = f.get(10, TimeUnit.SECONDS);
        System.out.println(out);
        assertTrue(out, out.toLowerCase().contains("ajud"));
    }
}
