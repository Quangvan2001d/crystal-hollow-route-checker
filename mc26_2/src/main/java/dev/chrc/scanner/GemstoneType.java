package dev.chrc.scanner;

import net.minecraft.ChatFormatting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public enum GemstoneType {
    RUBY("Ruby", ChatFormatting.RED, Blocks.STAINED_GLASS.red(), Blocks.STAINED_GLASS_PANE.red()),
    AMBER("Amber", ChatFormatting.GOLD, Blocks.STAINED_GLASS.orange(), Blocks.STAINED_GLASS_PANE.orange()),
    SAPPHIRE("Sapphire", ChatFormatting.AQUA, Blocks.STAINED_GLASS.lightBlue(), Blocks.STAINED_GLASS_PANE.lightBlue()),
    JADE("Jade", ChatFormatting.GREEN, Blocks.STAINED_GLASS.lime(), Blocks.STAINED_GLASS_PANE.lime()),
    AMETHYST("Amethyst", ChatFormatting.DARK_PURPLE, Blocks.STAINED_GLASS.purple(), Blocks.STAINED_GLASS_PANE.purple()),
    TOPAZ("Topaz", ChatFormatting.YELLOW, Blocks.STAINED_GLASS.yellow(), Blocks.STAINED_GLASS_PANE.yellow()),
    JASPER("Jasper", ChatFormatting.LIGHT_PURPLE, Blocks.STAINED_GLASS.pink(), Blocks.STAINED_GLASS_PANE.pink());

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
