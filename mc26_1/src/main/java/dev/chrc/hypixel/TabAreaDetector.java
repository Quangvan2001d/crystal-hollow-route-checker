package dev.chrc.hypixel;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/** Minimal Hypixel tab-list area detector used by CHRC automation helpers. */
public final class TabAreaDetector {
    private static final String CRYSTAL_HOLLOWS_LINE = "Area: Crystal Hollows";

    private TabAreaDetector() {}

    public static boolean isCrystalHollows(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) return false;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                for (PlayerInfo info : new ArrayList<>(client.getConnection().getListedOnlinePlayers())) {
                    String line;
                    if (info.getTabListDisplayName() != null) {
                        line = info.getTabListDisplayName().getString();
                    } else if (info.getProfile() != null) {
                        line = info.getProfile().name();
                    } else {
                        continue;
                    }

                    if (line.replace('\u00A0', ' ').contains(CRYSTAL_HOLLOWS_LINE)) {
                        return true;
                    }
                }
                return false;
            } catch (ConcurrentModificationException ignored) {
                Thread.yield();
            }
        }
        return false;
    }
}
