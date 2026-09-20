package org.mtr.mod.render;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class RailModelGeometryTest {

	@Test
	public void cachedPlacementsMatchStreamingRendererForStraightCurvedAndSlopedRails() {
		for (Rail rail : new Rail[]{rail(0, 0, 0), rail(32, 16, 64), rail(-64, -20, -64), rail(29999000, 30, -29999000)}) {
			for (double interval : new double[]{0.5, 1, 3.7}) {
				for (boolean flip : new boolean[]{false, true}) {
					final double yOffset = 0.375;
					final RailModelGeometry geometry = new RailModelGeometry(rail.railMath, interval, yOffset, flip);
					assertTrue(geometry.segments.length > 0);
					final int[] index = {0};
					rail.railMath.render((x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {
						final RailModelGeometry.Segment segment = geometry.segments[index[0]++];
						assertEquals(x1, segment.startX);
						assertEquals(y1, segment.startY);
						assertEquals(z1, segment.startZ);
						final double cameraX = x1 + 12.123456789;
						final double cameraY = y1 - 3.75;
						final double cameraZ = z1 + 7.987654321;
						final MatrixStack expected = new MatrixStack();
						expected.multiply(RotationAxis.POSITIVE_Y.rotation(0.7F));
						expected.multiply(RotationAxis.POSITIVE_X.rotation(-0.2F));
						expected.scale(0.9F, 1.1F, 1.05F);
						final Matrix4f base = new Matrix4f(expected.peek().getPositionMatrix());
						final double dx = x3 - x1;
						final double dz = z3 - z1;
						expected.translate((x1 + x3) / 2 - cameraX, (y1 + y2) / 2 + yOffset - cameraY, (z1 + z3) / 2 - cameraZ);
						expected.multiply(RotationAxis.POSITIVE_Y.rotation((float) (Math.PI / 2 - Math.atan2(dz, dx) + (flip ? Math.PI : 0))));
						expected.multiply(RotationAxis.POSITIVE_X.rotation((float) (Math.PI - Math.atan2(y2 - y1, Math.sqrt(dx * dx + dz * dz)) * (flip ? -1 : 1))));
						expected.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) ((x1 * z1) % 10) / 100));
						final Matrix4f actual = new Matrix4f();
						segment.transform(actual, base, cameraX, cameraY, cameraZ);
						assertTrue(expected.peek().getPositionMatrix().equals(actual, 0.00001F), "Cached rotation must match the original MatrixStack operations");
						final Matrix4f repeated = new Matrix4f();
						segment.transform(repeated, base, cameraX, cameraY, cameraZ);
						assertEquals(actual, repeated, "Rendering must not mutate the cached rotation or base matrix");
					}, interval, 0, 0);
					assertEquals(index[0], geometry.segments.length);
				}
			}
		}
	}

	@Test
	public void visibilityMatchesOriginalVectorsAcrossCameraAnglesAndDistanceBoundaries() {
		final Random random = new Random(731);
		for (int i = 0; i < 10000; i++) {
			final float yaw = (float) (random.nextDouble() * Math.PI * 2 - Math.PI);
			final float pitch = (float) (random.nextDouble() * Math.PI - Math.PI / 2);
			final double x = random.nextDouble() * 500 - 250;
			final double y = random.nextDouble() * 100 - 50;
			final double z = random.nextDouble() * 500 - 250;
			final RailRenderView view = new RailRenderView(0, 0, 0, 192, MathHelper.sin(yaw), MathHelper.cos(yaw), MathHelper.sin(pitch), MathHelper.cos(pitch));
			final double distance = Math.sqrt(x * x + z * z);
			final boolean expected = distance <= 192 && (distance < 32 || new Vec3d(x, y, z).rotateY(yaw).rotateX(pitch).z > 0);
			assertEquals(expected, view.isVisible(x, y, z));
		}
		final RailRenderView view = new RailRenderView(0, 0, 0, 192, 0, 1, 0, 1);
		assertTrue(view.isVisible(0, -10000, -31.999));
		assertFalse(view.isVisible(0, 0, -32));
		assertTrue(view.isVisible(0, 10000, 192));
		assertFalse(view.isVisible(0, 0, 192.001));
	}

	@Test
	public void changedGeometryIntervalOffsetAndDirectionNeverReuseStalePlacements() {
		final RailGeometryCache cache = new RailGeometryCache(10000, 100);
		final RailMath math = rail(0, 0, 0).railMath;
		final RailModelGeometry first = cache.get(math, 1, 0, false);
		assertNotNull(first);
		assertSame(first, cache.get(math, 1, 0, false));
		assertNotSame(first, cache.get(math, 0.5, 0, false));
		assertNotSame(first, cache.get(math, 1, 0.1, false));
		assertNotSame(first, cache.get(math, 1, 0, true));
		assertNotSame(first, cache.get(rail(0, 0, 0).railMath, 1, 0, false));
		cache.clear();
		assertEquals(0, cache.segmentCount());
		assertNotSame(first, cache.get(math, 1, 0, false));
	}

	@Test
	public void cacheEvictsLeastRecentlyUsedAndBoundsMemoryByPlacementsAndEntries() {
		final RailMath math = rail(0, 0, 0).railMath;
		final RailGeometryCache cache = new RailGeometryCache(400, 2);
		final RailModelGeometry first = cache.get(math, 1, 0, false);
		final RailModelGeometry second = cache.get(math, 1, 1, false);
		assertSame(first, cache.get(math, 1, 0, false));
		cache.get(math, 1, 2, false);
		assertSame(first, cache.get(math, 1, 0, false));
		assertNotSame(second, cache.get(math, 1, 1, false));
		assertTrue(cache.segmentCount() <= 400);
		for (int i = 0; i < 100; i++) {
			cache.get(rail(i, 0, i).railMath, 0.5, i, false);
			assertTrue(cache.segmentCount() <= 400);
		}
		assertNull(cache.get(math, 0.00001, 0, false));
		assertNull(cache.get(math, 0, 0, false));
		assertNull(cache.get(math, Double.NaN, 0, false));
	}

	private static Rail rail(int x, int y, int z) {
		return Rail.newRail(new Position(x, 64, z), Angle.E, new Position(x + 100, 64 + y, z == 0 ? z : z + 64), z == 0 ? Angle.W : Angle.N,
				Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
	}
}
