package com.kernelspecter.parley;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChatLineTest {
    @Test public void rankAndName() {
        ChatLine l = ChatLine.parse("\u00a77[\u00a7aVIP\u00a77] \u00a7aSteve\u00a7f: \u00a77hola como estas");
        assertEquals("[VIP] Steve: ", l.prefixPlain);
        assertEquals("hola como estas", l.body);
        assertEquals("\u00a77[\u00a7aVIP\u00a77] \u00a7aSteve\u00a7f: \u00a77", l.prefix);
    }

    @Test public void arrowSeparator() {
        ChatLine l = ChatLine.parse("Steve \u00bb que onda");
        assertEquals("Steve \u00bb ", l.prefixPlain);
        assertEquals("que onda", l.body);
    }

    @Test public void colonInsideMessageIsNotTheSeparator() {
        ChatLine l = ChatLine.parse("Steve: mira esto: hola");
        assertEquals("Steve: ", l.prefixPlain);
        assertEquals("mira esto: hola", l.body);
    }

    @Test public void systemLineHasNoPrefix() {
        ChatLine l = ChatLine.parse("\u00a7eBienvenido al servidor");
        assertEquals("", l.prefix);
        assertEquals("Bienvenido al servidor", l.body);
    }

    @Test public void guildAndPartyLines() {
        ChatLine g = ChatLine.parse("§2Guild > §b[MVP§c+§b] Steve §e[Officer]§f: alguien quiere jugar");
        assertEquals("Guild > [MVP+] Steve [Officer]: ", g.prefixPlain);
        assertEquals("alguien quiere jugar", g.body);
        ChatLine p = ChatLine.parse("§9Party §8> §aSteve§f: oi gente");
        assertEquals("oi gente", p.body);
    }
}
