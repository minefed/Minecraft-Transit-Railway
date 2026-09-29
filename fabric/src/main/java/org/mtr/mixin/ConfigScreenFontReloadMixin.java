package org.mtr.mixin;

import net.minecraft.client.MinecraftClient;
import org.mtr.mod.client.MtrFontState;
import org.mtr.mod.config.Config;
import org.mtr.mod.screen.ConfigScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The MTR font files are only loaded while "Use MTR Font" is enabled, so reload resources when the option changes.
 */
@Mixin(value = ConfigScreen.class, remap = false)
public abstract class ConfigScreenFontReloadMixin {

	@Inject(method = "onClose2", at = @At("TAIL"))
	private void mtr$reloadFontIfNeeded(CallbackInfo callback) {
		if (Config.getClient().getUseMTRFont() != MtrFontState.loaded) {
			MinecraftClient.getInstance().reloadResources();
		}
	}
}
