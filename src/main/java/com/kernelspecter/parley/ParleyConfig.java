package com.kernelspecter.parley;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** Settings changed through /tr, kept in config/parley/parley.properties. */
public final class ParleyConfig {
    /** Translate every incoming line automatically, not just the ones clicked. */
    public boolean translateAll = false;
    /** Put a clickable [T] in front of chat lines. */
    public boolean button = true;
    public boolean autoDownload = true;
    /** Language code everything you type is translated into, or empty when off. */
    public String send = "";
    public final Set<String> ignored = new LinkedHashSet<String>();

    private final File file;

    public ParleyConfig(File file) {
        this.file = file;
    }

    public void load() {
        if (!file.isFile()) return;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            Parley.LOG.warn("Could not read " + file + ": " + e);
            return;
        }
        // 1.0 wrote incoming=true for everyone; it is not carried over, so clicking is the new default.
        translateAll = Boolean.parseBoolean(p.getProperty("translateAll", "false"));
        button = Boolean.parseBoolean(p.getProperty("button", "true"));
        autoDownload = Boolean.parseBoolean(p.getProperty("autoDownload", "true"));
        send = p.getProperty("send", "").trim();
        ignored.clear();
        for (String s : p.getProperty("ignore", "").split(",")) if (!s.trim().isEmpty()) ignored.add(s.trim());
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("translateAll", String.valueOf(translateAll));
        p.setProperty("button", String.valueOf(button));
        p.setProperty("autoDownload", String.valueOf(autoDownload));
        p.setProperty("send", send);
        StringBuilder ig = new StringBuilder();
        for (String s : ignored) ig.append(ig.length() == 0 ? "" : ",").append(s);
        p.setProperty("ignore", ig.toString());
        file.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, "Parley settings. Change them in game with /tr.");
        } catch (IOException e) {
            Parley.LOG.warn("Could not write " + file + ": " + e);
        }
    }

    @Override public String toString() {
        return "translateAll=" + translateAll + " button=" + button + " send=" + send + " auto=" + autoDownload + " ignore=" + Arrays.toString(ignored.toArray());
    }
}
