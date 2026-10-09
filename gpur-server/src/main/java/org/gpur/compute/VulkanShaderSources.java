package org.gpur.compute;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The exact source compiled by shaderc, including every referenced helper. */
final class VulkanShaderSources {
    static final String LEGACY = "shaders/gpur/exact.comp";
    static final String NOISE = "shaders/gpur/vanilla-noise.comp";
    static final String AQUIFER = "shaders/gpur/vanilla-aquifer.comp";
    static final List<String> ENTRY_POINTS = List.of(LEGACY, NOISE, AQUIFER);

    private VulkanShaderSources() {}

    static String source(String entryPoint) {
        if (!ENTRY_POINTS.contains(entryPoint)) throw new IllegalArgumentException("Unknown GPU kernel: " + entryPoint);
        String source = read(entryPoint);
        if (entryPoint.equals(NOISE)) {
            source = include(source, "// GPUR_NOISE_HELPERS", "shaders/gpur/vanilla-noise.glsl");
        } else if (entryPoint.equals(AQUIFER)) {
            source = include(source, "// GPUR_AQUIFER_HELPERS", "shaders/gpur/vanilla-aquifer.glsl");
        }
        return source;
    }

    private static String include(String source, String marker, String resource) {
        int first = source.indexOf(marker);
        if (first < 0 || source.indexOf(marker, first + marker.length()) >= 0) {
            throw new IllegalStateException("Shader must contain exactly one include marker: " + marker);
        }
        return source.substring(0, first) + read(resource) + source.substring(first + marker.length());
    }

    private static String read(String resource) {
        try (InputStream stream = VulkanShaderSources.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing GPU shader resource: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read GPU shader resource: " + resource, failure);
        }
    }
}
