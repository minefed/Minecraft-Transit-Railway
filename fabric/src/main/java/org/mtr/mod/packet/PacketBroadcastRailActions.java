package org.mtr.mod.packet;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.RailAction;
import org.mtr.mod.screen.DashboardListItem;

public final class PacketBroadcastRailActions extends PacketHandler {

	private final ObjectArrayList<RailAction> railActions;
	private final ObjectArrayList<DashboardListItem> dashboardListItems;
	private final Operation operation;
	private final long railActionId;
	private final String railActionDescription;
	private final int railActionColor;

	public PacketBroadcastRailActions(PacketBufferReceiver packetBufferReceiver) {
		operation = Operation.values()[packetBufferReceiver.readInt()];
		railActions = new ObjectArrayList<>();
		dashboardListItems = new ObjectArrayList<>();
		switch (operation) {
			case FULL:
				final int actionCount = packetBufferReceiver.readInt();
				for (int i = 0; i < actionCount; i++) {
					dashboardListItems.add(new DashboardListItem(packetBufferReceiver.readLong(), packetBufferReceiver.readString(), packetBufferReceiver.readInt()));
				}
				railActionId = 0;
				railActionDescription = "";
				railActionColor = 0;
				break;
			case ADD:
				railActionId = packetBufferReceiver.readLong();
				railActionDescription = packetBufferReceiver.readString();
				railActionColor = packetBufferReceiver.readInt();
				break;
			case REMOVE:
				railActionId = packetBufferReceiver.readLong();
				railActionDescription = "";
				railActionColor = 0;
				break;
			default:
				railActionId = 0;
				railActionDescription = "";
				railActionColor = 0;
				break;
		}
	}

	public PacketBroadcastRailActions(ObjectArrayList<RailAction> railActions) {
		this.railActions = railActions;
		dashboardListItems = new ObjectArrayList<>();
		operation = Operation.FULL;
		railActionId = 0;
		railActionDescription = "";
		railActionColor = 0;
	}

	public static PacketBroadcastRailActions add(RailAction railAction) {
		return new PacketBroadcastRailActions(Operation.ADD, railAction.id, railAction.getDescription(), railAction.getColor());
	}

	public static PacketBroadcastRailActions remove(long railActionId) {
		return new PacketBroadcastRailActions(Operation.REMOVE, railActionId, "", 0);
	}

	private PacketBroadcastRailActions(Operation operation, long railActionId, String railActionDescription, int railActionColor) {
		this.railActions = new ObjectArrayList<>();
		this.dashboardListItems = new ObjectArrayList<>();
		this.operation = operation;
		this.railActionId = railActionId;
		this.railActionDescription = railActionDescription;
		this.railActionColor = railActionColor;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(operation.ordinal());
		switch (operation) {
			case FULL:
				packetBufferSender.writeInt(railActions.size());
				for (final RailAction railAction : railActions) {
					packetBufferSender.writeLong(railAction.id);
					packetBufferSender.writeString(railAction.getDescription());
					packetBufferSender.writeInt(railAction.getColor());
				}
				break;
			case ADD:
				packetBufferSender.writeLong(railActionId);
				packetBufferSender.writeString(railActionDescription);
				packetBufferSender.writeInt(railActionColor);
				break;
			case REMOVE:
				packetBufferSender.writeLong(railActionId);
				break;
			default:
				break;
		}
	}

	@Override
	public void runClient() {
		switch (operation) {
			case FULL:
				MinecraftClientData.getInstance().railActions.clear();
				MinecraftClientData.getInstance().railActions.addAll(dashboardListItems);
				break;
			case ADD:
				MinecraftClientData.getInstance().railActions.removeIf(dashboardListItem -> dashboardListItem.id == railActionId);
				MinecraftClientData.getInstance().railActions.add(new DashboardListItem(railActionId, railActionDescription, railActionColor));
				break;
			case REMOVE:
				MinecraftClientData.getInstance().railActions.removeIf(dashboardListItem -> dashboardListItem.id == railActionId);
				break;
			default:
				break;
		}
	}

	private enum Operation {
		FULL,
		ADD,
		REMOVE
	}
}
