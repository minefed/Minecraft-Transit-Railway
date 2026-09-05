package org.mtr.mod.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

public final class PacketPayloadTest {

	@Test
	public void legacyReaderReceivesExactStringAndFollowingFields() {
		final JsonObject json = fixture(10);
		final PacketPayload payload = new PacketPayload(json);
		final List<ByteBuf> frames = send(sender -> {
			payload.write(sender, false);
			sender.writeBoolean(true);
			sender.writeLong(Long.MIN_VALUE);
		});
		receive(frames, receiver -> {
			assertEquals(json.toString(), receiver.readString());
			assertTrue(receiver.readBoolean());
			assertEquals(Long.MIN_VALUE, receiver.readLong());
		});
	}

	@Test
	public void binaryAndLegacyFramesRoundTripAcrossFragmentsAndPreserveTails() {
		final JsonObject json = fixture(4000);
		final List<ByteBuf> legacy = send(sender -> new PacketPayload(json).write(sender, false));
		final List<ByteBuf> binary = send(sender -> {
			new PacketPayload(json).write(sender, true);
			sender.writeBoolean(true);
			sender.writeLong(987654321L);
		});
		assertTrue(binary.size() > 1);
		assertTrue(size(binary) < size(legacy));
		System.out.println("Vehicle/ID fixture wire bytes, legacy=" + size(legacy) + ", binary=" + size(binary));
		System.out.println("Same synthetic frames after default zlib compression, legacy=" + compressedSize(legacy) + ", binary=" + compressedSize(binary));
		Collections.reverse(binary);
		receive(binary, receiver -> {
			assertEquals(json.toString(), PacketPayload.read(receiver).json().toString());
			assertTrue(receiver.readBoolean());
			assertEquals(987654321L, receiver.readLong());
		});
		receive(legacy, receiver -> assertEquals(json.toString(), PacketPayload.read(receiver).json().toString()));
	}

	@Test
	public void neverExpandsAsciiCjkEmojiOrSmallPayloadsAndPreservesAllTailLengths() {
		for (String unit : new String[]{"abc", "한글漢字", "🚆🚉"}) {
			for (int length = 0; length < 32; length++) {
				final JsonObject json = new JsonObject();
				json.addProperty("text", unit.repeat(length));
				final List<ByteBuf> legacy = send(sender -> new PacketPayload(json).write(sender, false));
				final List<ByteBuf> modern = send(sender -> new PacketPayload(json).write(sender, true));
				assertTrue(size(modern) <= size(legacy));
				receive(modern, receiver -> assertEquals(json.toString(), PacketPayload.read(receiver).json().toString()));
				receive(legacy, receiver -> assertEquals(json.toString(), receiver.readString()));
			}
		}
	}

	@Test
	public void unpairedSurrogatesAndDeepTreesUseExactLegacyFallback() {
		final JsonObject surrogate = new JsonObject();
		surrogate.addProperty("text", "\uD800" + "same ".repeat(200) + "\uDC00");
		final JsonObject deep = new JsonObject();
		JsonObject current = deep;
		for (int i = 0; i < 140; i++) {
			final JsonObject child = new JsonObject();
			current.add("data", child);
			current = child;
		}
		for (JsonObject json : new JsonObject[]{surrogate, deep}) {
			receive(send(sender -> new PacketPayload(json).write(sender, true)), receiver -> assertEquals(json.toString(), receiver.readString()));
		}
	}

	private static JsonObject fixture(int count) {
		final JsonObject json = new JsonObject();
		final JsonArray ids = new JsonArray();
		final JsonArray updates = new JsonArray();
		final Random random = new Random(47);
		for (int i = 0; i < count; i++) {
			ids.add(random.nextLong());
			final JsonObject update = new JsonObject();
			update.addProperty("vehicleId", random.nextLong());
			update.addProperty("railProgress", random.nextDouble() * 10000);
			update.addProperty("thisStationName", "서울|Seoul|東京🚆");
			update.addProperty("doorTarget", i % 2 == 0);
			updates.add(update);
		}
		json.add("vehiclesToKeep", ids);
		json.add("vehiclesToUpdate", updates);
		return json;
	}

	private static List<ByteBuf> send(Consumer<PacketBufferSender> writer) {
		final PacketBufferSender sender = new PacketBufferSender(Unpooled::buffer);
		writer.accept(sender);
		final List<ByteBuf> frames = new ArrayList<>();
		final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
		sender.send(frames::add, scheduled::add);
		while (!scheduled.isEmpty()) {
			scheduled.removeFirst().run();
		}
		return frames;
	}

	private static void receive(List<ByteBuf> frames, Consumer<PacketBufferReceiver> reader) {
		final AtomicInteger completions = new AtomicInteger();
		try {
			for (ByteBuf frame : frames) {
				PacketBufferReceiver.receive(frame, receiver -> {
					reader.accept(receiver);
					completions.incrementAndGet();
				}, Runnable::run);
			}
			assertEquals(1, completions.get());
		} finally {
			frames.forEach(ByteBuf::release);
		}
	}

	private static int size(List<ByteBuf> frames) {
		return frames.stream().mapToInt(ByteBuf::readableBytes).sum();
	}

	private static int compressedSize(List<ByteBuf> frames) {
		int total = 0;
		for (ByteBuf frame : frames) {
			final byte[] bytes = new byte[frame.readableBytes()];
			frame.getBytes(frame.readerIndex(), bytes);
			final Deflater deflater = new Deflater();
			try {
				deflater.setInput(bytes);
				deflater.finish();
				final byte[] buffer = new byte[4096];
				while (!deflater.finished()) {
					total += deflater.deflate(buffer);
				}
			} finally {
				deflater.end();
			}
		}
		return total;
	}
}
