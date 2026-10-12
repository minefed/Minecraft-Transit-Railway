package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Optional, server-only BlueMap integration; no BlueMap or Minecraft client classes are linked. */
public final class BlueMapLiveBridge {

	private static final String API = "de.bluecolored.bluemap.api.BlueMapAPI";
	private static final String WEB_APP = "de.bluecolored.bluemap.api.WebApp";
	private static final String SCRIPT = "mtr-live/mtr-live.js";
	private static final BlueMapLiveBridge INSTANCE = new BlueMapLiveBridge();
	private volatile Object enabledApi;
	private volatile Exporter exporter;
	private boolean registered;

	private BlueMapLiveBridge() {
	}

	/** Register before BlueMap's server-start callback creates its web settings. */
	public static void register() {
		if (INSTANCE.registered || !Boolean.parseBoolean(System.getProperty("mtr.bluemapLive.enabled", "true"))) {
			return;
		}
		try {
			final Class<?> api = Class.forName(API);
			final Consumer<Object> enable = INSTANCE::enable;
			final Consumer<Object> disable = INSTANCE::disable;
			api.getMethod("onEnable", Consumer.class).invoke(null, enable);
			api.getMethod("onDisable", Consumer.class).invoke(null, disable);
			INSTANCE.registered = true;
		} catch (ClassNotFoundException ignored) {
			// MTR works normally when BlueMap is not installed.
		} catch (ReflectiveOperationException | LinkageError e) {
			Init.LOGGER.warn("[MTR BlueMap] Could not register optional integration", e);
		}
	}

	public static void start(Main main, MinecraftServer server) {
		stop();
		if (!INSTANCE.registered) {
			return;
		}
		try {
			final Map<Object, String> worlds = new LinkedHashMap<>();
			MinecraftServerHelper.iterateWorlds(server, world -> worlds.put(world.data, Init.getWorldId(new World(world.data))));
			final Exporter exporter = new Exporter(main, worlds, server.getRunDirectory().toPath());
			synchronized (INSTANCE) {
				INSTANCE.exporter = exporter;
				exporter.api = INSTANCE.enabledApi;
			}
			exporter.start();
		} catch (ReflectiveOperationException | RuntimeException e) {
			Init.LOGGER.warn("[MTR BlueMap] Live export unavailable for this Core version", e);
		}
	}

	public static void stop() {
		final Exporter exporter;
		synchronized (INSTANCE) {
			exporter = INSTANCE.exporter;
			INSTANCE.exporter = null;
		}
		if (exporter != null) {
			exporter.close();
		}
	}

	private synchronized void enable(Object api) {
		try {
			final Object webApp = invoke(API, api, "getWebApp");
			Class.forName(WEB_APP).getMethod("registerScript", String.class).invoke(webApp, SCRIPT);
			enabledApi = api;
			final Exporter current = exporter;
			if (current != null) {
				current.api = api;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			Init.LOGGER.warn("[MTR BlueMap] Could not register web overlay", e);
		}
	}

	private synchronized void disable(Object api) {
		if (enabledApi == api) {
			enabledApi = null;
			final Exporter current = exporter;
			if (current != null) {
				current.api = null;
				final Session session = current.session;
				if (session != null) {
					session.active = false;
				}
			}
		}
	}

	private static Object invoke(String type, Object instance, String method) throws ReflectiveOperationException {
		return Class.forName(type).getMethod(method).invoke(instance);
	}

	static void atomicWrite(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static JsonObject index() {
		final JsonObject result = new JsonObject();
		result.addProperty("schema", 1);
		result.add("maps", new JsonObject());
		result.addProperty("catalog", "mtr-live/catalog.json");
		return result;
	}

	private static final class Exporter {
		private final BlueMapSnapshot snapshot = new BlueMapSnapshot();
		private final List<Simulator> simulators;
		private final Map<Object, String> worlds;
		private final Path catalog;
		private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "MTR-BlueMap-export");
			thread.setDaemon(true);
			return thread;
		});
		private volatile Object api;
		private volatile Session session;
		private volatile boolean closed;
		private long lastError;

