package mtr.packet;

import mtr.block.BlockRailwaySign;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Station;
import mtr.gui.ClientData;
import mtr.gui.DashboardScreen;
import mtr.gui.RailwaySignScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class PacketTrainDataGuiClient implements IPacket {

	public static void openDashboardScreenS2C(MinecraftClient minecraftClient, PacketByteBuf packet) {
		receiveAll(packet);
		minecraftClient.execute(() -> {
			if (!(minecraftClient.currentScreen instanceof DashboardScreen)) {
				minecraftClient.openScreen(new DashboardScreen());
			}
		});
	}

	public static void openRailwaySignScreenS2C(MinecraftClient minecraftClient, PacketByteBuf packet) {
		receiveAll(packet);
		final BlockPos pos = packet.readBlockPos();
		minecraftClient.execute(() -> {
			if (!(minecraftClient.currentScreen instanceof RailwaySignScreen)) {
				minecraftClient.openScreen(new RailwaySignScreen(pos));
			}
		});
	}

	public static void sendAllC2S() {
		final PacketByteBuf packet = IPacket.sendAll(ClientData.stations, ClientData.platforms, ClientData.routes);
		ClientPlayNetworking.send(ID_ALL, packet);
	}

	public static void sendSignTypesC2S(BlockPos signPos, int platformRouteIndex, BlockRailwaySign.SignType[] signTypes) {
		final PacketByteBuf packet = PacketByteBufs.create();
		packet.writeBlockPos(signPos);
		packet.writeInt(platformRouteIndex);
		packet.writeInt(signTypes.length);
		for (final BlockRailwaySign.SignType signType : signTypes) {
			packet.writeString(signType == null ? "" : signType.toString());
		}
		ClientPlayNetworking.send(ID_SIGN_TYPES, packet);
	}

	public static void receiveAll(PacketByteBuf packet) {
		ClientData.stations = IPacket.receiveData(packet, Station::new);
		ClientData.platforms = IPacket.receiveData(packet, Platform::new);
		ClientData.routes = IPacket.receiveData(packet, Route::new);

		ClientData.platformIdToStation = ClientData.platforms.stream().map(platform -> new Pair<>(platform.id, RailwayData.getStationByPlatform(ClientData.stations, platform))).filter(pair -> pair.getRight() != null).collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

		ClientData.platformsInStation.clear();
		ClientData.platforms.forEach(platform -> {
			final Station station = RailwayData.getStationByPlatform(ClientData.stations, platform);
			if (station != null) {
				if (!ClientData.platformsInStation.containsKey(station.id)) {
					ClientData.platformsInStation.put(station.id, new ArrayList<>());
				}
				ClientData.platformsInStation.get(station.id).add(platform);
			}
		});
		ClientData.platformsInStation.forEach((stationId, posList) -> Collections.sort(posList));

		ClientData.routesInStation.clear();
		ClientData.routes.forEach(route -> route.platformIds.forEach(platformId -> {
			final Station station = ClientData.platformIdToStation.get(platformId);
			if (station != null) {
				if (!ClientData.routesInStation.containsKey(station.id)) {
					ClientData.routesInStation.put(station.id, new ArrayList<>());
				}
				if (ClientData.routesInStation.get(station.id).stream().noneMatch(colorNamePair -> route.color == colorNamePair.color)) {
					ClientData.routesInStation.get(station.id).add(new ClientData.ColorNamePair(route.color, route.name.split("\\|\\|")[0]));
				}
			}
		}));
		ClientData.routesInStation.forEach((stationId, routeList) -> routeList.sort(Comparator.comparingInt(a -> a.color)));

		ClientData.stationNames = ClientData.stations.stream().collect(Collectors.toMap(station -> station.id, station -> station.name));
		ClientData.platformToRoute = ClientData.platforms.stream().collect(Collectors.toMap(platform -> platform, platform -> ClientData.routes.stream().filter(route -> route.platformIds.contains(platform.id)).map(route -> {
			final List<ClientData.PlatformRouteDetails.StationDetails> stationDetails = route.platformIds.stream().map(platformId -> {
				final Station station = ClientData.platformIdToStation.get(platformId);
				if (station == null) {
					return new ClientData.PlatformRouteDetails.StationDetails("", new ArrayList<>());
				} else {
					return new ClientData.PlatformRouteDetails.StationDetails(station.name, ClientData.routesInStation.get(station.id).stream().filter(colorNamePair -> colorNamePair.color != route.color).collect(Collectors.toList()));
				}
			}).collect(Collectors.toList());
			return new ClientData.PlatformRouteDetails(route.name.split("\\|\\|")[0], route.color, route.platformIds.indexOf(platform.id), stationDetails);
		}).collect(Collectors.toList())));
	}
}
