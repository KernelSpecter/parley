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
    public boolean incoming = true;
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
        incoming = Boolean.parseBoolean(p.getProperty("incoming", "true"));
        autoDownload = Boolean.parseBoolean(p.getProperty("autoDownload", "true"));
        send = p.getProperty("send", "").trim();
        ignored.clear();
        for (String s : p.getProperty("ignore", "").split(",")) if (!s.trim().isEmpty()) ignored.add(s.trim());
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("incoming", String.valueOf(incoming));
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
        return "incoming=" + incoming + " send=" + send + " auto=" + autoDownload + " ignore=" + Arrays.toString(ignored.toArray());
    }
}