		private Exporter(Main main, Map<Object, String> worlds, Path runDirectory) throws IllegalAccessException {
			this.simulators = snapshot.simulators(main);
			this.worlds = worlds;
			catalog = runDirectory.resolve("config/mtr/bluemap-vehicles.json");
		}

		private void start() {
			io.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
		}

		private void tick() {
			if (closed) {
				return;
			}
			try {
				if (session != null && (session.api != api || !session.active)) {
					clear(session);
					session = null;
				}
				if (session == null && api != null) {
					session = connect(api);
				}
				final Session current = session;
				if (current == null || !current.active || closed) {
					return;
				}
				final long now = System.currentTimeMillis();
				for (WorldExport world : current.worlds) {
					final JsonObject vehicles = world.vehicles.getAndSet(null);
					final JsonObject infrastructure = world.infrastructure.getAndSet(null);
					if (vehicles != null) {
						atomicWrite(current.root.resolve(world.prefix + "-vehicles.json"), vehicles.toString());
					}
					if (infrastructure != null) {
						atomicWrite(current.root.resolve(world.prefix + "-rails.json"), infrastructure.toString());
					}
					if (world.pending.compareAndSet(false, true)) {
						final boolean updateInfrastructure = now >= world.nextInfrastructure;
						world.simulator.run(() -> {
							try {
								if (!current.active || closed) {
									return;
								}
								final JsonObject vehicleData = snapshot.vehicles(world.simulator, System.currentTimeMillis());
								final JsonObject infrastructureData = updateInfrastructure ? BlueMapSnapshot.infrastructure(world.simulator, System.currentTimeMillis()) : null;
								if (current.active && !closed) {
									world.vehicles.set(vehicleData);
									if (infrastructureData != null) {
										world.infrastructure.set(infrastructureData);
										world.nextInfrastructure = System.currentTimeMillis() + 30000;
									}
								}
							} catch (Exception e) {
								if (world.failed.compareAndSet(false, true)) {
									Init.LOGGER.warn("[MTR BlueMap] Snapshot failed for {}", world.simulator.dimension, e);
								}
							} finally {
								world.pending.set(false);
							}
						});
					}
				}
				if (now >= current.nextCatalog) {
					writeCatalog(current.root);
					current.nextCatalog = now + 30000;
				}
			} catch (Exception e) {
				if (System.currentTimeMillis() - lastError > 30000) {
					lastError = System.currentTimeMillis();
					Init.LOGGER.warn("[MTR BlueMap] Live export failed; will retry", e);
				}
			}
		}

		private Session connect(Object api) throws ReflectiveOperationException, IOException {
			final Object webApp = invoke(API, api, "getWebApp");
			final Path root = ((Path) invoke(WEB_APP, webApp, "getWebRoot")).resolve("mtr-live");
			final Session result = new Session(api, root);
			final JsonObject index = index();
			final Method getWorld = Class.forName(API).getMethod("getWorld", Object.class);
			for (Map.Entry<Object, String> world : worlds.entrySet()) {
				final Optional<?> blueMapWorld = (Optional<?>) getWorld.invoke(api, world.getKey());
				if (!blueMapWorld.isPresent()) {
					continue;
				}
				for (Simulator simulator : simulators) {
					if (!simulator.dimension.equals(world.getValue())) {
						continue;
					}
					final WorldExport export = new WorldExport(simulator);
					result.worlds.add(export);
					for (Object map : (Collection<?>) invoke("de.bluecolored.bluemap.api.BlueMapWorld", blueMapWorld.get(), "getMaps")) {
						final String mapId = (String) invoke("de.bluecolored.bluemap.api.BlueMapMap", map, "getId");
						final JsonObject entry = new JsonObject();
						entry.addProperty("worldId", simulator.dimension);
						entry.addProperty("vehicles", "mtr-live/" + export.prefix + "-vehicles.json");
						entry.addProperty("rails", "mtr-live/" + export.prefix + "-rails.json");
						index.getAsJsonObject("maps").add(mapId, entry);
					}
				}
			}
			clear(result); // Reset old files before publishing the new map index.
			try (InputStream script = BlueMapLiveBridge.class.getResourceAsStream("/assets/mtr/bluemap/mtr-live.js")) {
				if (script == null) {
					throw new IOException("Bundled BlueMap web script is missing");
				}
				atomicWrite(root.resolve("mtr-live.js"), new String(script.readAllBytes(), StandardCharsets.UTF_8));
			}
			writeCatalog(root);
			atomicWrite(root.resolve("index.json"), index.toString());
			result.active = !closed && this.api == api;
			Init.LOGGER.info("[MTR BlueMap] Exporting {} worlds to {}", result.worlds.size(), root);
			return result;
		}

