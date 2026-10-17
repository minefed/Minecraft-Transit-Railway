package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.mod.Init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Server-side public transport capture, independent of BlueMap installation or lifecycle. */
final class TransitExporter implements AutoCloseable {
	private final BlueMapSnapshot snapshot = new BlueMapSnapshot();
	private final List<WorldExport> exports = new ArrayList<>();
	private final List<CatalogExport> catalogExports = new ArrayList<>();
	private final Map<String, JsonObject> routeCatalogs = new LinkedHashMap<>();
	private final Path catalog;
	private final Path routeCatalogFile;
	private final Path publicRoutesFile;
	private final TransitApiPublisher publisher;
	private final Runnable maintainOverlay;
	private final String processId = UUID.randomUUID().toString();
	private volatile Publication publication = new Publication(0, BlueMapRoutePolicy.CLOSED);
	private JsonObject vehicleCatalog = emptyCatalog();
	private long nextCatalog;
	private long nextPolicyRead;
	private String lastPolicyError = "";
	private long lastError;
	private volatile boolean closed;
	private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR-transit-capture");
		thread.setDaemon(true);
		return thread;
	});

	TransitExporter(Main main, Path runDirectory, TransitApiConfig config, Runnable maintainOverlay) throws IllegalAccessException {
		this.maintainOverlay = maintainOverlay;
		catalog = runDirectory.resolve("config/mtr/bluemap-vehicles.json");
		routeCatalogFile = runDirectory.resolve("config/mtr/bluemap-routes.json");
		publicRoutesFile = runDirectory.resolve("config/mtr/bluemap-public-routes.json");
		// Construct all simulator exports; no BlueMap worlds or maps are needed.
		if (config.enabled()) for (Simulator simulator : snapshot.simulators(main)) {
			final WorldExport world = new WorldExport(simulator);
			reset(world, publication);
			exports.add(world);
			catalogExports.add(new CatalogExport(simulator));
			final JsonObject metadata = new JsonObject();
			metadata.addProperty("worldId", simulator.dimension);
			metadata.add("routes", new JsonArray());
			metadata.add("stations", new JsonArray());
			routeCatalogs.put(simulator.dimension, metadata);
		}
		publisher = config.enabled() ? new TransitApiPublisher(config) : null;
	}

	void start() {
		io.scheduleWithFixedDelay(this::tick, 0, 500, TimeUnit.MILLISECONDS);
	}

	private void tick() {
		if (closed) return;
		// UI installation failures cannot stop capture or public-policy revocation.
		try { maintainOverlay.run(); } catch (RuntimeException e) { logError("Could not update BlueMap API bootstrap", e); }
		if (publisher == null) return;
		try {
			final long now = System.currentTimeMillis();
			try {
				BlueMapLiveBridge.createInitial(publicRoutesFile, BlueMapRoutePolicy.INITIAL);
				BlueMapLiveBridge.createInitial(routeCatalogFile, catalogDocument(now).toString());
			} catch (IOException e) { logError("Could not create private route configuration", e); }
			if (now >= nextPolicyRead) {
				readPolicy();
				nextPolicyRead = now + 2000;
			}
			updateRouteCatalogs(now);
			for (WorldExport world : exports) {
				final SnapshotPair latest = world.mailbox.take();
				if (latest != null && latest.revision == publication.revision) {
					world.vehicles = latest.vehicles;
					if (latest.infrastructure != null) {
						world.infrastructure = latest.infrastructure;
						world.nextInfrastructure = now + 30000;
					}
				}
				if (world.pending.compareAndSet(false, true)) {
					final boolean updateInfrastructure = now >= world.nextInfrastructure;
					final Publication policy = publication;
					final BlueMapRoutePolicy.WorldSelection selection = policy.policy.world(world.simulator.dimension);
					world.simulator.run(() -> {
						try {
							if (closed || publication != policy) return;
							final long captureTime = System.currentTimeMillis();
							final JsonObject vehicleData = snapshot.vehicles(world.simulator, captureTime, selection);
							final JsonObject infrastructure = updateInfrastructure ? BlueMapSnapshot.infrastructure(world.simulator, captureTime, selection, world.geometryCache) : null;
							if (world.arrivals == null || world.arrivalsRevision != policy.revision || captureTime >= world.nextArrivals) {
								try {
									world.arrivals = BlueMapSnapshot.arrivals(world.simulator, captureTime, selection);
									world.arrivalsFailed.set(false);
								} catch (Exception e) {
									world.arrivals = BlueMapSnapshot.arrivalData(new JsonArray(), 0);
									if (world.arrivalsFailed.compareAndSet(false, true)) Init.LOGGER.warn("[MTR transit] Arrivals unavailable for {}", world.simulator.dimension, e);
								}
								world.arrivalsRevision = policy.revision;
								world.nextArrivals = captureTime + 2000;
							}
							vehicleData.add("arrivals", world.arrivals.get("arrivals"));
							vehicleData.add("arrivalsTimestamp", world.arrivals.get("arrivalsTimestamp"));
							stamp(vehicleData, policy);
							if (infrastructure != null) stamp(infrastructure, policy);
							if (!closed && publication == policy) world.mailbox.publish(policy.revision, new SnapshotPair(policy.revision, vehicleData, infrastructure));
						} catch (Exception e) {
							if (world.failed.compareAndSet(false, true)) Init.LOGGER.warn("[MTR transit] Capture failed for {}", world.simulator.dimension, e);
						} finally { world.pending.set(false); }
					});
				}
			}
			if (now >= nextCatalog) {
				vehicleCatalog = readVehicleCatalog();
				nextCatalog = now + 30000;
			}
			publisher.offer(document(false));
		} catch (Exception e) { logError("Public capture failed; will retry", e); }
	}

	private void logError(String message, Exception error) {
		if (System.currentTimeMillis() - lastError > 30000) {
			lastError = System.currentTimeMillis();
			Init.LOGGER.warn("[MTR transit] " + message, error);
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
						Init.LOGGER.warn("[MTR transit] Route catalog unavailable for {}", world.simulator.dimension, e);
					} finally {
						world.pending.set(false);
					}
				});
			}
		}
		if (changed) {
			nextPolicyRead = 0;
			try {
				BlueMapLiveBridge.atomicWrite(routeCatalogFile, catalogDocument(now).toString());
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
				Init.LOGGER.warn("[MTR transit] No routes published: {}", message);
				lastPolicyError = message;
			}
		}
		if (!next.fingerprint().equals(publication.policy.fingerprint())) {
			advancePublication(next);
		}
	}


	private void advancePublication(BlueMapRoutePolicy policy) {
		publication = new Publication(publication.revision + 1, policy);
		for (WorldExport world : exports) reset(world, publication);
		// Queue revocation even if later catalog IO or capture fails.
		publisher.offer(document(false));
	}

	private void stamp(JsonObject value, Publication policy) {
		value.addProperty("publicationRevision", processId + ":" + policy.revision);
	}

	private void reset(WorldExport world, Publication policy) {
		world.mailbox.reset(policy.revision);
		world.nextInfrastructure = 0;
		final long now = System.currentTimeMillis();
		world.vehicles = BlueMapSnapshot.empty("vehicles", now);
		world.vehicles.add("arrivals", new JsonArray());
		world.vehicles.addProperty("arrivalsTimestamp", 0);
		stamp(world.vehicles, policy);
		world.infrastructure = BlueMapSnapshot.empty("rails", now);
		world.infrastructure.add("routes", new JsonArray());
		world.infrastructure.add("stations", new JsonArray());
		stamp(world.infrastructure, policy);
	}

	private JsonObject document(boolean revoke) {
		final JsonObject document = new JsonObject();
		document.addProperty("publicationRevision", processId + ":" + publication.revision);
		final JsonArray worlds = new JsonArray();
		if (!revoke) for (WorldExport world : exports) {
			final JsonObject item = new JsonObject();
			item.addProperty("id", world.prefix);
			item.addProperty("worldId", world.simulator.dimension);
			item.add("vehicles", world.vehicles);
			item.add("infrastructure", world.infrastructure);
			worlds.add(item);
		}
		document.add("worlds", worlds);
		document.add("catalog", revoke ? emptyCatalog() : vehicleCatalog);
		return document;
	}

	static JsonObject emptyCatalog() {
		final JsonObject catalog = new JsonObject();
		catalog.addProperty("schema", 1);
		catalog.add("vehicles", new JsonObject());
		return catalog;
	}

	private JsonObject readVehicleCatalog() {
		final JsonObject result = emptyCatalog();
		if (!Files.isRegularFile(catalog)) return result;
		try {
			if (Files.size(catalog) > 2 * 1024 * 1024) throw new IOException("Vehicle catalog exceeds 2 MiB");
			final JsonObject source = JsonParser.parseString(Files.readString(catalog)).getAsJsonObject().getAsJsonObject("vehicles");
			if (source == null) return result;
			for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
				if (!entry.getValue().isJsonObject()) continue;
				final JsonObject vehicle = entry.getValue().getAsJsonObject();
				final JsonObject allowed = new JsonObject();
				if (vehicle.has("name") && vehicle.get("name").isJsonPrimitive() && vehicle.get("name").getAsJsonPrimitive().isString()) allowed.add("name", vehicle.get("name"));
				for (String key : List.of("length", "width")) {
					if (vehicle.has(key) && vehicle.get(key).isJsonPrimitive() && vehicle.get(key).getAsJsonPrimitive().isNumber()) {
						final double value = vehicle.get(key).getAsDouble();
						if (Double.isFinite(value) && value > 0 && value < 256) allowed.addProperty(key, value);
					}
				}
				result.getAsJsonObject("vehicles").add(entry.getKey(), allowed);
			}
		} catch (IOException | RuntimeException e) { logError("Ignoring invalid optional vehicle catalog", e); }
		return result;
	}

	@Override
	public void close() {
		closed = true;
		io.execute(() -> {
			if (publisher != null) {
				publication = new Publication(publication.revision + 1, BlueMapRoutePolicy.CLOSED);
				publisher.offer(document(true));
				publisher.close();
			}
		});
		io.shutdown();
		try {
			if (!io.awaitTermination(3500, TimeUnit.MILLISECONDS)) io.shutdownNow();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			io.shutdownNow();
		}
	}
	private static final class WorldExport {
		private final Simulator simulator;
		private final String prefix;
		private final AtomicBoolean pending = new AtomicBoolean();
		private final AtomicBoolean failed = new AtomicBoolean();
		private final AtomicBoolean arrivalsFailed = new AtomicBoolean();
		private final BlueMapSnapshotMailbox<SnapshotPair> mailbox = new BlueMapSnapshotMailbox<>();
		private final RailGeometryCache geometryCache = new RailGeometryCache(); // Simulator thread only
		private JsonObject vehicles; // IO worker only; completed snapshots are never mutated
		private JsonObject infrastructure;
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
