package com.kernelspecter.parley;

import com.kernelspecter.parley.engine.Language;
import net.minecraft.client.gui.GuiChat;

/** The chat box while a send language is set: plain messages go out translated, commands untouched. */
final class TranslatingChat extends GuiChat {
    TranslatingChat(String text) {
        super(text);
    }

    @Override
    public void sendChatMessage(String msg, boolean addToChat) {
        Parley mod = Parley.get();
        Language to = mod == null ? null : mod.sendLanguage();
        String trimmed = msg.trim();
        if (to == null || trimmed.isEmpty() || trimmed.startsWith("/")) {
            super.sendChatMessage(msg, addToChat);
            return;
        }
        if (addToChat) mc.ingameGUI.getChatGUI().addToSentMessages(msg);
        mod.sendTranslated(trimmed, to);
    }
}