		private void writeCatalog(Path root) throws IOException {
			String content = "{\"schema\":1,\"vehicles\":{}}";
			if (Files.isRegularFile(catalog)) {
				try {
					final String candidate = Files.readString(catalog, StandardCharsets.UTF_8);
					if (!JsonParser.parseString(candidate).isJsonObject()) {
						throw new IOException("Vehicle catalog must be a JSON object");
					}
					content = candidate;
				} catch (IOException | RuntimeException e) {
					Init.LOGGER.warn("[MTR BlueMap] Ignoring invalid optional vehicle catalog {}", catalog, e);
				}
			}
			atomicWrite(root.resolve("catalog.json"), content);
		}

		private void clear(Session session) throws IOException {
			session.active = false;
			atomicWrite(session.root.resolve("index.json"), index().toString());
			for (WorldExport world : session.worlds) {
				world.vehicles.set(null);
				world.infrastructure.set(null);
				atomicWrite(session.root.resolve(world.prefix + "-vehicles.json"), BlueMapSnapshot.empty("vehicles", System.currentTimeMillis()).toString());
				final JsonObject infrastructure = BlueMapSnapshot.empty("rails", System.currentTimeMillis());
				infrastructure.add("routes", new org.mtr.libraries.com.google.gson.JsonArray());
				infrastructure.add("stations", new org.mtr.libraries.com.google.gson.JsonArray());
				atomicWrite(session.root.resolve(world.prefix + "-rails.json"), infrastructure.toString());
			}
		}

		private void close() {
			closed = true;
			final Session current = session;
			if (current != null) {
				current.active = false;
			}
			io.execute(() -> {
				try {
					if (session != null) {
						clear(session);
					}
				} catch (IOException e) {
					Init.LOGGER.warn("[MTR BlueMap] Could not clear live export on shutdown", e);
				}
			});
			io.shutdown();
			try {
				if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
					io.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				io.shutdownNow();
			}
		}
	}

	private static final class Session {
		private final Object api;
		private final Path root;
		private final List<WorldExport> worlds = new ArrayList<>();
		private volatile boolean active;
		private long nextCatalog;

		private Session(Object api, Path root) {
			this.api = api;
			this.root = root;
		}
	}

	private static final class WorldExport {
		private final Simulator simulator;
		private final String prefix;
		private final AtomicBoolean pending = new AtomicBoolean();
		private final AtomicBoolean failed = new AtomicBoolean();
		private final AtomicReference<JsonObject> vehicles = new AtomicReference<>();
		private final AtomicReference<JsonObject> infrastructure = new AtomicReference<>();
		private volatile long nextInfrastructure;

		private WorldExport(Simulator simulator) {
			this.simulator = simulator;
			prefix = UUID.nameUUIDFromBytes(simulator.dimension.getBytes(StandardCharsets.UTF_8)).toString();
		}
	}
}
