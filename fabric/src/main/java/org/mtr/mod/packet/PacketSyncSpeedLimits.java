package org.mtr.mod.packet;

import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.data.VehicleSpeedRegistry;

/**
 * Packet to synchronize vehicle speed limits from server to client.
 * Sent when a player joins the server.
 */
public final class PacketSyncSpeedLimits extends PacketHandler {

	private final String jsonContent;

	/**
	 * Constructor for receiving the packet on client.
	 */
	public PacketSyncSpeedLimits(PacketBufferReceiver packetBufferReceiver) {
		this.jsonContent = packetBufferReceiver.readString();
	}

	/**
	 * Constructor for sending the packet from server.
	 */
	public PacketSyncSpeedLimits() {
		JsonObject speedsJson = VehicleSpeedRegistry.getSpeedsAsJson();
		this.jsonContent = speedsJson.toString();
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeString(jsonContent);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		// This packet is only sent from server to client, not the other way
	}

	@Override
	public void runClient() {
		try {
			JsonObject vehiclesJson = JsonParser.parseString(jsonContent).getAsJsonObject();
			VehicleSpeedRegistry.loadFromJson(vehiclesJson);
		} catch (Exception e) {
			Init.LOGGER.error("[MTR-SpeedLimit] Failed to parse speed limits from server", e);
		}
	}

	/**
	 * Send speed limits to a specific player (e.g., when they join).
	 */
	public static void sendToPlayer(ServerPlayerEntity serverPlayerEntity) {
		if (VehicleSpeedRegistry.isInitialized()) {
			Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketSyncSpeedLimits());
		}
	}
}
