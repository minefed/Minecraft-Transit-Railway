package org.mtr.mod.render;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.function.Consumer;

/**
 * Tracks when model textures (vehicle, rail and object textures drawn by the optimized renderer) were last drawn,
 * so that textures unused for a while can be released.
 * <p>
 * Vanilla keeps every loaded texture until the next resource reload, so every vehicle texture that was ever seen
 * (up to 64 MiB each) stays in VRAM. The loader-specific texture manager hook releases only plain resource
 * textures, which are loaded again from the resource packs the next time they are drawn (a short hitch).
 * All methods run on the render thread.
 */
public final class ModelTextureEvictor {

	private static final long IDLE_MILLIS = 5 * 60 * 1000;
	private static final long CHECK_INTERVAL_MILLIS = 30 * 1000;
	private static final Object2LongOpenHashMap<Object> LAST_USE = new Object2LongOpenHashMap<>();
	private static long currentMillis = System.currentTimeMillis();
	private static long lastCheckMillis = currentMillis;

	private ModelTextureEvictor() {
	}

	/**
	 * @param textureId the game's texture identifier, bound by an optimized-renderer batch
	 */
	public static void markUsed(Object textureId) {
		LAST_USE.put(textureId, currentMillis);
	}

	/**
	 * Called every texture manager tick. Passes each texture that was not drawn for {@link #IDLE_MILLIS} to the consumer once.
	 */
	public static void collectIdle(Consumer<Object> idleTextureConsumer) {
		currentMillis = System.currentTimeMillis();
		if (currentMillis - lastCheckMillis < CHECK_INTERVAL_MILLIS) {
			return;
		}
		lastCheckMillis = currentMillis;
		LAST_USE.object2LongEntrySet().removeIf(entry -> {
			if (currentMillis - entry.getLongValue() <= IDLE_MILLIS) {
				return false;
			}
			idleTextureConsumer.accept(entry.getKey());
			return true;
		});
	}
}
