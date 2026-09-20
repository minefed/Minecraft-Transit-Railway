package org.mtr.mod.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class RailModelFrustumTest {

	@Test
	public void rejectsOffscreenPiecesButKeepsModelsCrossingScreenAndNearPlane() {
		final RailModelFrustum frustum = view(new Matrix4f().perspective((float) Math.PI / 2, 1, 0.1F, 192));
		assertTrue(frustum.isVisible(0, 0, -10, 1));
		assertFalse(frustum.isVisible(30, 0, -10, 1));
		assertFalse(frustum.isVisible(0, 0, 10, 1));
		assertFalse(frustum.isVisible(0, 0, -250, 1));
		assertTrue(frustum.isVisible(10.5, 0, -10, 1), "A model partly on screen must not pop out");
		assertTrue(frustum.isVisible(0, 0, 0, 1), "A model crossing the near plane must remain visible");
		assertTrue(frustum.isVisible(30, 0, -10, 40), "Large custom models cannot use a default-track point test");
	}

	@Test
	public void everyOnscreenVertexSurvivesAcrossViewsSlopesFlipsAndWorldBorderCoordinates() {
		final Random random = new Random(90221);
		int onScreen = 0;
		for (int i = 0; i < 20000; i++) {
			final double offsetX = i % 2 == 0 ? 29999000.123456 : -100.456789;
			final double offsetY = 71.75;
			final double offsetZ = i % 2 == 0 ? -29999000.987654 : 321.123456;
			final Matrix4f projection = new Matrix4f().perspective(0.4F + random.nextFloat() * 1.8F, 0.6F + random.nextFloat() * 2, 0.05F, 256);
			final Matrix4f modelView = new Matrix4f().rotateY(random.nextFloat() * 6).rotateX(random.nextFloat() * 2 - 1);
			final Matrix4f base = new Matrix4f().translate(0.25F, -0.1F, 0.75F).rotateZ(0.05F).scale(0.9F, 1.1F, 1.05F);
			final RailModelFrustum frustum = new RailModelFrustum(projection, modelView, base, offsetX, offsetY, offsetZ);
			final double x = offsetX + random.nextDouble() * 300 - 150;
			final double y = offsetY + random.nextDouble() * 100 - 50;
			final double z = offsetZ + random.nextDouble() * 300 - 150;
			final float vx = random.nextFloat() * 20 - 10;
			final float vy = random.nextFloat() * 20 - 10;
			final float vz = random.nextFloat() * 20 - 10;
			final ModelBoundsRegistry.RadiusBuilder bounds = new ModelBoundsRegistry.RadiusBuilder();
			bounds.add(vx, vy, vz);
			final Matrix4f model = new Matrix4f(base).translate((float) (x - offsetX), (float) (y - offsetY), (float) (z - offsetZ))
					.rotateY(random.nextFloat() * 6).rotateX(random.nextFloat() * 6).rotateZ(random.nextFloat() * 0.02F);
			final Vector4f clip = new Matrix4f(projection).mul(modelView).mul(model).transform(new Vector4f(vx, vy, vz, 1));
			if (clip.w > 0 && Math.abs(clip.x) <= clip.w && Math.abs(clip.y) <= clip.w && Math.abs(clip.z) <= clip.w) {
				onScreen++;
				assertTrue(frustum.isVisible(x, y, z, bounds.radius()), "A vertex inside the actual shader clip volume must not be culled");
			}
		}
		assertTrue(onScreen > 500, "Exercise enough on-screen geometry, not only empty views");
	}

	@Test
	public void invalidBoundsAndDegenerateProjectionsFailOpen() {
		final RailModelFrustum frustum = view(new Matrix4f().perspective(1, 1.5F, 0.1F, 192));
		assertTrue(frustum.isVisible(10000, 10000, 10000, Float.POSITIVE_INFINITY));
		assertTrue(frustum.isVisible(10000, 10000, 10000, Float.NaN));
		assertTrue(view(new Matrix4f().zero()).isVisible(10000, 10000, 10000, 1));
		assertTrue(view(new Matrix4f().m00(Float.NaN)).isVisible(10000, 10000, 10000, 1));
	}

	@Test
	public void radiusEnclosesEveryVertexAndInvalidMeshesFailOpen() {
		final ModelBoundsRegistry.RadiusBuilder bounds = new ModelBoundsRegistry.RadiusBuilder();
		bounds.add(-3, 4, 0);
		bounds.add(1, 2, -12);
		assertTrue(bounds.radius() > Math.sqrt(149));
		assertTrue(bounds.radius() < Math.sqrt(149) + 0.002);
		bounds.add(Float.NaN, 0, 0);
		assertEquals(Float.POSITIVE_INFINITY, bounds.radius());
	}

	private static RailModelFrustum view(Matrix4f projection) {
		return new RailModelFrustum(projection, new Matrix4f(), new Matrix4f(), 0, 0, 0);
	}
}
