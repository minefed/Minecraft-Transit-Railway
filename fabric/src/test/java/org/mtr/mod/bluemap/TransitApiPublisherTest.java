package org.mtr.mod.bluemap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class TransitApiPublisherTest {

	@Test
	void publishesOnSeparateWorkerCoalescesAndPreservesCaptureTimes() throws Exception {
		final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		final AtomicInteger calls = new AtomicInteger();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/transit/test/snapshot", exchange -> {
			assertEquals("PUT", exchange.getRequestMethod());
			assertEquals("Bearer " + "x".repeat(32), exchange.getRequestHeaders().getFirst("Authorization"));
			requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			if (calls.incrementAndGet() == 1) {
				entered.countDown();
				try { release.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try (TransitApiPublisher publisher = new TransitApiPublisher(config(server))) {
			publisher.offer(snapshot("policy:1", 123, 100));
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			final JsonObject first = requests.poll(2, TimeUnit.SECONDS);
			assertNotNull(first);
			assertEquals(123, first.getAsJsonArray("worlds").get(0).getAsJsonObject().getAsJsonObject("vehicles").get("timestamp").getAsLong());
			// Offers complete while HTTP is blocked, and only the latest policy survives.
			publisher.offer(snapshot("policy:1", 124, 100));
			publisher.offer(snapshot("policy:2", 125, 125));
			release.countDown();
			final JsonObject latest = requests.poll(3, TimeUnit.SECONDS);
			assertNotNull(latest);
			assertEquals("policy:2", latest.get("publicationRevision").getAsString());
			assertTrue(latest.get("sequence").getAsLong() > first.get("sequence").getAsLong());
			assertTrue(latest.getAsJsonArray("worlds").get(0).getAsJsonObject().has("infrastructure"));
		} finally { release.countDown(); server.stop(0); }
	}

	@Test
	void apiRestartRequestsFullCachedInfrastructureAndDoesNotFollowRedirects() throws Exception {
		final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
		final AtomicInteger calls = new AtomicInteger(), redirected = new AtomicInteger();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/transit/test/snapshot", exchange -> {
			requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			final int call = calls.incrementAndGet();
			if (call == 4) exchange.getResponseHeaders().set("Location", "/steal-token");
			exchange.sendResponseHeaders(call == 2 ? 409 : call == 4 ? 307 : 204, -1);
			exchange.close();
		});
		server.createContext("/steal-token", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
		server.start();
		try (TransitApiPublisher publisher = new TransitApiPublisher(config(server))) {
			final JsonObject firstSnapshot = snapshot("policy:1", 123, 100);
			publisher.offer(firstSnapshot);
			assertNotNull(requests.poll(2, TimeUnit.SECONDS));
			// Acknowledgement is processed before the next publication in this serial sender.
			final JsonObject nextSnapshot = snapshot("policy:1", 124, 100);
			nextSnapshot.getAsJsonArray("worlds").get(0).getAsJsonObject().add("infrastructure", firstSnapshot.getAsJsonArray("worlds").get(0).getAsJsonObject().get("infrastructure"));
			publisher.offer(nextSnapshot);
			final JsonObject second = requests.poll(2, TimeUnit.SECONDS);
			assertNotNull(second);
			assertFalse(second.getAsJsonArray("worlds").get(0).getAsJsonObject().has("infrastructure"));
			final JsonObject recovered = requests.poll(3, TimeUnit.SECONDS);
			assertNotNull(recovered);
			assertTrue(recovered.getAsJsonArray("worlds").get(0).getAsJsonObject().has("infrastructure"));
			publisher.offer(snapshot("policy:2", 125, 125));
			assertNotNull(requests.poll(2, TimeUnit.SECONDS));
			assertNotNull(requests.poll(3, TimeUnit.SECONDS));
			assertEquals(0, redirected.get());
		} finally { server.stop(0); }
	}

	private static TransitApiConfig config(HttpServer server) throws Exception {
		return TransitApiConfig.parse("{\"apiBaseUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/v1/transit/test\"}", ignored -> "x".repeat(32));
	}

	@Test
	void stalledResponseBodyCannotBlockLaterRevocation() throws Exception {
		final BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService handlers = Executors.newCachedThreadPool();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(handlers);
		server.createContext("/v1/transit/test/snapshot", exchange -> {
			requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			if (calls.incrementAndGet() == 1) {
				exchange.sendResponseHeaders(200, 100);
				exchange.getResponseBody().write(1);
				exchange.getResponseBody().flush();
				try { release.await(6, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			} else exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();
		try (TransitApiPublisher publisher = new TransitApiPublisher(config(server))) {
			publisher.offer(snapshot("policy:1", 123, 123));
			assertNotNull(requests.poll(2, TimeUnit.SECONDS));
			publisher.offer(snapshot("policy:2", 124, 124));
			final JsonObject revoked = requests.poll(4, TimeUnit.SECONDS);
			assertNotNull(revoked, "A stuck response must release the sender for a newer policy");
			assertEquals("policy:2", revoked.get("publicationRevision").getAsString());
		} finally {
			release.countDown();
			server.stop(0);
			handlers.shutdownNow();
		}
	}

	private static JsonObject snapshot(String revision, long timestamp, long infrastructureTime) {
		final JsonObject snapshot = new JsonObject();
		snapshot.addProperty("publicationRevision", revision);
		snapshot.add("catalog", TransitExporter.emptyCatalog());
		final JsonArray worlds = new JsonArray();
		final JsonObject world = new JsonObject();
		world.addProperty("id", "0d075393-52e5-3731-9042-c1f53430c696");
		world.addProperty("worldId", "minecraft/overworld");
		final JsonObject vehicles = BlueMapSnapshot.empty("vehicles", timestamp);
		vehicles.addProperty("publicationRevision", revision);
		vehicles.addProperty("arrivalsTimestamp", 0);
		vehicles.add("arrivals", new JsonArray());
		world.add("vehicles", vehicles);
		final JsonObject infrastructure = BlueMapSnapshot.empty("rails", infrastructureTime);
		infrastructure.addProperty("publicationRevision", revision);
		infrastructure.add("routes", new JsonArray());
		infrastructure.add("stations", new JsonArray());
		world.add("infrastructure", infrastructure);
		worlds.add(world);
		snapshot.add("worlds", worlds);
		return snapshot;
	}
}
