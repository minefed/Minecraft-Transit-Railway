package org.mtr.mod.packet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.operation.DataRequest;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public final class PacketCodecCapabilitiesTest {

	@AfterEach
	public void reset() {
		PacketCodecCapabilities.resetClient();
		PacketCodecCapabilities.resetServer();
	}

	@Test
	public void legacyCoreIgnoresTheAdvertisementWithoutChangingRequest() {
		final JsonObject original = Utilities.getJsonObjectFromData(new DataRequest(new UUID(1, 2), new Position(1, 2, 3), 128));
		final JsonObject advertised = PacketCodecCapabilities.advertise(original.deepCopy());
		assertEquals(original.toString(), Utilities.getJsonObjectFromData(new DataRequest(new JsonReader(advertised))).toString());
	}

	@Test
	public void negotiatesPerConnectionAndFallsBackForLegacyOrUnsupportedPeers() {
		final UUID modern = new UUID(1, 2);
		final UUID legacy = new UUID(3, 4);
		assertFalse(PacketCodecCapabilities.canSendToServer());
		assertFalse(PacketCodecCapabilities.canSendToClient(modern));
		final JsonObject advertisement = PacketCodecCapabilities.advertise(new JsonObject());
		PacketCodecCapabilities.receiveClient(modern, advertisement);
		PacketCodecCapabilities.receiveClient(legacy, new JsonObject());
		assertTrue(PacketCodecCapabilities.canSendToClient(modern));
		assertFalse(PacketCodecCapabilities.canSendToClient(legacy));
		PacketCodecCapabilities.receiveServer(advertisement);
		assertTrue(PacketCodecCapabilities.canSendToServer());
		PacketCodecCapabilities.resetClient();
		assertFalse(PacketCodecCapabilities.canSendToServer());
		PacketCodecCapabilities.removeClient(modern);
		assertFalse(PacketCodecCapabilities.canSendToClient(modern));
		advertisement.addProperty("_mtrPacketCodec", 2);
		PacketCodecCapabilities.receiveClient(modern, advertisement);
		PacketCodecCapabilities.receiveServer(advertisement);
		assertFalse(PacketCodecCapabilities.canSendToClient(modern));
		assertFalse(PacketCodecCapabilities.canSendToServer());
		advertisement.addProperty("_mtrPacketCodec", "1");
		assertFalse(PacketCodecCapabilities.supports(advertisement));
	}
}
