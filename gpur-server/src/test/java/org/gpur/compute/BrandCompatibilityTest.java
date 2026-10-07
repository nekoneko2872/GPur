package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class BrandCompatibilityTest {
    @Test void gpurDeclaresPaperAndPurpurCompatibility() {
        var info = new ServerBuildInfoImpl(Key.key("gpur:gpur"), "GPur", "26.2", "26.2",
            java.util.OptionalInt.empty(), java.time.Instant.EPOCH, java.util.Optional.empty(), java.util.Optional.empty());
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID));
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PURPUR_ID));
        assertTrue(info.isBrandCompatible(Key.key("gpur:gpur")));
        assertFalse(info.isBrandCompatible(Key.key("unknown:server")));
    }
}
