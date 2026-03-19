package org.gpur.preload;

public record GPurPreloadStatusSnapshot(
    int activeAnchors,
    int activeWorlds,
    long sharedSelections,
    long sharedPriorityHits,
    long retainedPriorityHits,
    long sendBurstSends,
    long turboSendBurstSends
) {
}
