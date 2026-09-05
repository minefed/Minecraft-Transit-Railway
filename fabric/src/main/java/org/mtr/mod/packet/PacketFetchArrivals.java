package org.mtr.mod.packet;

import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.mod.data.ArrivalsCacheServer;

public final class PacketFetchArrivals extends PacketHandler {

	private final LongAVLTreeSet platformIds;
	private final long responseTime;
	private final ObjectArrayList<PacketPayload> responses = new ObjectArrayList<>();
	private final long callbackId;
	private final boolean binary;

	private static final PendingCallbackRegistry<Callback> CALLBACKS = new PendingCallbackRegistry<>();

	public PacketFetchArrivals(PacketBufferReceiver packetBufferReceiver) {
		binary = false;
		platformIds = new LongAVLTreeSet();
		final int platformIdCount = packetBufferReceiver.readInt();
		for (int i = 0; i < platformIdCount; i++) {
			platformIds.add(packetBufferReceiver.readLong());
		}

		responseTime = packetBufferReceiver.readLong();

		final int responseCount = packetBufferReceiver.readInt();
		for (int i = 0; i < responseCount; i++) {
			responses.add(PacketPayload.read(packetBufferReceiver));
		}

		callbackId = packetBufferReceiver.readLong();
	}

	public PacketFetchArrivals(LongAVLTreeSet platformIds, Callback callback) {
		binary = false;
		this.platformIds = platformIds;
		responseTime = 0;
		callbackId = CALLBACKS.register(callback);
	}

	private PacketFetchArrivals(long responseTime, ObjectArrayList<String> arrivalResponses, long callbackId, boolean binary) {
		this.binary = binary;
		platformIds = new LongAVLTreeSet();
		this.responseTime = responseTime;
		responses.ensureCapacity(arrivalResponses.size());
		for (final String arrivalResponse : arrivalResponses) {
			responses.add(new PacketPayload(arrivalResponse));
		}
		this.callbackId = callbackId;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(platformIds.size());
		platformIds.forEach(packetBufferSender::writeLong);
		packetBufferSender.writeLong(responseTime);
		packetBufferSender.writeInt(responses.size());
		responses.forEach(response -> response.write(packetBufferSender, binary));
		packetBufferSender.writeLong(callbackId);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final ArrivalsCacheServer instance = ArrivalsCacheServer.getInstance(serverPlayerEntity.getServerWorld());
		Init.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketFetchArrivals(instance.getMillisOffset() + System.currentTimeMillis(), instance.requestSerializedArrivals(platformIds), callbackId, PacketCodecCapabilities.canSendToClient(serverPlayerEntity.getUuid())));
	}

	@Override
	public void runClient() {
		final Callback callback = CALLBACKS.remove(callbackId);
		if (callback != null) {
			final ObjectArrayList<ArrivalResponse> arrivalResponses = new ObjectArrayList<>(responses.size());
			for (final PacketPayload response : responses) {
				arrivalResponses.add(new ArrivalResponse(new JsonReader(response.json())));
			}
			callback.accept(responseTime, arrivalResponses);
		}
	}

	public static void clearCallbacks() {
		CALLBACKS.clear();
	}

	@FunctionalInterface
	public interface Callback {
		void accept(long responseTime, ObjectList<ArrivalResponse> arrivalResponseList);
	}
}
