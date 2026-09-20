package org.mtr.mod.render;

import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.vertex.Vertex;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Transfers CPU mesh bounds to GPU model constructors without retaining GPU resources. */
public final class ModelBoundsRegistry {

	private static final Map<VertexArray, Float> RADII = new WeakHashMap<>();

	private ModelBoundsRegistry() {
	}

	public static synchronized void capture(RawModel model, List<VertexArray> uploadedParts) {
		final RadiusBuilder bounds = new RadiusBuilder();
		model.iterateRawMeshList(mesh -> {
			for (Vertex vertex : mesh.vertices) {
				bounds.add(vertex.position.getX(), vertex.position.getY(), vertex.position.getZ());
			}
		});
		final float radius = bounds.radius();
		for (VertexArray part : uploadedParts) {
			RADII.put(part, radius);
		}
	}

	public static synchronized float radius(List<VertexArray> uploadedParts) {
		float radius = 0;
		for (VertexArray part : uploadedParts) {
			// Third-party models that bypass RawModel.upload remain visible.
			radius = Math.max(radius, RADII.getOrDefault(part, Float.POSITIVE_INFINITY));
		}
		return radius;
	}

	static final class RadiusBuilder {

		private double squared;

		void add(float x, float y, float z) {
			squared = Math.max(squared, (double) x * x + (double) y * y + (double) z * z);
		}

		float radius() {
			// Round outwards, including matrix rounding at the edge of the screen.
			return Double.isFinite(squared) ? Math.nextUp((float) Math.sqrt(squared) + 0.001F) : Float.POSITIVE_INFINITY;
		}
	}
}
