package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonArray;
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
import java.nio.file.FileAlreadyExistsException;
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
		private final Path routeCatalogFile;
		private final Path publicRoutesFile;
		private final List<CatalogExport> catalogExports = new ArrayList<>();
		private final Map<String, JsonObject> routeCatalogs = new LinkedHashMap<>();
		private final String processId = UUID.randomUUID().toString();
		private volatile Publication publication = new Publication(0, BlueMapRoutePolicy.CLOSED);
		private long nextPolicyRead;
		private String lastPolicyError = "";
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
			routeCatalogFile = runDirectory.resolve("config/mtr/bluemap-routes.json");
			publicRoutesFile = runDirectory.resolve("config/mtr/bluemap-public-routes.json");
			for (Simulator simulator : simulators) {
				catalogExports.add(new CatalogExport(simulator));
				final JsonObject world = new JsonObject();
				world.addProperty("worldId", simulator.dimension);
				world.add("routes", new JsonArray());
				routeCatalogs.put(simulator.dimension, world);
			}
		}

		private void start() {
			io.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
		}

		private void tick() {
			if (closed) {
				return;
			}
			try {
				final long now = System.currentTimeMillis();
				try {
					createInitial(publicRoutesFile, BlueMapRoutePolicy.INITIAL);
					createInitial(routeCatalogFile, catalogDocument(now).toString());
				} catch (IOException e) {
					logError("Could not create private route configuration", e);
				}
				if (now >= nextPolicyRead) {
					readPolicy();
					nextPolicyRead = now + 2000;
				}
				try {
					updateRouteCatalogs(now);
				} catch (RuntimeException e) {
					logError("Could not capture private route catalog", e);
				}
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
				if (current.needsReset) {
					writeEmpty(current);
					current.needsReset = false;
				}
				for (WorldExport world : current.worlds) {
					final SnapshotPair latest = world.mailbox.take();
					if (latest != null && latest.revision == publication.revision) {
						atomicWrite(current.root.resolve(world.prefix + "-vehicles.json"), latest.vehicles.toString());
						if (latest.infrastructure != null) {
							atomicWrite(current.root.resolve(world.prefix + "-rails.json"), latest.infrastructure.toString());
							world.nextInfrastructure = now + 30000;
						}
					}
					if (world.pending.compareAndSet(false, true)) {
						final boolean updateInfrastructure = now >= world.nextInfrastructure;
						final Publication policy = publication;
						final BlueMapRoutePolicy.WorldSelection selection = policy.policy.world(world.simulator.dimension);
						world.simulator.run(() -> {
							try {
								if (!current.active || closed || publication != policy) {
									return;
								}
								final long captureTime = System.currentTimeMillis();
								final JsonObject vehicleData = snapshot.vehicles(world.simulator, captureTime, selection);
								final JsonObject infrastructureData = updateInfrastructure ? BlueMapSnapshot.infrastructure(world.simulator, captureTime, selection) : null;
								if (world.arrivals == null || world.arrivalsRevision != policy.revision || captureTime >= world.nextArrivals) {
									try {
										world.arrivals = BlueMapSnapshot.arrivals(world.simulator, captureTime, selection);
										world.arrivalsFailed.set(false);
									} catch (Exception e) {
										world.arrivals = BlueMapSnapshot.arrivalData(new JsonArray(), 0);
										if (world.arrivalsFailed.compareAndSet(false, true)) {
											Init.LOGGER.warn("[MTR BlueMap] Arrivals unavailable for {}", world.simulator.dimension, e);
										}
									}
									world.arrivalsRevision = policy.revision;
									world.nextArrivals = captureTime + 2000;
								}
								vehicleData.add("arrivals", world.arrivals.get("arrivals"));
								vehicleData.add("arrivalsTimestamp", world.arrivals.get("arrivalsTimestamp"));
								stamp(vehicleData, policy);
								if (infrastructureData != null) {
									stamp(infrastructureData, policy);
								}
								if (current.active && !closed && publication == policy) {
									world.mailbox.publish(policy.revision, new SnapshotPair(policy.revision, vehicleData, infrastructureData));
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
				logError("Live export failed; will retry", e);
			}
		}

		private void logError(String message, Exception error) {
			if (System.currentTimeMillis() - lastError > 30000) {
				lastError = System.currentTimeMillis();
				Init.LOGGER.warn("[MTR BlueMap] " + message, error);
			}
		}

		private JsonObject catalogDocument(long timestamp) {
			final JsonObject result = new JsonObject();
			result.addProperty("schema", 1);
			result.addProperty("updatedAt", timestamp);
			final JsonArray catalogs = new JsonArray();
			routeCatalogs.values().forEach(catalogs::add);
			result.add("worlds", catalogs);
			return result;
		}

		/** Catalog data never enters a public snapshot or the BlueMap web directory. */
		private void updateRouteCatalogs(long now) {
			boolean changed = false;
			for (CatalogExport world : catalogExports) {
				final JsonObject latest = world.latest.getAndSet(null);
				if (latest != null) {
					routeCatalogs.put(world.simulator.dimension, latest);
					world.received = true;
					changed = true;
				}
				if (now >= world.nextCapture && world.pending.compareAndSet(false, true)) {
					world.nextCapture = now + 30000;
					world.simulator.run(() -> {
						try {
							if (!closed) {
								world.latest.set(BlueMapSnapshot.routeCatalog(world.simulator, world.simulator.dimension));
							}
						} catch (Exception e) {
							Init.LOGGER.warn("[MTR BlueMap] Route catalog unavailable for {}", world.simulator.dimension, e);
						} finally {
							world.pending.set(false);
						}
					});
				}
			}
			if (changed) {
				nextPolicyRead = 0;
				try {
					atomicWrite(routeCatalogFile, catalogDocument(now).toString());
				} catch (IOException e) {
					// An unwritable private catalog must not prevent policy revocation.
					logError("Could not update private route catalog", e);
				}
			}
		}

		private void readPolicy() {
			BlueMapRoutePolicy next = BlueMapRoutePolicy.CLOSED;
			try {
				if (Files.size(publicRoutesFile) > 2 * 1024 * 1024) {
					throw new IOException("Public route settings exceed 2 MiB");
				}
				final String contents = Files.readString(publicRoutesFile, StandardCharsets.UTF_8);
				// Wait for every simulator's first catalog so valid IDs are not mistaken for unknown IDs.
				if (catalogExports.stream().allMatch(world -> world.received)) {
					next = BlueMapRoutePolicy.parse(contents, routeCatalogs);
				}
				lastPolicyError = "";
			} catch (IOException | RuntimeException e) {
				final String message = e.getMessage();
				if (!java.util.Objects.equals(lastPolicyError, message)) {
					Init.LOGGER.warn("[MTR BlueMap] No routes published: {}", message);
					lastPolicyError = message;
				}
			}
			if (!next.fingerprint().equals(publication.policy.fingerprint())) {
				advancePublication(next, session);
			}
		}

		private void advancePublication(BlueMapRoutePolicy policy, Session current) {
			publication = new Publication(publication.revision + 1, policy);
			if (current != null) {
				current.needsReset = true;
				for (WorldExport world : current.worlds) {
					world.mailbox.reset(publication.revision);
					world.nextInfrastructure = 0;
				}
			}
		}

		private void stamp(JsonObject object, Publication policy) {
			object.addProperty("publicationRevision", processId + ":" + policy.revision);
		}

		private void writeEmpty(Session current) throws IOException {
			final java.util.Set<String> prefixes = new java.util.LinkedHashSet<>();
			current.worlds.forEach(world -> prefixes.add(world.prefix));
			clearPublicSnapshots(current.root, prefixes, processId + ":" + publication.revision, System.currentTimeMillis());
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
			advancePublication(publication.policy, session);
			IOException failure = writeOrCollect(session.root.resolve("index.json"), index().toString(), null);
			try {
				writeEmpty(session);
			} catch (IOException e) {
				if (failure == null) failure = e;
				else failure.addSuppressed(e);
			}
			if (failure != null) throw failure;
			session.needsReset = false;
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
		private boolean needsReset;
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
		private final AtomicBoolean arrivalsFailed = new AtomicBoolean();
		private final BlueMapSnapshotMailbox<SnapshotPair> mailbox = new BlueMapSnapshotMailbox<>();
		private long nextInfrastructure; // IO worker only
		private JsonObject arrivals; // Simulator thread only
		private long arrivalsRevision = -1;
		private long nextArrivals;

		private WorldExport(Simulator simulator) {
			this.simulator = simulator;
			prefix = UUID.nameUUIDFromBytes(simulator.dimension.getBytes(StandardCharsets.UTF_8)).toString();
		}
	}

	private static final class CatalogExport {
		private final Simulator simulator;
		private final AtomicBoolean pending = new AtomicBoolean();
		private final AtomicReference<JsonObject> latest = new AtomicReference<>();
		private long nextCapture;
		private boolean received;

		private CatalogExport(Simulator simulator) {
			this.simulator = simulator;
		}
	}

	private static final class Publication {
		private final long revision;
		private final BlueMapRoutePolicy policy;

		private Publication(long revision, BlueMapRoutePolicy policy) {
			this.revision = revision;
			this.policy = policy;
		}
	}

	private static final class SnapshotPair {
		private final long revision;
		private final JsonObject vehicles;
		private final JsonObject infrastructure;

		private SnapshotPair(long revision, JsonObject vehicles, JsonObject infrastructure) {
			this.revision = revision;
			this.vehicles = vehicles;
			this.infrastructure = infrastructure;
		}
	}
}
