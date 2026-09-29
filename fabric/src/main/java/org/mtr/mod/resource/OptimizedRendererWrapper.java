package org.mtr.mod.resource;

import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mixin.OptimizedRendererAccessor;
import org.mtr.mod.data.IGui;

import javax.annotation.Nullable;

public final class OptimizedRendererWrapper implements IGui {

	@Nullable
	private final OptimizedRenderer optimizedRenderer;
	/**
	 * Shader sources only change when resources reload, so compile them once per reload instead of for every model build.
	 */
	private static int resourceGeneration;
	private int shaderGeneration = -1;

	public OptimizedRendererWrapper() {
		this.optimizedRenderer = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedRenderer() : null;
	}

	/**
	 * Marks the compiled shaders as stale after a resource reload.
	 */
	public static void invalidateShaders() {
		resourceGeneration++;
	}

	public void beginReload() {
		if (optimizedRenderer != null) {
			if (shaderGeneration != resourceGeneration || !((OptimizedRendererAccessor) (Object) optimizedRenderer).mtr$getShaderManager().isReady()) {
				// Same as before: recompile the shaders and capture the GL state (also retried while compilation keeps failing)
				optimizedRenderer.beginReload();
				shaderGeneration = resourceGeneration;
			} else {
				// The shaders compiled for this resource generation are still current; only capture the GL state
				GlStateTracker.capture();
			}
		}
	}

	public void finishReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.finishReload();
		}
	}

	public void queue(OptimizedModelWrapper optimizedModel, GraphicsHolder graphicsHolder, int light) {
		if (optimizedRenderer != null && optimizedModel.optimizedModel != null) {
			optimizedRenderer.queue(optimizedModel.optimizedModel, graphicsHolder, ARGB_WHITE, light);
		}
	}

	public void render(boolean renderTranslucent) {
		if (optimizedRenderer != null) {
			optimizedRenderer.render(renderTranslucent);
		}
	}
}
