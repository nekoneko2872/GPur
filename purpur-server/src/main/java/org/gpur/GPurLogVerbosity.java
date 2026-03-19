package org.gpur;

public enum GPurLogVerbosity {
    OFF,
    SUMMARY,
    VERBOSE;

    public static GPurLogVerbosity fromString(final String rawValue) {
        if (rawValue == null) {
            return SUMMARY;
        }

        return switch (rawValue.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "false", "none" -> OFF;
            case "verbose", "debug" -> VERBOSE;
            default -> SUMMARY;
        };
    }
}
