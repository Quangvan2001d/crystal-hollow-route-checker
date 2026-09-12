package dev.chrc.notifications;

import dev.chrc.config.ChrcConfig;
import dev.chrc.config.ConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Handles the small set of Hypixel mining chat messages CHRC optionally filters.
 * Parsing is intentionally based on the plain visible text so it is independent
 * of Hypixel's chat colours/formatting.
 */
public final class MiningMessageManager {
    private static final String PRISTINE_TOKEN = "pristine!";
    private static final String PICKAXE_USED_PREFIX = "you used your ";
    private static final String PICKAXE_USED_SUFFIX = " pickaxe ability!";
    private static final String AVAILABLE_SUFFIX = " is now available!";
    private static final String EXPIRED_PREFIX = "your ";
    private static final String EXPIRED_SUFFIX = " has expired!";

    private MiningMessageManager() {}

    /**
     * Called immediately before a chat line is added to Minecraft's ChatComponent.
     * It may show a center-screen title and returns true when the chat line itself
     * should be suppressed.
     */
    public static boolean handleAndShouldHide(Component message) {
        if (message == null) return false;

        String plain = normalize(message.getString());
        if (plain.isEmpty()) return false;
        String lower = plain.toLowerCase(Locale.ROOT);
        ChrcConfig config = ConfigManager.get();

        boolean pristine = lower.contains(PRISTINE_TOKEN);
        boolean pickaxeUsed = lower.startsWith(PICKAXE_USED_PREFIX)
                && lower.endsWith(PICKAXE_USED_SUFFIX);
        boolean available = lower.endsWith(AVAILABLE_SUFFIX);
        boolean expired = lower.startsWith(EXPIRED_PREFIX)
                && lower.endsWith(EXPIRED_SUFFIX);

        if (config.showAbilityStatusTitles) {
            if (available) {
                showCenterTitle(Component.literal("Your ability is now available")
                        .withStyle(ChatFormatting.GREEN));
            } else if (expired) {
                showCenterTitle(Component.literal("Your ability has expired")
                        .withStyle(ChatFormatting.RED));
            }
        }

        if (pristine && config.hidePristineMessages) return true;
        if (pickaxeUsed && config.hidePickaxeAbilityUsedMessages) return true;
        return expired && config.hideAbilityExpiredMessages;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static void showCenterTitle(Component title) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        client.execute(() -> {
            if (client.gui == null) return;
            // 10 tick fade-in, 50 tick hold, 10 tick fade-out.
            client.gui.setTimes(10, 50, 10);
            client.gui.setSubtitle(Component.empty());
            client.gui.setTitle(title);
        });
    }
}
