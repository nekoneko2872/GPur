package org.gpur.gpu;

public record GPurAntiXraySectionRequest(
    int sectionIndex,
    int[] presetBlockStateBits,
    byte[] stateFlags,
    byte[] paddedTransparency
) {
}
