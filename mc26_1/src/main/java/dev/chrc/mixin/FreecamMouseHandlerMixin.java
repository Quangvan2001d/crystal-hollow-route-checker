package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class FreecamMouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void chrc$blockPhysicalClicksInFreecam(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (FreecamManager.isEnabled() && Minecraft.getInstance().screen == null) {
            ci.cancel();
        }
    }
}
