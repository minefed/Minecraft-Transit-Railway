package org.mtr.mod.packet;

import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Negotiation piggybacks on DataRequest fields ignored by older Core schemas. */
public final class PacketCodecCapabilities {

	private static final String KEY = "_mtrPacketCodec";
	private static final int VERSION = 1;
	private static final Set<UUID> CLIENTS = ConcurrentHashMap.newKeySet();
	private static volatile boolean serverSupportsBinary;

	static JsonObject advertise(JsonObject json) {
		json.addProperty(KEY, VERSION);
		return json;
	}

	static boolean supports(JsonObject json) {
		final JsonElement version = json.get(KEY);
		return version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber() && Integer.toString(VERSION).equals(version.getAsString());
	}

	static void receiveClient(UUID uuid, JsonObject json) {
		if (supports(json)) {
			CLIENTS.add(uuid);
		} else {
			CLIENTS.remove(uuid);
		}
	}

	static void receiveServer(JsonObject json) {
		serverSupportsBinary = supports(json);
	}

	public static boolean canSendToClient(UUID uuid) {
		return CLIENTS.contains(uuid);
	}

	static boolean canSendToServer() {
		return serverSupportsBinary;
	}

	public static void resetClient() {
		serverSupportsBinary = false;
	}

	public static void removeClient(UUID uuid) {
		CLIENTS.remove(uuid);
	}

	public static void resetServer() {
		CLIENTS.clear();
	}
}
