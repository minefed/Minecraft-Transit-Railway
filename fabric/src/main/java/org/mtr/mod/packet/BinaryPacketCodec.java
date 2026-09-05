package org.mtr.mod.packet;

import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.com.google.gson.internal.LazilyParsedNumber;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lossless JSON tree encoding. The versioned dictionary is part of the wire protocol. */
final class BinaryPacketCodec {

	static final int MAX_BYTES = 64 * 1024 * 1024;
	private static final int MAX_DEPTH = 128;
	private static final int NULL = 0, FALSE = 1, TRUE = 2, LONG = 3, DOUBLE = 4, NUMBER = 5, STRING = 6, ARRAY = 7, OBJECT = 8;
	// Never reorder or extend this dictionary without negotiating a new codec version.
	private static final String[] DICTIONARY = ("clientId clientPosition requestRadius existingStationIds existingPlatformIds existingSidingIds existingSimplifiedRouteIds existingDepotIds existingRailIds " +
			"sidingId vehicleId ridingEntities ridingCar x y z isOnGangway isDriver manualAccelerate manualBrake manualToggleDoors manualToggleAto doorOverride " +
			"vehiclesToUpdate vehiclesToKeep liftsToUpdate liftsToKeep signalBlockUpdates vehicle data speed railProgress elapsedDwellTime nextStoppingIndexAto nextStoppingIndexManual reversed departureIndex sidingDepartureTime " +
			"id name color transportMode depotId previousRouteId previousPlatformId previousStationId previousRouteColor previousRouteName previousRouteNumber previousRouteType previousRouteCircularState previousStationName previousRouteDestination " +
			"thisRouteId thisPlatformId thisStationId thisRouteColor thisRouteName thisRouteNumber thisRouteType thisRouteCircularState thisStationName thisRouteDestination " +
			"nextRouteId nextPlatformId nextStationId nextRouteColor nextRouteName nextRouteNumber nextRouteType nextRouteCircularState nextStationName nextRouteDestination " +
			"isTerminating interchangeColorsForStationNameList stoppingPoint powerLevel speedTarget doorTarget isCurrentlyManual railLength totalVehicleLength repeatIndex1 repeatIndex2 acceleration deceleration isManualAllowed maxManualSpeed manualToAutomaticTime totalDistance defaultPosition vehicleCars path " +
			"savedRailBaseId dwellTime stopIndex startDistance endDistance startPosition startAngle endPosition endAngle shape verticalRadius speedLimit " +
			"destination arrival departure deviation realtime routeId routeName routeNumber routeColor circularState platformId platformName cars " +
			"height width depth offsetX offsetY offsetZ isDoubleSided style angle stoppingCoolDown floors instructions railId preBlockedSignalColors currentlyBlockedSignalColors " +
			"stations platforms sidings simplifiedRoutes depots rails position length bogie1Position bogie2Position vehicleIdOverride isReversed _mtrPacketCodec").split(" ");
	private static final Map<String, Integer> DICTIONARY_IDS = new HashMap<>();

	static {
		for (int i = 0; i < DICTIONARY.length; i++) {
			if (DICTIONARY_IDS.put(DICTIONARY[i], i) != null) {
				throw new IllegalStateException("Duplicate packet dictionary entry");
			}
		}
	}

	static byte[] encode(JsonElement value) {
		try {
			final ByteArrayOutputStream bytes = new LimitedOutput();
			write(value, new DataOutputStream(bytes), new HashMap<>(), 0);
			if (bytes.size() > MAX_BYTES) {
				throw new IllegalArgumentException("Binary packet too large");
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot encode packet", e);
		}
	}

	static JsonElement decode(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new IllegalArgumentException("Binary packet too large");
		}
		try {
			final DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
			final JsonElement result = read(input, new ArrayList<>(), 0);
			if (input.available() != 0) {
				throw new IOException("Trailing packet data");
			}
			return result;
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid binary packet", e);
		}
	}

