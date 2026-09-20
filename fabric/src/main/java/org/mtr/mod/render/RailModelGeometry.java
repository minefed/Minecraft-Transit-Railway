package org.mtr.mod.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.mtr.core.data.RailMath;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.List;

/** Static placements only: lighting, visibility and GPU models must not be cached here. */
final class RailModelGeometry {

	final Segment[] segments;

	RailModelGeometry(RailMath railMath, double interval, double yOffset, boolean flip) {
		final List<Segment> placements = new ArrayList<>();
		railMath.render((x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) ->
				placements.add(new Segment(x1, y1, z1, x3, y2, z3, yOffset, flip)), interval, 0, 0);
		segments = placements.toArray(new Segment[0]);
	}

	static final class Segment {

		final double startX, startY, startZ;
		final double x, y, z;
		final float yaw, pitch, rollDegrees;
		private final Matrix4f rotation;
		private BlockPos lightPosition;

		Segment(double x1, double y1, double z1, double x3, double y2, double z3, double yOffset, boolean flip) {
			startX = x1;
			startY = y1;
			startZ = z1;
			x = (x1 + x3) / 2;
			y = (y1 + y2) / 2 + yOffset;
			z = (z1 + z3) / 2;
			final double dx = x3 - x1;
			final double dz = z3 - z1;
			yaw = (float) (Math.PI / 2 - Math.atan2(dz, dx) + (flip ? Math.PI : 0));
			pitch = (float) (Math.PI - Math.atan2(y2 - y1, Math.sqrt(dx * dx + dz * dz)) * (flip ? -1 : 1));
			rollDegrees = (float) ((x1 * z1) % 10) / 100;
			// Match GraphicsHolder / RotationAxis, including its float degree conversion.
			rotation = new Matrix4f().rotate(new Quaternionf().rotationY(yaw))
					.rotate(new Quaternionf().rotationX(pitch))
					.rotate(new Quaternionf().rotationZ(rollDegrees * ((float) Math.PI / 180F)));
		}

		BlockPos lightPosition() {
			if (lightPosition == null) {
				lightPosition = Init.newBlockPos(startX, startY + 0.1, startZ);
			}
			return lightPosition;
		}

		void transform(Matrix4f target, Matrix4f base, double offsetX, double offsetY, double offsetZ) {
			// Subtract in double precision before converting to the camera-relative float matrix.
			target.set(base).translate((float) (x - offsetX), (float) (y - offsetY), (float) (z - offsetZ)).mul(rotation);
		}
	}
}
