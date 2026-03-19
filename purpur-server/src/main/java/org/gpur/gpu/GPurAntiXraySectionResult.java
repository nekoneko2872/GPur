package org.gpur.gpu;

public record GPurAntiXraySectionResult(
    int sectionIndex,
    byte[] obfuscationMask
) {
}
