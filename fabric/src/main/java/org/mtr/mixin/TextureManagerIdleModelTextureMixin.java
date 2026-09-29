package org.mtr.mixin;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.mtr.mod.render.ModelTextureEvictor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases model textures that the optimized renderer has not drawn for a while (see {@link ModelTextureEvictor}).
 */
@Mixin(TextureManager.class)
public abstract class TextureManagerIdleModelTextureMixin {

	@Shadow
	public abstract AbstractTexture getOrDefault(Identifier id, AbstractTexture fallback);

	@Shadow
	public abstract void destroyTexture(Identifier id);

	@Inject(method = "tick", at = @At("HEAD"))
	private void mtr$releaseIdleModelTextures(CallbackInfo callback) {
		ModelTextureEvictor.collectIdle(textureId -> {
			if (textureId instanceof Identifier) {
				final AbstractTexture texture = getOrDefault((Identifier) textureId, null);
				// Exactly a resource texture: reloadable from resources, unlike dynamic, atlas or skin textures
				if (texture != null && texture.getClass() == ResourceTexture.class) {
					destroyTexture((Identifier) textureId);
				}
			}
		});
	}
}
