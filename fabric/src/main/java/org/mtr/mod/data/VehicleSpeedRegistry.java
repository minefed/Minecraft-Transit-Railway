package org.mtr.mod.data;

import org.mtr.libraries.com.google.gson.GsonBuilder;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2DoubleAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.mod.Init;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Registry that provides vehicle max speeds for server-side access.
 * Loads vehicle speeds from server config file (config/mtr-speed-limit.json).
 */
public class VehicleSpeedRegistry {

	// Map of vehicleId to maxSpeed in m/ms
	private static final Object2DoubleAVLTreeMap<String> VEHICLE_SPEEDS = new Object2DoubleAVLTreeMap<>();
	private static Path configFilePath;

	static {
		// Default value for unknown vehicles
		VEHICLE_SPEEDS.defaultReturnValue(-1);
	}

	/**
	 * Initialize the registry by loading vehicle data from server config file.
	 * This should be called during mod initialization after Config.init().
	 *
	 * @param baseFolder The server run directory (same as passed to Config.init)
	 */
	public static void init(File baseFolder) {
		VEHICLE_SPEEDS.clear();

		if (baseFolder == null) {
			Init.LOGGER.warn("[MTR-SpeedLimit] Base folder is null, cannot load config");
			return;
		}

		configFilePath = baseFolder.toPath().resolve("config/mtr-speed-limit.json");

		// Create config file if it doesn't exist
		if (!Files.exists(configFilePath)) {
			createDefaultConfig();
		}

		// Load config file
		loadConfigFile();

		Init.LOGGER.info("[MTR-SpeedLimit] Loaded {} vehicle speeds from config", VEHICLE_SPEEDS.size());
	}

	/**
	 * Creates a default config file with vehicle speeds from mtr_custom_resources.json.
	 */
	private static void createDefaultConfig() {
		try {
			// Ensure parent directory exists
			Files.createDirectories(configFilePath.getParent());

			JsonObject root = new JsonObject();
			root.addProperty("_comment", "Vehicle speed limits in km/h. Use vehicle ID as key (e.g., 'mtr:sp1900'). Set to -1 or remove entry for no limit.");
			root.addProperty("version", 1);

			// Read vehicles from mtr_custom_resources.json in the JAR
			JsonObject vehicles = loadVehicleSpeedsFromResources();

			root.add("vehicles", vehicles);

			String jsonString = new GsonBuilder().setPrettyPrinting().create().toJson(root);
			Files.writeString(configFilePath, jsonString, StandardCharsets.UTF_8);

			Init.LOGGER.info("[MTR-SpeedLimit] Created default config file at {} with {} vehicles", configFilePath, vehicles.size());
		} catch (Exception e) {
			Init.LOGGER.error("[MTR-SpeedLimit] Failed to create default config file", e);
		}
	}

