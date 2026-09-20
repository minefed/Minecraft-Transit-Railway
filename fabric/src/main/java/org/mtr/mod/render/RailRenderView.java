package org.mtr.mod.render;

/** The existing horizontal range / near radius / camera hemisphere test, without per-piece vectors. */
final class RailRenderView {

	private final double x, y, z, distanceSquared;
	private final float sinYaw, cosYaw, sinPitch, cosPitch;

	RailRenderView(double x, double y, double z, int renderDistance, float sinYaw, float cosYaw, float sinPitch, float cosPitch) {
		this.x = x;
		this.y = y;
		this.z = z;
		distanceSquared = (double) renderDistance * renderDistance;
		this.sinYaw = sinYaw;
		this.cosYaw = cosYaw;
		this.sinPitch = sinPitch;
		this.cosPitch = cosPitch;
	}

	boolean isVisible(double startX, double startY, double startZ) {
		final double dx = startX - x;
		final double dz = startZ - z;
		final double distance = dx * dx + dz * dz;
		return distance <= distanceSquared && (distance < 32 * 32 ||
				(dz * cosYaw - dx * sinYaw) * cosPitch - (startY - y) * sinPitch > 0);
	}
}
