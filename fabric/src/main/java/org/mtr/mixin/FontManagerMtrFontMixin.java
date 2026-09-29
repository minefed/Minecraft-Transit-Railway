package org.mtr.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.font.FontLoader;
import net.minecraft.client.font.FontManager;
import net.minecraft.client.font.TrueTypeFontLoader;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.mtr.mod.Init;
import org.mtr.mod.client.MtrFontState;
import org.mtr.mod.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code mtr:mtr} font is only drawn when "Use MTR Font" is enabled, but vanilla loads its TrueType files
 * (a 28 MB CJK font) into native memory on every resource reload. Skip them while the option is off; the config
 * screen reloads resources when the option changes. The remaining providers are the vanilla fonts it references.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMtrFontMixin {

	private static final Identifier MTR_FONT = new Identifier(Init.MOD_ID, "mtr");

	@Inject(method = "loadFontProviders", at = @At("RETURN"), cancellable = true)
	private static void mtr$skipDisabledFont(List<Resource> fontResources, Identifier id, CallbackInfoReturnable<List<Pair<?, FontLoader>>> callback) {
		if (!MTR_FONT.equals(id)) {
			return;
		}
		MtrFontState.loaded = Config.getClient().getUseMTRFont();
		if (!MtrFontState.loaded) {
			callback.setReturnValue(callback.getReturnValue().stream().filter(pair -> !(pair.getSecond() instanceof TrueTypeFontLoader)).collect(Collectors.toList()));
		}
	}
}
