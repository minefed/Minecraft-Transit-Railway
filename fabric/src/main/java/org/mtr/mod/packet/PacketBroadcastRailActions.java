package org.mtr.mod.packet;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.RailAction;
import org.mtr.mod.screen.DashboardListItem;

public final class PacketBroadcastRailActions extends PacketHandler {

	private final ObjectArrayList<DashboardListItem> dashboardListItems;

	public PacketBroadcastRailActions(PacketBufferReceiver packetBufferReceiver) {
		dashboardListItems = new ObjectArrayList<>();
		final int actionCount = packetBufferReceiver.readInt();
		for (int i = 0; i < actionCount; i++) {
			dashboardListItems.add(new DashboardListItem(packetBufferReceiver.readLong(), packetBufferReceiver.readString(), packetBufferReceiver.readInt()));
		}
	}

	public PacketBroadcastRailActions(Iterable<RailAction> railActions) {
		dashboardListItems = new ObjectArrayList<>();
		railActions.forEach(railAction -> dashboardListItems.add(new DashboardListItem(railAction.id, railAction.getDescription(), railAction.getColor())));
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(dashboardListItems.size());
		for (final DashboardListItem dashboardListItem : dashboardListItems) {
			packetBufferSender.writeLong(dashboardListItem.id);
			packetBufferSender.writeString(dashboardListItem.getName(false));
			packetBufferSender.writeInt(dashboardListItem.getColor(false));
		}
	}

	@Override
	public void runClient() {
		MinecraftClientData.getInstance().railActions.clear();
		MinecraftClientData.getInstance().railActions.addAll(dashboardListItems);
	}
}
