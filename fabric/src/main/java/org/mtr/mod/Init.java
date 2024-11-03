package org.mtr.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.core.Main;
import org.mtr.core.data.Position;
import org.mtr.core.operation.GenerateOrClearByDepotName;
import org.mtr.core.operation.SetTime;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GameRule;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.mapper.WorldHelper;
import org.mtr.mapping.registry.CommandBuilder;
import org.mtr.mapping.registry.Registry;
import org.mtr.mapping.tool.DummyClass;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.ArrivalsCacheServer;
import org.mtr.mod.data.RailActionModule;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.*;
import org.mtr.mod.servlet.MinecraftOperationProcessor;
import org.mtr.mod.servlet.RequestHelper;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class Init implements Utilities {

	private static Main main;
	private static int serverPort;
	private static Runnable sendWorldTimeUpdate;
	private static boolean canSendWorldTimeUpdate = true;
	private static int serverTick;
	private static long lastSavedMillis;

	public static final String MOD_ID = "mtr";
	public static final String MOD_ID_NTE = "mtrsteamloco";
	public static final Logger LOGGER = LogManager.getLogger("MinecraftTransitRailway");
	public static final Registry REGISTRY = new Registry();
	public static final int SECONDS_PER_MC_HOUR = 50;
	public static final int AUTOSAVE_INTERVAL = 30000;
	public static final RequestHelper REQUEST_HELPER = new RequestHelper();

	private static final int MILLIS_PER_MC_DAY = SECONDS_PER_MC_HOUR * MILLIS_PER_SECOND * HOURS_PER_DAY;
	private static final Object2ObjectArrayMap<ServerWorld, RailActionModule> RAIL_ACTION_MODULES = new Object2ObjectArrayMap<>();
	private static final ObjectArrayList<String> WORLD_ID_LIST = new ObjectArrayList<>();

	public static void init() {
		AsciiArt.print();
		Blocks.init();
		Items.init();
		BlockEntityTypes.init();
		EntityTypes.init();
		CreativeModeTabs.init();
		SoundEvents.init();
		DummyClass.enableLogging();

		// Register packets
		REGISTRY.setupPackets(new Identifier(MOD_ID, "packet"));
		REGISTRY.registerPacket(PacketAddBalance.class, PacketAddBalance::new);
		REGISTRY.registerPacket(PacketBroadcastRailActions.class, PacketBroadcastRailActions::new);
		REGISTRY.registerPacket(PacketDeleteData.class, PacketDeleteData::new);
		REGISTRY.registerPacket(PacketDeleteRailAction.class, PacketDeleteRailAction::new);
		REGISTRY.registerPacket(PacketDepotClear.class, PacketDepotClear::new);
		REGISTRY.registerPacket(PacketDepotGenerate.class, PacketDepotGenerate::new);
		REGISTRY.registerPacket(PacketDriveTrain.class, PacketDriveTrain::new);
		REGISTRY.registerPacket(PacketFetchArrivals.class, PacketFetchArrivals::new);
		REGISTRY.registerPacket(PacketForwardClientRequest.class, PacketForwardClientRequest::new);
		REGISTRY.registerPacket(PacketOpenBlockEntityScreen.class, PacketOpenBlockEntityScreen::new);
		REGISTRY.registerPacket(PacketOpenDashboardScreen.class, PacketOpenDashboardScreen::new);
		REGISTRY.registerPacket(PacketOpenLiftCustomizationScreen.class, PacketOpenLiftCustomizationScreen::new);
		REGISTRY.registerPacket(PacketOpenPIDSConfigScreen.class, PacketOpenPIDSConfigScreen::new);
		REGISTRY.registerPacket(PacketOpenTicketMachineScreen.class, PacketOpenTicketMachineScreen::new);
		REGISTRY.registerPacket(PacketPressLiftButton.class, PacketPressLiftButton::new);
		REGISTRY.registerPacket(PacketRequestData.class, PacketRequestData::new);
		REGISTRY.registerPacket(PacketTurnOnBlockEntity.class, PacketTurnOnBlockEntity::new);
		REGISTRY.registerPacket(PacketUpdateData.class, PacketUpdateData::new);
		REGISTRY.registerPacket(PacketUpdateEyeCandyConfig.class, PacketUpdateEyeCandyConfig::new);
		REGISTRY.registerPacket(PacketUpdateLastRailStyles.class, PacketUpdateLastRailStyles::new);
		REGISTRY.registerPacket(PacketUpdateLiftTrackFloorConfig.class, PacketUpdateLiftTrackFloorConfig::new);
		REGISTRY.registerPacket(PacketUpdatePIDSConfig.class, PacketUpdatePIDSConfig::new);
		REGISTRY.registerPacket(PacketUpdateRailwaySignConfig.class, PacketUpdateRailwaySignConfig::new);
		REGISTRY.registerPacket(PacketUpdateSignalConfig.class, PacketUpdateSignalConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainAnnouncerConfig.class, PacketUpdateTrainAnnouncerConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainScheduleSensorConfig.class, PacketUpdateTrainScheduleSensorConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainSensorConfig.class, PacketUpdateTrainSensorConfig::new);
		REGISTRY.registerPacket(PacketUpdateVehiclesLifts.class, PacketUpdateVehiclesLifts::new);
		REGISTRY.registerPacket(PacketUpdateVehicleRidingEntities.class, PacketUpdateVehicleRidingEntities::new);

		// Register command
		REGISTRY.registerCommand("mtr", commandBuilderMtr -> {
			// Generate depot(s) by name
			commandBuilderMtr.then("generate", commandBuilderGenerate -> generateOrClearDepotsFromCommand(commandBuilderGenerate, true));
			// Clear depot(s) by name
			commandBuilderMtr.then("clear", commandBuilderClear -> generateOrClearDepotsFromCommand(commandBuilderClear, false));
			// Force copy a world backup from one folder another
			commandBuilderMtr.then("forceCopyWorld", commandBuilderForceCopy -> {
				commandBuilderForceCopy.permissionLevel(4);
				commandBuilderForceCopy.then("worldDirectory", StringArgumentType.string(), innerCommandBuilder1 -> innerCommandBuilder1.then("backupDirectory", StringArgumentType.string(), innerCommandBuilder2 -> innerCommandBuilder2.executes(contextHandler -> {
					final Path runPath = contextHandler.getServer().getRunDirectory().toPath();
					final Path worldDirectory = runPath.resolve(contextHandler.getString("worldDirectory"));
					final Path backupDirectory = runPath.resolve(contextHandler.getString("backupDirectory"));
					final boolean worldDirectoryExists = Files.isDirectory(worldDirectory);
					final boolean backupDirectoryExists = Files.isDirectory(backupDirectory);
					if (worldDirectoryExists && backupDirectoryExists) {
						try {
							if (main != null) {
								main.stop();
							}
							contextHandler.sendSuccess(String.format("Restoring world backup from %s to %s...", backupDirectory, worldDirectory), true);
							FileUtils.deleteDirectory(worldDirectory.toFile());
							contextHandler.sendSuccess("Deleting world complete", true);
							FileUtils.copyDirectory(backupDirectory.toFile(), worldDirectory.toFile());
							contextHandler.sendSuccess("Restoring world backup complete", true);
							System.exit(0);
							return 1;
						} catch (Exception e) {
							contextHandler.sendFailure("Restoring world backup failed");
							LOGGER.error("", e);
							return -1;
						}
					} else {
						if (backupDirectoryExists) {
							contextHandler.sendFailure("World directory not found");
						} else if (worldDirectoryExists) {
							contextHandler.sendFailure("Backup directory not found");
						} else {
							contextHandler.sendFailure("Directories not found");
						}
						return -1;
					}
				})));
			});
		}, "minecrafttransitrailway");

		// Register events
		REGISTRY.eventRegistry.registerServerStarted(minecraftServer -> {
			// Start up the backend
			RAIL_ACTION_MODULES.clear();
			WORLD_ID_LIST.clear();
			MinecraftServerHelper.iterateWorlds(minecraftServer, serverWorld -> {
				RAIL_ACTION_MODULES.put(serverWorld, new RailActionModule(serverWorld));
				WORLD_ID_LIST.add(getWorldId(new World(serverWorld.data)));
			});

			Config.init(minecraftServer.getRunDirectory());
			final int defaultPort = Config.getServer().getWebserverPort();
			serverPort = defaultPort <= 0 ? -1 : findFreePort(defaultPort);
			main = new Main(minecraftServer.getSavePath(WorldSavePath.getRootMapped()).resolve("mtr"), serverPort, Config.getServer().getUseThreadedSimulation(), WORLD_ID_LIST.toArray(new String[0]));

			serverTick = 0;
			lastSavedMillis = System.currentTimeMillis();
			sendWorldTimeUpdate = () -> {
				if (canSendWorldTimeUpdate) {
					canSendWorldTimeUpdate = false;
					sendMessageC2S(
							OperationProcessor.SET_TIME,
							minecraftServer,
							null,
							new SetTime(
									(WorldHelper.getTimeOfDay(minecraftServer.getOverworld()) + 6000) * SECONDS_PER_MC_HOUR,
									MILLIS_PER_MC_DAY,
									GameRule.DO_DAYLIGHT_CYCLE.getBooleanGameRule(minecraftServer)
							),
							response -> canSendWorldTimeUpdate = true,
							SerializedDataBase.class
					);
				} else {
					Main.LOGGER.error("Transport Simulation Core not responding; stopping Minecraft server!");
					minecraftServer.stop(false);
					canSendWorldTimeUpdate = true; // In singleplayer, this gives the player opportunity to re-enter world.
				}
			};
		});

		REGISTRY.eventRegistry.registerServerStopping(minecraftServer -> {
			if (main != null) {
				main.stop();
			}
			serverPort = 0;
		});

		REGISTRY.eventRegistry.registerStartServerTick(() -> {
			if (sendWorldTimeUpdate != null && serverTick % (SECONDS_PER_MC_HOUR * 10) == 0) {
				sendWorldTimeUpdate.run();
			}

			ArrivalsCacheServer.tickAll();
			serverTick++;

			if (main != null) {
				if (!Config.getServer().getUseThreadedSimulation()) {
					main.manualTick();
				}

				final long currentMillis = System.currentTimeMillis();
				if (currentMillis - lastSavedMillis > AUTOSAVE_INTERVAL) {
					main.save();
					lastSavedMillis = currentMillis;
				}
			}
		});

		REGISTRY.eventRegistry.registerEndWorldTick(serverWorld -> {
			final RailActionModule railActionModule = RAIL_ACTION_MODULES.get(serverWorld);
			if (railActionModule != null) {
				railActionModule.tick();
			}

			if (main != null) {
				final String dimension = getWorldId(new World(serverWorld.data));
				main.processMessagesS2C(WORLD_ID_LIST.indexOf(dimension), queueObject -> MinecraftOperationProcessor.process(queueObject, serverWorld, dimension));
			}
		});

		// Finish registration
		REGISTRY.init();
	}

	public static void getRailActionModule(ServerWorld serverWorld, Consumer<RailActionModule> consumer) {
		final RailActionModule railActionModule = RAIL_ACTION_MODULES.get(serverWorld);
		if (railActionModule != null) {
			consumer.accept(railActionModule);
		}
	}

	/**
	 * @return the port of the webserver started by Transport Simulation Core, not the clientside webserver.
	 * <br>{@code 0} means the integrated server is not running
	 * <br>{@code -1} means the webserver is disabled
	 */
	public static int getServerPort() {
		return serverPort;
	}

	public static <T extends SerializedDataBase> void sendMessageC2S(String key, @Nullable MinecraftServer minecraftServer, @Nullable World world, SerializedDataBase data, @Nullable Consumer<T> consumer, @Nullable Class<T> responseDataClass) {
		if (main != null) {
			main.sendMessageC2S(world == null ? null : WORLD_ID_LIST.indexOf(getWorldId(world)), new QueueObject(key, data, consumer == null || minecraftServer == null ? null : responseData -> minecraftServer.execute(() -> consumer.accept(responseData)), responseDataClass));
		}
	}

	public static BlockPos positionToBlockPos(Position position) {
		return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
	}

	public static Position blockPosToPosition(BlockPos blockPos) {
		return new Position(blockPos.getX(), blockPos.getY(), blockPos.getZ());
	}

	public static BlockPos newBlockPos(double x, double y, double z) {
		return new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
	}

	public static boolean isChunkLoaded(World world, BlockPos blockPos) {
		return world.getChunkManager().getWorldChunk(blockPos.getX() / 16, blockPos.getZ() / 16) != null && world.isRegionLoaded(blockPos, blockPos);
	}

	public static String getWorldId(World world) {
		final Identifier identifier = MinecraftServerHelper.getWorldId(world);
		return String.format("%s/%s", identifier.getNamespace(), identifier.getPath());
	}

	public static int findFreePort(int startingPort) {
		for (int i = Math.max(1024, startingPort); i <= 65535; i++) {
			// Start with port 80, then search from 1025 onwards
			try (final ServerSocket serverSocket = new ServerSocket(i == 1024 ? 80 : i)) {
				final int port = serverSocket.getLocalPort();
				LOGGER.info("Found available port: {}", port);
				return port;
			} catch (Exception ignored) {
			}
		}
		return 0;
	}

	public static void openConnectionSafe(String url, Consumer<InputStream> callback, String... requestProperties) {
		try {
			final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setUseCaches(false);

			for (int i = 0; i < requestProperties.length / 2; i++) {
				connection.setRequestProperty(requestProperties[2 * i], requestProperties[2 * i + 1]);
			}

			try (final InputStream inputStream = connection.getInputStream()) {
				callback.accept(inputStream);
			} catch (Exception e) {
				Init.LOGGER.error("", e);
			}
		} catch (Exception e) {
			Init.LOGGER.error("", e);
		}
	}

	public static void openConnectionSafeJson(String url, Consumer<JsonElement> callback, String... requestProperties) {
		openConnectionSafe(url, inputStream -> {
			try (final InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
				callback.accept(JsonParser.parseReader(inputStreamReader));
			} catch (Exception e) {
				Init.LOGGER.error("", e);
			}
		}, requestProperties);
	}

	private static void generateOrClearDepotsFromCommand(CommandBuilder<?> commandBuilder, boolean isGenerate) {
		commandBuilder.permissionLevel(2);
		commandBuilder.executes(contextHandler -> {
			contextHandler.sendSuccess((isGenerate ? TranslationProvider.COMMAND_MTR_GENERATE_ALL : TranslationProvider.COMMAND_MTR_CLEAR_ALL).key, true);
			return generateOrClearDepotsFromCommand(contextHandler.getWorld(), "", isGenerate);
		});
		commandBuilder.then("name", StringArgumentType.greedyString(), innerCommandBuilder -> innerCommandBuilder.executes(contextHandler -> {
			final String filter = contextHandler.getString("name");
			contextHandler.sendSuccess((isGenerate ? TranslationProvider.COMMAND_MTR_GENERATE_FILTER : TranslationProvider.COMMAND_MTR_CLEAR_FILTER).key, true, filter);
			return generateOrClearDepotsFromCommand(contextHandler.getWorld(), filter, isGenerate);
		}));
	}

	private static int generateOrClearDepotsFromCommand(World world, String filter, boolean isGenerate) {
		final GenerateOrClearByDepotName generateByDepotName = new GenerateOrClearByDepotName();
		generateByDepotName.setFilter(filter);
		sendMessageC2S(isGenerate ? OperationProcessor.GENERATE_BY_DEPOT_NAME : OperationProcessor.CLEAR_BY_DEPOT_NAME, world.getServer(), world, generateByDepotName, null, null);
		return 1;
	}
}
