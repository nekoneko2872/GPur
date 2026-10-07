package org.gpur;

import com.destroystokyo.paper.util.VersionFetcher;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Report this fork's build; a GPur build number cannot be compared with Purpur's release API. */
public final class GPurVersionFetcher implements VersionFetcher {
    @Override
    public long getCacheTime() { return 720_000; }

    @Override
    public Component getVersionMessage() {
        ServerBuildInfo build = ServerBuildInfo.buildInfo();
        return Component.text("GPur " + build.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE), NamedTextColor.GREEN)
            .append(Component.newline())
            .append(Component.text("Source and releases: github.com/nekoneko2872/GPur", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.openUrl("https://github.com/nekoneko2872/GPur")));
    }
}
