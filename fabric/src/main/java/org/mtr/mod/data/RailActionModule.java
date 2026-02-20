package org.mtr.mod.data;

import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketBroadcastRailActions;

public class RailActionModule {

	private final ServerWorld serverWorld;
	private final ObjectArrayList<RailAction> railActions = new ObjectArrayList<>();

	public RailActionModule(ServerWorld serverWorld) {
		this.serverWorld = serverWorld;
	}

	public void tick() {
		if (!railActions.isEmpty() && railActions.get(0).build()) {
			final long removedRailActionId = railActions.get(0).id;
			railActions.remove(0);
			broadcastRemove(removedRailActionId);
		}
	}

	public void markRailForBridge(Rail rail, ServerPlayerEntity serverPlayerEntity, int radius, BlockState blockState) {
		final RailAction railAction = new RailAction(serverWorld, serverPlayerEntity, RailActionType.BRIDGE, rail, radius, 0, blockState);
		railActions.add(railAction);
		broadcastAdd(railAction);
	}

	public void markRailForTunnel(Rail rail, ServerPlayerEntity serverPlayerEntity, int radius, int height) {
		final RailAction railAction = new RailAction(serverWorld, serverPlayerEntity, RailActionType.TUNNEL, rail, radius, height, null);
		railActions.add(railAction);
		broadcastAdd(railAction);
	}

	public void markRailForTunnelWall(Rail rail, ServerPlayerEntity serverPlayerEntity, int radius, int height, BlockState blockState) {
		final RailAction railAction = new RailAction(serverWorld, serverPlayerEntity, RailActionType.TUNNEL_WALL, rail, radius + 1, height + 1, blockState);
		railActions.add(railAction);
		broadcastAdd(railAction);
	}

	public void removeRailAction(long id) {
		final boolean removed = railActions.removeIf(railAction -> railAction.id == id);
		if (removed) {
			broadcastRemove(id);
		}
	}

	private void broadcastUpdate() {
		final PacketBroadcastRailActions packet = new PacketBroadcastRailActions(railActions);
		MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntity -> Init.REGISTRY.sendPacketToClient(serverPlayerEntity, packet));
	}

	private void broadcastAdd(RailAction railAction) {
		final PacketBroadcastRailActions packet = PacketBroadcastRailActions.add(railAction);
		MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntity -> Init.REGISTRY.sendPacketToClient(serverPlayerEntity, packet));
	}

	private void broadcastRemove(long railActionId) {
		final PacketBroadcastRailActions packet = PacketBroadcastRailActions.remove(railActionId);
		MinecraftServerHelper.iteratePlayers(serverWorld, serverPlayerEntity -> Init.REGISTRY.sendPacketToClient(serverPlayerEntity, packet));
	}
}
