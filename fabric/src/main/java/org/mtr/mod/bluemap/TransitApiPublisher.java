package org.mtr.mod.bluemap;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mod.Init;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One in-flight HTTP request and one latest snapshot: outages never queue unbounded work. */
final class TransitApiPublisher implements AutoCloseable {

	private final TransitApiConfig config;
	private final HttpClient client;
	private final String publisherId = UUID.randomUUID().toString();
	private final long publisherStartedAt = System.currentTimeMillis();
	private final AtomicReference<JsonObject> latest = new AtomicReference<>();
	private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR-transit-api-send");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, JsonObject> acknowledgedInfrastructure = new HashMap<>();
	private JsonObject acknowledgedCatalog;
	private String acknowledgedRevision = "";
	private long sequence;
	private long lastError;
	private long nextAttempt;
	private boolean full = true;
	private volatile boolean closed;

	TransitApiPublisher(TransitApiConfig config) {
		this.config = config;
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
		sender.scheduleWithFixedDelay(this::sendLatest, 0, 50, TimeUnit.MILLISECONDS);
	}

	/** The complete immutable snapshot includes cached infrastructure for retry/restart recovery. */
	void offer(JsonObject snapshot) {
		if (!closed) latest.set(snapshot);
	}

	private void sendLatest() {
		if (!closed && System.currentTimeMillis() < nextAttempt) return;
		final JsonObject snapshot = latest.getAndSet(null);
		if (snapshot == null) return;
		try {
			final String revision = snapshot.get("publicationRevision").getAsString();
			final boolean sendFull = full || !revision.equals(acknowledgedRevision);
			final JsonObject body = new JsonObject();
			body.addProperty("schema", 1);
			body.addProperty("publisherId", publisherId);
			body.addProperty("publisherStartedAt", publisherStartedAt);
			body.addProperty("sequence", ++sequence);
			body.addProperty("publicationRevision", revision);
			final JsonArray worlds = new JsonArray();
			final Map<String, JsonObject> infrastructureVersions = new HashMap<>();
			for (JsonElement item : snapshot.getAsJsonArray("worlds")) {
				final JsonObject source = item.getAsJsonObject();
				final String id = source.get("id").getAsString();
				final JsonObject world = new JsonObject();
				world.add("id", source.get("id"));
				world.add("worldId", source.get("worldId"));
				world.add("vehicles", source.get("vehicles"));
				// Capture objects are immutable. Identity also distinguishes an empty reset from
				// a real capture completed in the same millisecond, without rehashing geometry.
				final JsonObject version = source.getAsJsonObject("infrastructure");
				infrastructureVersions.put(id, version);
				if (sendFull || version != acknowledgedInfrastructure.get(id)) world.add("infrastructure", version);
				worlds.add(world);
			}
			body.add("worlds", worlds);
			final JsonObject catalog = snapshot.getAsJsonObject("catalog");
			if (sendFull || catalog != acknowledgedCatalog) body.add("catalog", catalog);
			final HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBase + "/snapshot"))
					.timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + config.token)
					.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build();
			// HttpRequest.timeout alone does not bound a stalled response body on Java 17.
			final var response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
			final int status;
			try {
				status = response.get(2, TimeUnit.SECONDS).statusCode();
			} finally {
				if (!response.isDone()) response.cancel(true);
			}
			if (status < 200 || status >= 300) throw new java.io.IOException("HTTP " + status);
			acknowledgedRevision = revision;
			acknowledgedInfrastructure.clear();
			acknowledgedInfrastructure.putAll(infrastructureVersions);
			acknowledgedCatalog = catalog;
			full = false;
		} catch (Exception e) {
			nextAttempt = System.currentTimeMillis() + 500;
			full = true; // API restart, rejection, and lost acknowledgements all recover with a complete snapshot.
			latest.compareAndSet(null, snapshot); // Never overwrite a newer policy/snapshot with an older retry.
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			if (System.currentTimeMillis() - lastError > 30000) {
				lastError = System.currentTimeMillis();
				// Neither upstream response bodies nor exception text may disclose credentials.
				Init.LOGGER.warn("[MTR transit] API publication failed; retrying the latest public snapshot");
			}
		}
	}

	@Override
	public void close() {
		closed = true;
		sender.execute(this::sendLatest);
		sender.shutdown();
		try {
			if (!sender.awaitTermination(2500, TimeUnit.MILLISECONDS)) sender.shutdownNow();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			sender.shutdownNow();
		}
	}
}
