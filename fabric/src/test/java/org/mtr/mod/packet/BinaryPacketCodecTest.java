package org.mtr.mod.packet;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.operation.UpdateVehicleRidingEntities;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.*;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public final class BinaryPacketCodecTest {

	@Test
	public void versionOneGoldenVectorRemainsWireCompatible() {
		final String golden = "08040b03020e0480000000000000000f043ff80000000000001402";
		final byte[] bytes = new byte[golden.length() / 2];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) Integer.parseInt(golden.substring(i * 2, i * 2 + 2), 16);
		}
		final JsonElement value = JsonParser.parseString("{\"vehicleId\":1,\"x\":-0.0,\"y\":1.5,\"manualBrake\":true}");
		assertArrayEquals(bytes, BinaryPacketCodec.encode(value));
		assertEquals(value.toString(), BinaryPacketCodec.decode(bytes).toString());
	}

	@Test
	public void preservesNumericLexemesUnicodeUnknownFieldsAndOrder() {
		final JsonElement source = JsonParser.parseString("{\"futureField\":[null,true,false,0,-0,1.0,-0.0,1e2,1.00,9223372036854775807,-9223372036854775808,18446744073709551615,1e1000],\"이름\":\"서울|東京🚆\\n\\\"\\\\\",\"empty\":{},\"array\":[],\"repeat\":\"이름\"}");
		assertEquals(source.toString(), BinaryPacketCodec.decode(BinaryPacketCodec.encode(source)).toString());
	}

	@Test
	public void preservesEveryRidingFlagAndFullPrecisionCoreRoundTrip() {
		for (int flags = 0; flags < 128; flags++) {
			final UpdateVehicleRidingEntities update = new UpdateVehicleRidingEntities(Long.MIN_VALUE, Long.MAX_VALUE);
			update.add(new VehicleRidingEntity(new UUID(Long.MIN_VALUE, Long.MAX_VALUE), flags % 2 == 0 ? -1 : 3, Math.nextUp(1.0), -0.0, Double.MIN_VALUE,
					(flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0));
			final JsonObject original = Utilities.getJsonObjectFromData(update);
			final byte[] encoded = BinaryPacketCodec.encode(original);
			final UpdateVehicleRidingEntities decoded = new UpdateVehicleRidingEntities(new JsonReader(BinaryPacketCodec.decode(encoded).getAsJsonObject()));
			assertEquals(original.toString(), Utilities.getJsonObjectFromData(decoded).toString());
			assertTrue(encoded.length < original.toString().length() * 2);
		}
		final JsonObject empty = Utilities.getJsonObjectFromData(new UpdateVehicleRidingEntities(1, 2));
		assertEquals(empty.toString(), BinaryPacketCodec.decode(BinaryPacketCodec.encode(empty)).toString());
	}

	@Test
	public void preservesArbitraryFiniteDoubleBitsAndLongBoundaries() {
		final Random random = new Random(20260906);
		final JsonArray values = new JsonArray();
		for (int i = 0; i < 5000; i++) {
			values.add(random.nextLong());
			final double value = Double.longBitsToDouble(random.nextLong());
			if (Double.isFinite(value)) {
				values.add(value);
			}
		}
		assertEquals(values.toString(), BinaryPacketCodec.decode(BinaryPacketCodec.encode(values)).toString());
	}

	@Test
	public void rejectsTruncationTrailingDataInvalidReferencesAndDepth() {
		final byte[] valid = BinaryPacketCodec.encode(JsonParser.parseString("{\"vehicleId\":123,\"unknown\":[\"value\",0.1,true]}"));
		for (int length = 0; length < valid.length; length++) {
			final byte[] truncated = Arrays.copyOf(valid, length);
			assertThrows(IllegalArgumentException.class, () -> BinaryPacketCodec.decode(truncated));
		}
		assertThrows(IllegalArgumentException.class, () -> BinaryPacketCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
		assertThrows(IllegalArgumentException.class, () -> BinaryPacketCodec.decode(new byte[]{6, (byte) 255, (byte) 255, 127}));
		assertThrows(IllegalArgumentException.class, () -> BinaryPacketCodec.decode(new byte[]{7, (byte) 255, (byte) 255, 127}));
		JsonArray deep = new JsonArray();
		JsonArray current = deep;
		for (int i = 0; i < 130; i++) {
			final JsonArray child = new JsonArray();
			current.add(child);
			current = child;
		}
		assertThrows(IllegalArgumentException.class, () -> BinaryPacketCodec.encode(deep));
	}
}
