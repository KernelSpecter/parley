package com.kernelspecter.parley;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import com.kernelspecter.parley.engine.Language;
import com.kernelspecter.parley.engine.ModelStore;
import com.kernelspecter.parley.engine.Translator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Parley.MODID, name = "Parley", version = Parley.VERSION,
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class Parley {
    public static final String MODID = "parley";
    public static final String VERSION = "1.1.0";
    public static final Logger LOG = LogManager.getLogger("Parley");

    /** Chat allows 100 characters per message in 1.8. */
    private static final int CHAT_LIMIT = 100;
    /** Chat keeps 100 lines by default, so older [T] buttons have scrolled away anyway. */
    private static final int CLICKABLE_LINES = 200;

    private static Parley instance;

    ParleyConfig config;
    Translator translator;

    /** Lines with a [T] button, by the id in the button's command. Only touched on the client thread. */
    private final Map<Integer, ChatLine> clickable = new LinkedHashMap<Integer, ChatLine>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, ChatLine> e) { return size() > CLICKABLE_LINES; }
    };
    private int nextId;

    static Parley get() { return instance; }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        File dir = new File(event.getModConfigurationDirectory(), MODID);
        config = new ParleyConfig(new File(dir, "parley.properties"));
        config.load();
        try {
            translator = new Translator(new File(dir, "models").toPath(), new DownloadNotices());
        } catch (Exception e) {
            LOG.error("Parley could not start", e);
            return;
        }
        applyConfig();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (translator == null) return;
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new ParleyCommand(this));
        LOG.info("Parley " + VERSION + " ready (" + config + ")");
    }

    void applyConfig() {
        translator.setAutoDownload(config.autoDownload);
        translator.setIgnored(config.ignored);
        config.save();
    }

    Language sendLanguage() {
        return config.send.isEmpty() ? null : Language.find(config.send);
    }

    /** Runs last so other mods matching chat text see the line before the [T] button is added. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || event.message == null) return; // 2 is the action bar
        final ChatLine line = ChatLine.parse(event.message.getFormattedText());
        if (line.body.length() < 2 || !ChatLine.hasLetter(line.body, line.body.length()) || isOwnMessage(line)) return;
        if (config.button) event.message = withButton(event.message, line);
        if (config.translateAll) translator.translateIncoming(line.body, result -> show(line, result));
    }

    private IChatComponent withButton(IChatComponent message, ChatLine line) {
        int id = nextId++;
        clickable.put(id, line);
        IChatComponent button = new ChatComponentText("§8[§3T§8] ");
        button.setChatStyle(new ChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tr t " + id))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("§7Translate into English"))));
        return new ChatComponentText("").appendSibling(button).appendSibling(message);
    }

    /** Called when a [T] button is clicked. */
    void translateClicked(int id) {
        final ChatLine line = clickable.get(id);
        if (line == null) {
            say("That message is too old to translate.");
            return;
        }
        translator.translateRequested(line.body, result -> show(line, result),
                error -> Minecraft.getMinecraft().addScheduledTask(() -> say(error)));
    }

    private static void show(ChatLine line, Translator.Incoming result) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            String tag = result.language == null ? "?" : result.language.tag();
            String from = result.language == null ? "Translated" : result.language.name;
            IChatComponent msg = new ChatComponentText("§8[§3" + tag + "§8] §r" + line.prefix + result.translated);
            msg.setChatStyle(new ChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText("§7" + from + " to English\n§f" + result.original))));
            print(msg);
        });
    }

    private static boolean isOwnMessage(ChatLine line) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || line.prefixPlain.isEmpty()) return false;
        String p = line.prefixPlain;
        if (p.startsWith("To ")) return true; // private message echo
        String name = mc.thePlayer.getName();
        for (String w : p.split("[^A-Za-z0-9_]+")) if (w.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Swaps the chat box for one that translates what you type, while a send language is set. */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui == null || event.gui.getClass() != GuiChat.class || sendLanguage() == null) return;
        String text = "";
        try {
            text = ReflectionHelper.getPrivateValue(GuiChat.class, (GuiChat) event.gui, "defaultInputFieldText", "field_146409_v");
        } catch (Exception e) {
            LOG.warn("Could not read chat prefill: " + e);
        }
        event.gui = new TranslatingChat(text == null ? "" : text);
    }

    /** Translates {@code text} into {@code to} and sends it as a chat message. */
    void sendTranslated(String text, Language to) {
        translator.translateOutgoing(text, to, out -> Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) return;
            String send = out.length() > CHAT_LIMIT ? out.substring(0, CHAT_LIMIT) : out;
            if (send.length() < out.length()) say("§eThe translation was longer than chat allows, so it was cut to 100 characters.");
            mc.thePlayer.sendChatMessage(send);
        }), error -> Minecraft.getMinecraft().addScheduledTask(() -> say("§c" + error + " §7(not sent)")));
    }

    static void say(String text) {
        print(new ChatComponentText("§8[§3Parley§8] §7" + text));
    }

    private static void print(IChatComponent msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(msg);
    }

    /** Human description of a model folder, e.g. "Spanish to English". */
    static String describe(String model) {
        if ("mul-en".equals(model)) return "many languages to English";
        if ("en-mul".equals(model)) return "English to many languages";
        StringBuilder in = new StringBuilder(), out = new StringBuilder();
        for (Language l : Language.all()) {
            if (model.equals(l.toEnglish)) in.append(in.length() == 0 ? "" : "/").append(l.name);
            if (model.equals(l.fromEnglish)) out.append(out.length() == 0 ? "" : "/").append(l.name);
        }
        if (in.length() > 0) return in + " to English";
        if (out.length() > 0) return "English to " + out;
        return model;
    }

    static String sizeOf(String model) {
        return "about 110 MB";
    }

    private static final class DownloadNotices implements ModelStore.Listener {
        @Override public void downloadStarted(String model) {
            later("Downloading the " + describe(model) + " model (" + sizeOf(model) + ", one time only).");
        }

        @Override public void downloadFinished(String model) {
            later("§a" + describe(model) + " is ready.");
        }

        @Override public void downloadFailed(String model, String reason) {
            LOG.warn("Download of " + model + " failed: " + reason);
            later("§cCould not download the " + describe(model) + " model: " + reason);
        }

        private long lastErrorShown;

        @Override public void translationFailed(Throwable error) {
            LOG.warn("Translation failed", error);
            long now = System.currentTimeMillis();
            if (now - lastErrorShown < 60000) return;
            lastErrorShown = now;
            later("§cA translation failed (" + error + "). Details are in the game log.");
        }

        private static void later(String text) {
            Minecraft.getMinecraft().addScheduledTask(() -> say(text));
        }
    }
}
