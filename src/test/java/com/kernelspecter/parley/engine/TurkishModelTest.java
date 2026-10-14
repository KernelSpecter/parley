package com.kernelspecter.parley.engine;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Turkish goes through mul-en; slang expansion should give it readable input. */
public class TurkishModelTest {
    @Test public void turkishThroughMultilingualModel() throws Exception {
        MarianModel m = new MarianModel(TestModels.dir("mul-en"));
        Slang slang = new Slang();
        try {
            String out = m.translate(slang.expand("kimse benimle oynamak istemiyor", "tr"), null);
            System.out.println(out);
            assertTrue(out, out.toLowerCase().contains("play"));
        } finally {
            m.close();
        }
    }
}