	private static void write(JsonElement value, DataOutputStream output, Map<String, Integer> strings, int depth) throws IOException {
		checkDepth(depth);
		if (value.isJsonNull()) {
			output.writeByte(NULL);
		} else if (value.isJsonArray()) {
			output.writeByte(ARRAY);
			writeUnsigned(output, value.getAsJsonArray().size());
			for (JsonElement child : value.getAsJsonArray()) {
				write(child, output, strings, depth + 1);
			}
		} else if (value.isJsonObject()) {
			output.writeByte(OBJECT);
			writeUnsigned(output, value.getAsJsonObject().size());
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				writeString(entry.getKey(), output, strings);
				write(entry.getValue(), output, strings, depth + 1);
			}
		} else {
			final JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				output.writeByte(primitive.getAsBoolean() ? TRUE : FALSE);
			} else if (primitive.isString()) {
				output.writeByte(STRING);
				writeString(primitive.getAsString(), output, strings);
			} else {
				final String text = primitive.getAsString();
				try {
					final long number = Long.parseLong(text);
					if (Long.toString(number).equals(text)) {
						output.writeByte(LONG);
						writeUnsigned(output, (number << 1) ^ (number >> 63));
						return;
					}
				} catch (NumberFormatException ignored) {
				}
				final double number = primitive.getAsDouble();
				if (Double.isFinite(number) && Double.toString(number).equals(text)) {
					output.writeByte(DOUBLE);
					output.writeDouble(number);
				} else {
					output.writeByte(NUMBER);
					writeString(text, output, strings);
				}
			}
		}
	}

	private static JsonElement read(DataInputStream input, List<String> strings, int depth) throws IOException {
		checkDepth(depth);
		switch (input.readUnsignedByte()) {
			case NULL: return JsonNull.INSTANCE;
			case FALSE: return new JsonPrimitive(false);
			case TRUE: return new JsonPrimitive(true);
			case LONG:
				final long number = readUnsigned(input);
				return new JsonPrimitive((number >>> 1) ^ -(number & 1));
			case DOUBLE: return new JsonPrimitive(input.readDouble());
			case NUMBER: return new JsonPrimitive(new LazilyParsedNumber(readString(input, strings)));
			case STRING: return new JsonPrimitive(readString(input, strings));
			case ARRAY:
				final int arraySize = readCount(input, 1);
				final JsonArray array = new JsonArray();
				for (int i = 0; i < arraySize; i++) {
					array.add(read(input, strings, depth + 1));
				}
				return array;
			case OBJECT:
				final int objectSize = readCount(input, 2);
				final JsonObject object = new JsonObject();
				for (int i = 0; i < objectSize; i++) {
					final String key = readString(input, strings);
					if (object.has(key)) {
						throw new IOException("Duplicate object key");
					}
					object.add(key, read(input, strings, depth + 1));
				}
				return object;
			default: throw new IOException("Unknown value type");
		}
	}

	private static void writeString(String value, DataOutputStream output, Map<String, Integer> strings) throws IOException {
		final Integer knownId = DICTIONARY_IDS.get(value);
		final Integer id = knownId == null ? strings.get(value) : knownId;
		if (id != null) {
			writeUnsigned(output, (long) id + 1);
		} else {
			// The legacy transport preserves UTF-16 code units, including unpaired
			// surrogates. Such strings must use its fallback rather than UTF-8 replacement.
			for (int i = 0; i < value.length(); i++) {
				final char character = value.charAt(i);
				if (Character.isHighSurrogate(character)) {
					if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
						throw new IOException("Unpaired surrogate");
					}
				} else if (Character.isLowSurrogate(character)) {
					throw new IOException("Unpaired surrogate");
				}
			}
			writeUnsigned(output, 0);
			final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			writeUnsigned(output, bytes.length);
			output.write(bytes);
			strings.put(value, DICTIONARY.length + strings.size());
		}
	}

	private static String readString(DataInputStream input, List<String> strings) throws IOException {
		final long id = readUnsigned(input);
		if (id != 0) {
			if (id < 0 || id > DICTIONARY.length + strings.size()) {
				throw new IOException("Invalid string reference");
			}
			return id <= DICTIONARY.length ? DICTIONARY[(int) id - 1] : strings.get((int) id - DICTIONARY.length - 1);
		}
		final byte[] bytes = new byte[readCount(input, 1)];
		input.readFully(bytes);
		final String value = new String(bytes, StandardCharsets.UTF_8);
		strings.add(value);
		return value;
	}

	private static int readCount(DataInputStream input, int minBytes) throws IOException {
		final long size = readUnsigned(input);
		if (size < 0 || size > input.available() / minBytes) {
			throw new IOException("Invalid value count");
		}
		return (int) size;
	}

	private static void writeUnsigned(DataOutputStream output, long value) throws IOException {
		while ((value & ~0x7FL) != 0) {
			output.writeByte((int) (value & 0x7F) | 0x80);
			value >>>= 7;
		}
		output.writeByte((int) value);
	}

	private static long readUnsigned(DataInputStream input) throws IOException {
		long result = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			final int next = input.readUnsignedByte();
			if (shift == 63 && (next & 0xFE) != 0) {
				throw new IOException("Integer overflow");
			}
			result |= (long) (next & 0x7F) << shift;
			if ((next & 0x80) == 0) {
				return result;
			}
		}
		throw new IOException("Invalid integer");
	}

	private static void checkDepth(int depth) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("Packet nesting too deep");
		}
	}

	private static final class LimitedOutput extends ByteArrayOutputStream {
		@Override
		public synchronized void write(int value) {
			checkCapacity(1);
			super.write(value);
		}

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			checkCapacity(length);
			super.write(bytes, offset, length);
		}

		private void checkCapacity(int length) {
			if (length > MAX_BYTES - count) {
				throw new IllegalArgumentException("Binary packet too large");
			}
		}
	}
}
