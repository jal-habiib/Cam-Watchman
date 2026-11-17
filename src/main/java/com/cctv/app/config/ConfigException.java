package com.cctv.app.config;

import java.util.List;

/** Thrown when {@code cameras.properties} cannot be used; carries every problem found. */
public final class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("invalid camera configuration:\n - " + String.join("\n - ", problems));
        this.problems = List.copyOf(problems);
    }

    public ConfigException(String problem) {
        this(List.of(problem));
    }

    /** All validation problems, in the order they were detected. */
    public List<String> problems() {
        return problems;
    }
}
