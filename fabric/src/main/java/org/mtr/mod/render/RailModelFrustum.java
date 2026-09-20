package org.mtr.mod.render;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Uses the same projection, model-view and base matrices as the optimized vertex shader. */
final class RailModelFrustum {

	private final FrustumIntersection frustum;
	private final double offsetX, offsetY, offsetZ;

	RailModelFrustum(Matrix4fc projection, Matrix4fc modelView, Matrix4fc base, double offsetX, double offsetY, double offsetZ) {
		this.offsetX = offsetX;
		this.offsetY = offsetY;
		this.offsetZ = offsetZ;
		final Matrix4f clip = new Matrix4f(projection).mul(modelView).mul(base);
		final Vector4f plane = new Vector4f();
		boolean valid = true;
		for (int i = 0; i < 6; i++) {
			clip.frustumPlane(i, plane);
			valid &= Float.isFinite(plane.x) && Float.isFinite(plane.y) && Float.isFinite(plane.z) && Float.isFinite(plane.w);
		}
		// Unusual/degenerate projections must fail open instead of hiding tracks.
		frustum = valid ? new FrustumIntersection(clip) : null;
	}

	boolean isVisible(double x, double y, double z, float radius) {
		return frustum == null || !Float.isFinite(radius) || radius < 0 ||
				frustum.testSphere((float) (x - offsetX), (float) (y - offsetY), (float) (z - offsetZ), radius);
	}
}
