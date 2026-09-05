package org.mtr.mod.packet;

import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sends a round trip request/response. This is meant to be used on the Minecraft client only.
 * <p>
 * Minecraft client -> Minecraft server -> Transport Simulation Core -> Minecraft server -> Minecraft client
 */
public abstract class PacketRequestResponseBase extends PacketHandler {

	private final PacketPayload payload;
	private boolean binary;

	public PacketRequestResponseBase(PacketBufferReceiver packetBufferReceiver) {
		payload = PacketPayload.read(packetBufferReceiver);
	}

	public PacketRequestResponseBase(String content) {
		payload = new PacketPayload(content);
	}

	protected PacketRequestResponseBase(JsonObject content, boolean binary) {
		payload = new PacketPayload(content);
		this.binary = binary;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		payload.write(packetBufferSender, binary);
	}

	@Override
	public final void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		runServerOutbound(serverPlayerEntity.getServerWorld(), serverPlayerEntity);
	}

	@Override
	public final void runClient() {
		final JsonObject json = payload.json();
		if (this instanceof PacketRequestData) {
			PacketCodecCapabilities.receiveServer(json);
		}
		runClientInbound(new JsonReader(json));
	}

	protected void runServerOutbound(ServerWorld serverWorld, @Nullable ServerPlayerEntity serverPlayerEntity) {
		final ResponseType responseType = responseType();
		final JsonObject requestJson = payload.json();
		final boolean negotiate = this instanceof PacketRequestData && serverPlayerEntity != null;
		if (negotiate) {
			PacketCodecCapabilities.receiveClient(serverPlayerEntity.getUuid(), requestJson);
		}
		Init.sendMessageC2S(getKey(), serverWorld.getServer(), new World(serverWorld.data), getDataInstance(new JsonReader(requestJson)), responseType == ResponseType.NONE ? null : responseData -> {
			final JsonObject responseJson = Utilities.getJsonObjectFromData(responseData);
			final boolean negotiated = negotiate && PacketCodecCapabilities.canSendToClient(serverPlayerEntity.getUuid());
			if (negotiated) {
				PacketCodecCapabilities.advertise(responseJson);
			}
			final PacketRequestResponseBase responsePacket = getInstance(responseJson);
			responsePacket.binary = negotiated;
			if (responseType == ResponseType.PLAYER) {
				if (serverPlayerEntity != null) {
					Init.REGISTRY.sendPacketToClient(serverPlayerEntity, responsePacket);
				}
			} else {
				MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntityNew -> Init.REGISTRY.sendPacketToClient(serverPlayerEntityNew, responsePacket));
			}
			runServerInbound(serverWorld, responseJson);
		}, SerializedDataBase.class);
	}

	protected void runServerInbound(ServerWorld serverWorld, JsonObject jsonObject) {
	}

	protected void runClientInbound(JsonReader jsonReader) {
	}

	/**
	 * @param content the content being sent from the Minecraft server to the Minecraft client
	 * @return an instance of the packet (should be constructed using {@link #PacketRequestResponseBase(String)})
	 */
	protected abstract PacketRequestResponseBase getInstance(String content);

	protected PacketRequestResponseBase getInstance(JsonObject content) {
		return getInstance(content.toString());
	}

	protected abstract SerializedDataBase getDataInstance(JsonReader jsonReader);

	@Nonnull
	protected abstract String getKey();

	/**
	 * If a response is needed, override {@link #runClient()}.
	 *
	 * @return whether this request expects a response from the POST request
	 */
	protected abstract ResponseType responseType();

	protected enum ResponseType {
		NONE, PLAYER, ALL
	}
}