	/**
	 * Load vehicle speeds from mtr_custom_resources.json in the mod's JAR.
	 * Extracts base vehicle IDs and their max speeds.
	 */
	private static JsonObject loadVehicleSpeedsFromResources() {
		// Use a map to collect unique base vehicle IDs and their speeds
		Object2ObjectAVLTreeMap<String, Double> vehicleSpeedMap = new Object2ObjectAVLTreeMap<>();

		try (InputStream inputStream = VehicleSpeedRegistry.class.getClassLoader().getResourceAsStream("assets/mtr/mtr_custom_resources.json")) {
			if (inputStream != null) {
				JsonObject customResources = JsonParser.parseReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)).getAsJsonObject();

				if (customResources.has("vehicles")) {
					JsonArray vehiclesArray = customResources.getAsJsonArray("vehicles");

					for (JsonElement vehicleElement : vehiclesArray) {
						JsonObject vehicleObj = vehicleElement.getAsJsonObject();

						if (vehicleObj.has("id") && vehicleObj.has("maxSpeedKilometersPerHour")) {
							String fullId = vehicleObj.get("id").getAsString();
							double maxSpeed = vehicleObj.get("maxSpeedKilometersPerHour").getAsDouble();

							// Extract base vehicle ID (e.g., "sp1900_cab_1" -> "sp1900")
							String baseId = extractBaseVehicleId(fullId);

							// Only add if not already present or if this is the first occurrence
							if (!vehicleSpeedMap.containsKey(baseId) && maxSpeed > 0) {
								vehicleSpeedMap.put(baseId, maxSpeed);
							}
						}
					}
				}

				Init.LOGGER.info("[MTR-SpeedLimit] Found {} unique base vehicles in mtr_custom_resources.json", vehicleSpeedMap.size());
			} else {
				Init.LOGGER.warn("[MTR-SpeedLimit] Could not find mtr_custom_resources.json in resources");
			}
		} catch (Exception e) {
			Init.LOGGER.error("[MTR-SpeedLimit] Failed to load vehicles from mtr_custom_resources.json", e);
		}

		// Convert map to JsonObject (without namespace prefix to match runtime IDs)
		JsonObject vehicles = new JsonObject();
		vehicleSpeedMap.forEach((baseId, speed) -> {
			vehicles.addProperty(baseId, speed.intValue());
		});

		return vehicles;
	}

	/**
	 * Extract the base vehicle ID from a full vehicle ID.
	 * For example: "sp1900_cab_1" -> "sp1900", "sp1900_small_trailer" -> "sp1900"
	 */
	private static String extractBaseVehicleId(String fullId) {
		// Common suffixes and size modifiers to remove
		final String[] sizeSuffixes = {"_mini", "_small"};
		final String[] typeSuffixes = {"_trailer", "_cab_1", "_cab_2", "_cab_3", "_head", "_tail", "_middle"};

		String baseId = fullId;

		// Remove size suffix first (e.g., "_small", "_mini")
		for (String suffix : sizeSuffixes) {
			if (baseId.contains(suffix)) {
				baseId = baseId.replace(suffix, "");
				break;
			}
		}

		// Remove type suffix (e.g., "_cab_1", "_trailer")
		for (String suffix : typeSuffixes) {
			if (baseId.endsWith(suffix)) {
				baseId = baseId.substring(0, baseId.length() - suffix.length());
				break;
			}
		}

		return baseId;
	}

	/**
	 * Loads vehicle speeds from the config file.
	 */
	private static void loadConfigFile() {
		try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configFilePath), StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

			if (!root.has("vehicles")) {
				Init.LOGGER.warn("[MTR-SpeedLimit] Config file missing 'vehicles' object");
				return;
			}

			JsonObject vehicles = root.getAsJsonObject("vehicles");

			for (Map.Entry<String, JsonElement> entry : vehicles.entrySet()) {
				String vehicleId = entry.getKey();
				double speedKmh = entry.getValue().getAsDouble();

				if (speedKmh > 0) {
					// Convert km/h to m/ms
					double speedMms = speedKmh / 3600000.0;
					VEHICLE_SPEEDS.put(vehicleId, speedMms);
					Init.LOGGER.debug("[MTR-SpeedLimit] Registered {} = {} km/h ({} m/ms)", vehicleId, speedKmh, speedMms);
				}
			}
		} catch (Exception e) {
			Init.LOGGER.error("[MTR-SpeedLimit] Failed to load config file", e);
		}
	}

	/**
	 * Get the max speed for a vehicle by its ID.
	 *
	 * @param vehicleId The vehicle ID (e.g., "sp1900_cab_1")
	 * @return The max speed in m/ms, or -1 if not found
	 */
	public static double getMaxSpeed(String vehicleId) {
		// Try exact match first
		double speed = VEHICLE_SPEEDS.getDouble(vehicleId);
		if (speed >= 0) {
			return speed;
		}

		// Try without the suffix (e.g., "sp1900_cab_1" -> "sp1900")
		// Vehicle IDs typically have suffixes like _cab_1, _cab_2, _trailer
		final String baseId = getBaseVehicleId(vehicleId);
		if (!baseId.equals(vehicleId)) {
			speed = VEHICLE_SPEEDS.getDouble(baseId);
			if (speed >= 0) {
				return speed;
			}
		}

		return -1;
	}

	/**
	 * Extract the base vehicle ID by removing common suffixes.
	 * For example: "mtr:sp1900_small_cab_1" -> "mtr:sp1900"
	 */
	private static String getBaseVehicleId(String vehicleId) {
		// Size suffixes to strip first (e.g., "_small", "_mini")
		final String[] sizeSuffixes = {"_mini", "_small"};
		// Type suffixes to strip (e.g., "_cab_1", "_trailer")
		final String[] typeSuffixes = {"_cab_1", "_cab_2", "_cab_3", "_trailer", "_head", "_tail", "_middle", "_lht", "_rht"};

		String baseId = vehicleId;

		// First, remove size suffix if present (anywhere in the string, not just at the end)
		for (final String suffix : sizeSuffixes) {
			if (baseId.contains(suffix)) {
				baseId = baseId.replace(suffix, "");
				break;
			}
		}

		// Then, remove type suffix if present at the end
		for (final String suffix : typeSuffixes) {
			if (baseId.endsWith(suffix)) {
				baseId = baseId.substring(0, baseId.length() - suffix.length());
				break;
			}
		}

		return baseId;
	}

	/**
	 * Register a vehicle speed programmatically.
	 * Can be called by other mods or datapacks to add custom vehicle speeds.
	 *
	 * @param vehicleId   The vehicle ID
	 * @param maxSpeedKmh The max speed in km/h
	 */
	public static void registerVehicleSpeed(String vehicleId, double maxSpeedKmh) {
		if (maxSpeedKmh > 0) {
			VEHICLE_SPEEDS.put(vehicleId, maxSpeedKmh / 3600000.0);
		}
	}

	/**
	 * Reload the config file. Can be called to hot-reload speeds without server restart.
	 */
	public static void reload() {
		if (configFilePath != null && Files.exists(configFilePath)) {
			VEHICLE_SPEEDS.clear();
			loadConfigFile();
			Init.LOGGER.info("[MTR-SpeedLimit] Reloaded {} vehicle speeds from config", VEHICLE_SPEEDS.size());
		}
	}

	/**
	 * Get the max speed in km/h for a vehicle by its ID.
	 * This is a convenience method for UI display.
	 *
	 * @param vehicleId The vehicle ID (e.g., "mtr:sp1900")
	 * @return The max speed in km/h, or -1 if not found/unlimited
	 */
	public static double getMaxSpeedKilometersPerHour(String vehicleId) {
		double speedMms = getMaxSpeed(vehicleId);
		if (speedMms > 0) {
			return speedMms * 3600000.0;
		}
		return -1;
	}

	/**
	 * Get all registered vehicle speeds as a JsonObject for sync.
	 * Used by server to send speed data to clients.
	 *
	 * @return JsonObject with vehicleId -> speedKmh mappings
	 */
	public static JsonObject getSpeedsAsJson() {
		JsonObject vehicles = new JsonObject();
		VEHICLE_SPEEDS.object2DoubleEntrySet().forEach(entry -> {
			vehicles.addProperty(entry.getKey(), entry.getDoubleValue() * 3600000.0);
		});
		return vehicles;
	}

	/**
	 * Load vehicle speeds from a JsonObject.
	 * Used by client to receive speed data from server.
	 *
	 * @param vehiclesJson JsonObject with vehicleId -> speedKmh mappings
	 */
	public static void loadFromJson(JsonObject vehiclesJson) {
		VEHICLE_SPEEDS.clear();
		for (Map.Entry<String, JsonElement> entry : vehiclesJson.entrySet()) {
			String vehicleId = entry.getKey();
			double speedKmh = entry.getValue().getAsDouble();
			if (speedKmh > 0) {
				VEHICLE_SPEEDS.put(vehicleId, speedKmh / 3600000.0);
			}
		}
		Init.LOGGER.info("[MTR-SpeedLimit] Client received {} vehicle speeds from server", VEHICLE_SPEEDS.size());
	}

	/**
	 * Check if the registry has been initialized with any data.
	 *
	 * @return true if at least one speed is registered
	 */
	public static boolean isInitialized() {
		return !VEHICLE_SPEEDS.isEmpty();
	}
}
