package dev.chrc.scanner;

import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public enum GemstoneType {
    RUBY("Ruby", ChatFormatting.RED, Blocks.RED_STAINED_GLASS, Blocks.RED_STAINED_GLASS_PANE),
    AMBER("Amber", ChatFormatting.GOLD, Blocks.ORANGE_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS_PANE),
    SAPPHIRE("Sapphire", ChatFormatting.AQUA, Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE),
    JADE("Jade", ChatFormatting.GREEN, Blocks.LIME_STAINED_GLASS, Blocks.LIME_STAINED_GLASS_PANE),
    AMETHYST("Amethyst", ChatFormatting.DARK_PURPLE, Blocks.PURPLE_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS_PANE),
    TOPAZ("Topaz", ChatFormatting.YELLOW, Blocks.YELLOW_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS_PANE),
    JASPER("Jasper", ChatFormatting.LIGHT_PURPLE, Blocks.PINK_STAINED_GLASS, Blocks.PINK_STAINED_GLASS_PANE);

    private final String displayName;
    private final ChatFormatting color;
    private final Block fullBlock;
    private final Block paneBlock;

    GemstoneType(String displayName, ChatFormatting color, Block fullBlock, Block paneBlock) {
        this.displayName = displayName;
        this.color = color;
        this.fullBlock = fullBlock;
        this.paneBlock = paneBlock;
    }

    public String displayName() {
        return displayName;
    }

    public ChatFormatting color() {
        return color;
    }

    public boolean matches(BlockState state) {
        return state.is(fullBlock) || state.is(paneBlock);
    }
}
