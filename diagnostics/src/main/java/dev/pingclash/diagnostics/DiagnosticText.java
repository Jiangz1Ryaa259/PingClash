package dev.pingclash.diagnostics;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Host-supplied resources; diagnostic evidence remains code-based, not text-based. */
public final class DiagnosticText {
    private static volatile Function<String, String> resolver = key -> key;
    private static volatile String version = "development";
    private DiagnosticText() {}
    public static void install(Function<String, String> value) { resolver = Objects.requireNonNull(value); }
    public static String text(String key) { return Objects.requireNonNull(resolver.apply(key)); }
    public static String format(String key, Object... args) { return String.format(Locale.ROOT, text(key), args); }
    public static String version() { return version; }
    public static void setVersion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9.+-]{1,64}")) throw new IllegalArgumentException("invalid_version");
        version = value;
    }
}
