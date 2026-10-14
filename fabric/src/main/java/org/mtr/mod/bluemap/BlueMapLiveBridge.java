package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** BlueMap is a presentation client. Transport capture and API publication also run without it. */
public final class BlueMapLiveBridge {
	private static final String API = "de.bluecolored.bluemap.api.BlueMapAPI";
	private static final String WEB_APP = "de.bluecolored.bluemap.api.WebApp";
	private static final String SCRIPT = "mtr-live/mtr-live.js";
	private static final BlueMapLiveBridge INSTANCE = new BlueMapLiveBridge();
	private volatile Object enabledApi;
	private TransitExporter exporter;
	private Session session;
	private boolean registered;

	private BlueMapLiveBridge() { }

	public static void register() {
		if (INSTANCE.registered || !Boolean.parseBoolean(System.getProperty("mtr.bluemapLive.enabled", "true"))) return;
		try {
			final Class<?> api = Class.forName(API);
			api.getMethod("onEnable", Consumer.class).invoke(null, (Consumer<Object>) INSTANCE::enable);
			api.getMethod("onDisable", Consumer.class).invoke(null, (Consumer<Object>) INSTANCE::disable);
			INSTANCE.registered = true;
		} catch (ClassNotFoundException ignored) {
			// API publication does not require BlueMap.
		} catch (ReflectiveOperationException | LinkageError e) {
			Init.LOGGER.warn("[MTR BlueMap] Could not register optional integration", e);
		}
	}

	public static void start(Main main, MinecraftServer server) {
		stop();
		try {
			final TransitApiConfig config = loadConfig(server.getRunDirectory().toPath().resolve("config/mtr/transit-api.json"));
			if (!config.enabled() && !INSTANCE.registered) return;
			final Map<Object, String> worlds = new LinkedHashMap<>();
			MinecraftServerHelper.iterateWorlds(server, world -> worlds.put(world.data, Init.getWorldId(new World(world.data))));
			final TransitExporter exporter = new TransitExporter(main, server.getRunDirectory().toPath(), config, () -> INSTANCE.maintainOverlay(config, worlds));
			INSTANCE.exporter = exporter;
			exporter.start();
		} catch (IOException | ReflectiveOperationException | RuntimeException e) {
			Init.LOGGER.warn("[MTR transit] Could not start API publication; check transit-api.json and its token environment variable", e);
			// Remove the previous file feed even when new configuration is invalid.
			try { INSTANCE.maintainOverlay(TransitApiConfig.parse("{}", ignored -> null), Collections.emptyMap()); }
			catch (IOException | RuntimeException ignored) { }
		}
	}

	private static TransitApiConfig loadConfig(Path path) throws IOException {
		try {
			return TransitApiConfig.load(path);
		} catch (IOException e) {
			Init.LOGGER.warn("[MTR transit] API publication disabled: invalid transit-api.json or missing token environment variable");
			// Keep the UI lifecycle alive so a later BlueMap enable also clears old feeds.
			return TransitApiConfig.parse("{}", ignored -> null);
		}
	}

	public static void stop() {
		final TransitExporter exporter = INSTANCE.exporter;
		INSTANCE.exporter = null;
		if (exporter != null) exporter.close();
		INSTANCE.clearOverlay();
	}

	private synchronized void enable(Object api) {
		try {
			final Object webApp = invoke(API, api, "getWebApp");
			Class.forName(WEB_APP).getMethod("registerScript", String.class).invoke(webApp, SCRIPT);
			clearOverlay();
			final Path root = ((Path) invoke(WEB_APP, webApp, "getWebRoot")).resolve("mtr-live");
			// Even if capture failed to start, a later BlueMap enable cannot revive the old feed.
			atomicWrite(root.resolve("index.json"), index("").toString());
			clearPublicSnapshots(root, Collections.emptyList(), "api-enabling", System.currentTimeMillis());
			atomicWrite(root.resolve("catalog.json"), TransitExporter.emptyCatalog().toString());
			enabledApi = api;
		} catch (ReflectiveOperationException | IOException | RuntimeException e) {
			Init.LOGGER.warn("[MTR BlueMap] Could not register API web overlay", e);
		}
	}

	private void disable(Object api) {
		if (enabledApi == api) enabledApi = null;
	}

