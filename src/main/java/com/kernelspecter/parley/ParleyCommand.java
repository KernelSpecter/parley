package com.kernelspecter.parley;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.kernelspecter.parley.engine.Language;
import com.kernelspecter.parley.engine.ModelStore;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

/** /tr: everything Parley does is driven from here. */
final class ParleyCommand extends CommandBase {
    private static final List<String> SUBCOMMANDS = Arrays.asList("in", "out", "say", "langs", "ignore", "download", "auto");

    private final Parley mod;

    ParleyCommand(Parley mod) {
        this.mod = mod;
    }

    @Override public String getCommandName() { return "tr"; }

    @Override public List<String> getCommandAliases() { return Collections.singletonList("parley"); }

    @Override public String getCommandUsage(ICommandSender sender) { return "/tr [in|out|say|langs|ignore|download|auto]"; }

    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        ParleyConfig c = mod.config;
        switch (sub) {
            case "in":
                if (args.length == 2 && onOff(args[1]) != null) {
                    c.incoming = onOff(args[1]);
                    mod.applyConfig();
                }
                Parley.say("Incoming chat translation is " + (c.incoming ? "§aon" : "§coff") + "§7.");
                break;
            case "out":
                if (args.length != 2) { Parley.say("Usage: /tr out <language> or /tr out off"); break; }
                if (onOff(args[1]) == Boolean.FALSE) {
                    c.send = "";
                    mod.applyConfig();
                    Parley.say("Your messages are sent as you type them.");
                    break;
                }
                Language to = sendable(args[1]);
                if (to == null) break;
                c.send = to.code;
                mod.applyConfig();
                Parley.say("Everything you type in chat now goes out in §f" + to.name + "§7. Commands are left alone. Stop with /tr out off.");
                prepare(to.fromEnglish);
                break;
            case "say":
                if (args.length < 3) { Parley.say("Usage: /tr say <language> <message>"); break; }
                Language lang = sendable(args[1]);
                if (lang == null) break;
                mod.sendTranslated(buildString(args, 2), lang);
                break;
            case "langs":
                listLanguages();
                break;
            case "ignore":
                if (args.length != 2) { Parley.say("Usage: /tr ignore <language>. Ignored: " + (c.ignored.isEmpty() ? "none" : c.ignored)); break; }
                Language ig = find(args[1]);
                if (ig == null) break;
                if (c.ignored.remove(ig.code)) Parley.say(ig.name + " messages will be translated again.");
                else {
                    c.ignored.add(ig.code);
                    Parley.say(ig.name + " messages will be left as they are.");
                }
                mod.applyConfig();
                break;
            case "download":
                if (args.length != 2) { Parley.say("Usage: /tr download <language>"); break; }
                Language dl = find(args[1]);
                if (dl == null) break;
                prepare(dl.toEnglish);
                if (dl.canSend()) prepare(dl.fromEnglish);
                break;
            case "auto":
                if (args.length == 2 && onOff(args[1]) != null) {
                    c.autoDownload = onOff(args[1]);
                    mod.applyConfig();
                }
                Parley.say("Downloading models for new languages automatically is " + (c.autoDownload ? "§aon" : "§coff") + "§7.");
                break;
            default:
                help();
        }
    }

    private void help() {
        Language send = mod.sendLanguage();
        Parley.say("§fParley " + Parley.VERSION + "§7, offline chat translation.");
        Parley.say("Incoming: " + (mod.config.incoming ? "§aon" : "§coff") + "§7. Typing in: §f"
                + (send == null ? "as written" : send.name) + "§7.");
        Parley.say("§f/tr in on|off§7  translate chat you receive into English");
        Parley.say("§f/tr out <language>|off§7  translate everything you type");
        Parley.say("§f/tr say <language> <message>§7  translate and send one message");
        Parley.say("§f/tr langs§7  languages and downloaded models");
        Parley.say("§f/tr ignore <language>§7  stop or resume translating a language");
        Parley.say("§f/tr download <language>§7  get a language's models now");
        Parley.say("§f/tr auto on|off§7  download models when a new language shows up");
    }

    private void listLanguages() {
        ModelStore models = mod.translator.models();
        Parley.say("Languages (§aready§7, §8not downloaded§7, * = rougher sending):");
        StringBuilder line = new StringBuilder();
        for (Language l : Language.all()) {
            boolean ready = models.isDownloaded(l.toEnglish);
            String entry = (ready ? "§a" : "§8") + l.code + " " + l.name + (l.sendIsRough() ? "*" : "")
                    + (l.canSend() ? "" : " (read only)") + (mod.config.ignored.contains(l.code) ? " (ignored)" : "");
            if (line.length() > 0) line.append("§7, ");
            line.append(entry);
            if (line.length() > 180) {
                Parley.say(line.toString());
                line.setLength(0);
            }
        }
        if (line.length() > 0) Parley.say(line.toString());
    }

    private void prepare(String model) {
        ModelStore models = mod.translator.models();
        if (models.isDownloaded(model)) Parley.say("§a" + Parley.describe(model) + " is ready.");
        else if (models.isDownloading(model)) Parley.say(Parley.describe(model) + " is already downloading.");
        else models.download(model);
    }

    private static Language find(String key) {
        Language l = Language.find(key);
        if (l == null) Parley.say("§cUnknown language \"" + key + "\". See /tr langs.");
        return l;
    }

    private static Language sendable(String key) {
        Language l = find(key);
        if (l != null && !l.canSend()) {
            Parley.say("§cParley can read " + l.name + " but can't write it yet.");
            return null;
        }
        return l;
    }

    private static Boolean onOff(String s) {
        if ("on".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("off".equalsIgnoreCase(s)) return Boolean.FALSE;
        return null;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("in") || sub.equals("auto")) return getListOfStringsMatchingLastWord(args, "on", "off");
            if (sub.equals("out") || sub.equals("say") || sub.equals("ignore") || sub.equals("download")) {
                List<String> codes = new ArrayList<String>();
                if (sub.equals("out")) codes.add("off");
                for (Language l : Language.all()) if (!(sub.equals("out") || sub.equals("say")) || l.canSend()) codes.add(l.code);
                return getListOfStringsMatchingLastWord(args, codes);
            }
        }
        return null;
    }
}
