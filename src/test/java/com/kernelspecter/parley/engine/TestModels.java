package com.kernelspecter.parley.engine;

import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locates downloaded model folders for tests (set PARLEY_MODELS to the folder holding es-en, ru-en, ...). */
final class TestModels {
    private TestModels() {}

    static Path dir(String pair) {
        String root = System.getProperty("parley.models", "");
        assumeTrue("PARLEY_MODELS not set", !root.isEmpty());
        Path p = Paths.get(root, pair);
        assumeTrue("missing model " + p, Files.isDirectory(p));
        return p;
    }
}