	private synchronized void maintainOverlay(TransitApiConfig config, Map<Object, String> worlds) {
		final Object api = enabledApi;
		try {
			if (session != null && session.api != api) clearOverlay();
			if (api == null || session != null) return;
			final Object webApp = invoke(API, api, "getWebApp");
			final Path root = ((Path) invoke(WEB_APP, webApp, "getWebRoot")).resolve("mtr-live");
			final JsonObject index = index(config.publicApiBase);
			final Method getWorld = Class.forName(API).getMethod("getWorld", Object.class);
			if (config.enabled()) for (Map.Entry<Object, String> world : worlds.entrySet()) {
				final Optional<?> blueMapWorld = (Optional<?>) getWorld.invoke(api, world.getKey());
				if (!blueMapWorld.isPresent()) continue;
				for (Object map : (Collection<?>) invoke("de.bluecolored.bluemap.api.BlueMapWorld", blueMapWorld.get(), "getMaps")) {
					final JsonObject entry = new JsonObject();
					entry.addProperty("worldId", world.getValue());
					entry.addProperty("id", UUID.nameUUIDFromBytes(world.getValue().getBytes(StandardCharsets.UTF_8)).toString());
					index.getAsJsonObject("maps").add((String) invoke("de.bluecolored.bluemap.api.BlueMapMap", map, "getId"), entry);
				}
			}
			// Clear legacy feeds before publishing API bootstrap; old clients must fail closed.
			clearPublicSnapshots(root, Collections.emptyList(), "api-migration", System.currentTimeMillis());
			atomicWrite(root.resolve("catalog.json"), TransitExporter.emptyCatalog().toString());
			try (InputStream script = BlueMapLiveBridge.class.getResourceAsStream("/assets/mtr/bluemap/mtr-live.js")) {
				if (script == null) throw new IOException("Missing MTR API web script");
				atomicWrite(root.resolve("mtr-live.js"), new String(script.readAllBytes(), StandardCharsets.UTF_8));
			}
			atomicWrite(root.resolve("index.json"), index.toString());
			session = new Session(api, root);
		} catch (ReflectiveOperationException | IOException | RuntimeException e) {
			throw new IllegalStateException("Could not install BlueMap API bootstrap", e);
		}
	}

	private synchronized void clearOverlay() {
		if (session == null) return;
		try {
			atomicWrite(session.root.resolve("index.json"), index("").toString());
			clearPublicSnapshots(session.root, Collections.emptyList(), "api-disabled", System.currentTimeMillis());
		} catch (IOException e) {
			Init.LOGGER.warn("[MTR BlueMap] Could not clear API bootstrap", e);
		} finally { session = null; }
	}

	private static JsonObject index(String apiBase) {
		final JsonObject index = new JsonObject();
		index.addProperty("schema", 2);
		index.addProperty("apiBase", apiBase);
		index.add("maps", new JsonObject());
		return index;
	}

	private static Object invoke(String type, Object instance, String method) throws ReflectiveOperationException {
		return Class.forName(type).getMethod(method).invoke(instance);
	}

	private static final class Session {
		private final Object api;
		private final Path root;
		private Session(Object api, Path root) { this.api = api; this.root = root; }
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

	/** Same-directory rename publishes a complete file; absence is checked atomically by move. */
	static void createInitial(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		if (Files.exists(file)) {
			return;
		}
		final Path temporary = Files.createTempFile(file.getParent(), ".mtr-initial-", ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file); // Deliberately no REPLACE_EXISTING, including concurrent edits.
			} catch (FileAlreadyExistsException ignored) {
				// An administrator or another initializer created it first.
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** Revoke every owned file, including removed worlds; one failed file must not retain the others. */
	static void clearPublicSnapshots(Path root, Collection<String> currentPrefixes, String revision, long timestamp) throws IOException {
		final java.util.Set<String> prefixes = new java.util.LinkedHashSet<>(currentPrefixes);
		IOException failure = null;
		if (Files.isDirectory(root)) {
			try (java.util.stream.Stream<Path> files = Files.list(root)) {
				files.map(path -> path.getFileName().toString())
						.filter(name -> name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-(vehicles|rails)\\.json(?:\\.tmp)?"))
						.forEach(name -> prefixes.add(name.substring(0, 36)));
			} catch (IOException e) {
				failure = e;
			}
		}
		for (String prefix : prefixes) {
			final JsonObject vehicles = BlueMapSnapshot.empty("vehicles", timestamp);
			vehicles.add("arrivals", new JsonArray());
			vehicles.addProperty("arrivalsTimestamp", 0);
			vehicles.addProperty("publicationRevision", revision);
			failure = writeOrCollect(root.resolve(prefix + "-vehicles.json"), vehicles.toString(), failure);
			final JsonObject infrastructure = BlueMapSnapshot.empty("rails", timestamp);
			infrastructure.add("routes", new JsonArray());
			infrastructure.add("stations", new JsonArray());
			infrastructure.addProperty("publicationRevision", revision);
			failure = writeOrCollect(root.resolve(prefix + "-rails.json"), infrastructure.toString(), failure);
		}
		if (failure != null) throw failure;
	}

	private static IOException writeOrCollect(Path file, String contents, IOException failure) {
		try {
			atomicWrite(file, contents);
		} catch (IOException e) {
			if (failure == null) return e;
			failure.addSuppressed(e);
		}
		return failure;
	}

}
