package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import com.logisticscraft.occlusionculling.util.Vec3d;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Optional synthetic culling workload; elapsed times are not gameplay FPS measurements. */
public final class OcclusionCacheBenchmark {
	private static volatile long sink;
	public static void main(String[] args) {
		if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("sparse|selected reach open|pillars|walls [frames]");
		String type = args[0];
		int reach = Integer.parseInt(args[1]);
		String scene = args[2];
		if (!type.equals("sparse") && !type.equals("selected")) throw new IllegalArgumentException("Use sparse or selected");
		if (!scene.equals("open") && !scene.equals("pillars") && !scene.equals("walls")) throw new IllegalArgumentException("Use open, pillars or walls");
		if (reach < 32 || reach > 512) throw new IllegalArgumentException("reach must be between 32 and 512 blocks");
		int frames = args.length > 3 ? Integer.parseInt(args[3]) : 50;
		if (frames <= 0) throw new IllegalArgumentException("frames must be positive");
		OcclusionCache cache = type.equals("sparse") ? new DirtyBlockOcclusionCache(reach) : ReachLimitedOcclusionCullingInstance.createCache(reach);
		DataProvider world = new DataProvider() {
			public boolean prepareChunk(int x, int z) { return true; }
			public boolean isOpaqueFullCube(int x, int y, int z) {
				if (y < 0) return true;
				if (scene.equals("open")) return false;
				if (scene.equals("pillars")) return y < 18 && (x & 31) < 3 && (z & 31) < 3;
				return y < 14 && ((x & 63) == 0 && (z & 63) > 12 || (z & 63) == 0 && (x & 63) > 12);
			}
		};
		OcclusionCullingInstance culling = new OcclusionCullingInstance(reach, world, cache, 0.5);
		Random random = new Random(83473);
		Vec3d[] min = new Vec3d[512], max = new Vec3d[512], camera = new Vec3d[16];
		for (int i = 0; i < min.length; i++) {
			double x = (random.nextDouble() * 2 - 1) * (reach - 16);
			double z = (random.nextDouble() * 2 - 1) * (reach - 16);
			double y = random.nextDouble() * 12 + 1;
			min[i] = new Vec3d(x, y, z);
			max[i] = new Vec3d(x + 2, y + 3, z + 5);
		}
		for (int i = 0; i < camera.length; i++) camera[i] = new Vec3d((i & 3) * 0.6, 4.6, (i >> 2) * 0.6);
		for (int i = 0; i < 5; i++) run(culling, min, max, camera, Math.min(20, frames));
		double[] samples = new double[7];
		long checksum = 0;
		for (int sample = 0; sample < samples.length; sample++) {
			long before = System.nanoTime();
			long value = run(culling, min, max, camera, frames);
			samples[sample] = (System.nanoTime() - before) / 1e6 / frames;
			checksum += value;
		}
		Arrays.sort(samples);
		long payloadKiB = cache instanceof DirtyBlockOcclusionCache ? ((DirtyBlockOcclusionCache) cache).allocatedBrickCount() : (2L * reach * 2 * reach * 2 * reach / 4096);
		System.out.printf(Locale.ROOT, "%s,%d,%s,%.6f,%.6f,%.6f,%d,%d%n", type, reach, scene, samples[3], samples[0], samples[6], checksum, payloadKiB);
	}
	private static long run(OcclusionCullingInstance culling, Vec3d[] min, Vec3d[] max, Vec3d[] camera, int frames) {
		long visible = 0;
		for (int frame = 0; frame < frames; frame++) {
			culling.resetCache();
			for (int i = 0; i < min.length; i++) {
				if (culling.isAABBVisible(min[i], max[i], camera[frame & 15])) visible++;
			}
		}
		sink = visible;
		return visible;
	}
}
