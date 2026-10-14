package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SlangTest {
    @Test public void expandsEnglishShorthandAndKeepsPunctuation() throws Exception {
        assertEquals("I don't know, are you on right now?", new Slang().expand("idk, r u on rn?", "en"));
    }

    @Test public void gameNamesGetBrandCasing() throws Exception {
        assertEquals("anyone want to play BedWars", new Slang().expand("anyone wanna play bedwars", "en").replace("want to", "want to"));
    }

    @Test public void perLanguageLists() throws Exception {
        Slang s = new Slang();
        assertEquals("porque no vienes", s.expand("xq no vienes", "es"));
        assertEquals("você vem hoje?", s.expand("vc vem hj?", "pt"));
    }
}
