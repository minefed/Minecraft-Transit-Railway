package org.mtr.mod.render;

import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockRailwaySign;
import org.mtr.mod.block.BlockStationNameBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.resource.SignResource;
import org.mtr.mod.screen.EditStationScreen;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

public class RenderRailwaySign<T extends BlockRailwaySign.BlockEntity> extends BlockEntityRenderer<T> implements IBlock, IGui, IDrawing {

	private static final Identifier WHITE_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/white.png");
	private static final WeakHashMap<String[], SignLayout> SIGN_LAYOUT_CACHE = new WeakHashMap<>();
	private static int cachedResourceGeneration = -1;

	public RenderRailwaySign(Argument dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(T entity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = entity.getWorld2();
		if (world == null) {
			return;
		}

		final BlockPos pos = entity.getPos2();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock().data instanceof BlockRailwaySign)) {
			return;
		}
		final BlockRailwaySign block = (BlockRailwaySign) state.getBlock().data;
		final String[] currentSignIds = entity.getSignIds();
		if (currentSignIds.length != block.length) {
			return;
		}
		final Direction facing = IBlock.getStatePropertySafe(state, BlockStationNameBase.FACING);
		final SignLayout signLayout = getSignLayout(currentSignIds);
		final String[] signIds = signLayout.signIds;
		final int backgroundColor = signLayout.backgroundColor;
		final LongAVLTreeSet selectedIds = entity.getSelectedIds();

		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(0.5 + entity.getPos2().getX(), 0.53125 + entity.getPos2().getY(), 0.5 + entity.getPos2().getZ());
		storedMatrixTransformations.add(graphicsHolderNew -> {
			graphicsHolderNew.rotateYDegrees(-facing.asRotation());
			graphicsHolderNew.rotateZDegrees(180);
			graphicsHolderNew.translate(block.getXStart() / 16F - 0.5, 0, -0.0625 - SMALL_OFFSET * 2);
		});

		graphicsHolder.push();
		graphicsHolder.translate(0.5, 0.53125, 0.5);
		graphicsHolder.rotateYDegrees(-facing.asRotation());
		graphicsHolder.rotateZDegrees(180);
		graphicsHolder.translate(block.getXStart() / 16F - 0.5, 0, -0.0625 - SMALL_OFFSET * 2);

		if (signLayout.renderBackground) {
			final int newBackgroundColor = backgroundColor | ARGB_BLACK;
			MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.LIGHT, (graphicsHolderNew, offset) -> {
				storedMatrixTransformations.transform(graphicsHolderNew, offset);
				IDrawing.drawTexture(graphicsHolderNew, 0, 0, SMALL_OFFSET, 0.5F * (signIds.length), 0.5F, SMALL_OFFSET, facing, newBackgroundColor, GraphicsHolder.getDefaultLight());
				graphicsHolderNew.pop();
			});
		}
		for (int i = 0; i < signIds.length; i++) {
			if (signIds[i] != null && signLayout.signs[i] != null) {
				drawSign(
						graphicsHolder,
						storedMatrixTransformations,
						pos,
						signIds[i],
						signLayout.signs[i],
						0.5F * i,
						0,
						0.5F,
						signLayout.maxWidthsLeft[i],
						signLayout.maxWidthsRight[i],
						selectedIds,
						facing,
						backgroundColor | ARGB_BLACK,
						false,
						(textureId, x, y, size, flipTexture) -> MainRenderer.scheduleRender(textureId, true, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolderNew, offset) -> {
							storedMatrixTransformations.transform(graphicsHolderNew, offset);
							IDrawing.drawTexture(graphicsHolderNew, x, y, size, size, flipTexture ? 1 : 0, 0, flipTexture ? 0 : 1, 1, facing, -1, GraphicsHolder.getDefaultLight());
							graphicsHolderNew.pop();
						})
				);
			}
		}

		graphicsHolder.pop();
	}

	public static void drawSign(GraphicsHolder graphicsHolder, @Nullable StoredMatrixTransformations storedMatrixTransformations, BlockPos pos, String signId, float x, float y, float size, float maxWidthLeft, float maxWidthRight, LongAVLTreeSet selectedIds, Direction facing, int backgroundColor, boolean isGui, DrawTexture drawTexture) {
		final SignResource sign = getSign(signId);
		if (sign == null) {
			return;
		}
		drawSign(graphicsHolder, storedMatrixTransformations, pos, signId, sign, x, y, size, maxWidthLeft, maxWidthRight, selectedIds, facing, backgroundColor, isGui, drawTexture);
	}

	private static void drawSign(GraphicsHolder graphicsHolder, @Nullable StoredMatrixTransformations storedMatrixTransformations, BlockPos pos, String signId, SignResource sign, float x, float y, float size, float maxWidthLeft, float maxWidthRight, LongAVLTreeSet selectedIds, Direction facing, int backgroundColor, boolean isGui, DrawTexture drawTexture) {

		final float signSize = (sign.getSmall() ? BlockRailwaySign.SMALL_SIGN_PERCENTAGE : 1) * size;
		final float margin = (size - signSize) / 2;

		final boolean hasCustomText = sign.hasCustomText;
		final boolean flipCustomText = sign.getFlipCustomText();
		final boolean flipTexture = sign.getFlipTexture();
		final boolean fullSizeSign = sign.getFullSizeSign();
		final boolean isExit = signId.equals("exit_letter") || signId.equals("exit_letter_flipped");
		final boolean isLine = signId.equals("line") || signId.equals("line_flipped");
		final boolean isPlatform = signId.equals("platform") || signId.equals("platform_flipped");
		final boolean isStation = signId.equals("station") || signId.equals("station_flipped");

		if (storedMatrixTransformations != null && isExit) {
			final Station station = InitClient.findStation(pos);
			if (station == null) {
				return;
			}

			final ObjectArrayList<StationExit> selectedExitsSorted = new ObjectArrayList<>();
			final ObjectOpenHashSet<String> selectedExitNames = new ObjectOpenHashSet<>();
			selectedIds.longStream().forEach(selectedId -> selectedExitNames.add(EditStationScreen.deserializeExit(selectedId)));
			final ObjectArrayList<StationExit> exits = EditStationScreen.getStationExits(station, true);
			exits.forEach(exit -> {
				if (selectedExitNames.contains(exit.getName())) {
					selectedExitsSorted.add(exit);
				}
			});

			graphicsHolder.push();
			graphicsHolder.translate(x + margin + (flipCustomText ? signSize : 0), y + margin, 0);
			final float maxWidth = ((flipCustomText ? maxWidthLeft : maxWidthRight) + 1) * size - margin * 2;
			final float exitWidth = signSize * selectedExitsSorted.size();
			graphicsHolder.scale(Math.min(1, maxWidth / exitWidth), 1, 1);

			for (int i = 0; i < selectedExitsSorted.size(); i++) {
				final StationExit stationExit = selectedExitsSorted.get(flipCustomText ? selectedExitsSorted.size() - i - 1 : i);
				final float signOffset = (flipCustomText ? -1 : 1) * signSize * i - (flipCustomText ? signSize : 0);

				MainRenderer.scheduleRender(DynamicTextureCache.instance.getExitSignLetter(stationExit.getName().substring(0, 1), stationExit.getName().substring(1), backgroundColor).identifier, true, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolderNew, offset) -> {
					storedMatrixTransformations.transform(graphicsHolderNew, offset);
					graphicsHolderNew.translate(x + margin + (flipCustomText ? signSize : 0), y + margin, 0);
					graphicsHolderNew.scale(Math.min(1, maxWidth / exitWidth), 1, 1);
					IDrawing.drawTexture(graphicsHolderNew, signOffset, 0, signSize, signSize, facing, GraphicsHolder.getDefaultLight());
					graphicsHolderNew.pop();
				});

				if (maxWidth > exitWidth && selectedExitsSorted.size() == 1 && !stationExit.getDestinations().isEmpty()) {
					renderCustomText(stationExit.getDestinations().get(0), storedMatrixTransformations, facing, size, flipCustomText ? x : x + size, flipCustomText, maxWidth - exitWidth - margin * 2, backgroundColor);
				}
			}

			graphicsHolder.pop();
		} else if (storedMatrixTransformations != null && isLine) {
			final Station station = InitClient.findStation(pos);
			if (station == null) {
				return;
			}

			final LongOpenHashSet platformIds = new LongOpenHashSet();
			station.savedRails.forEach(platform -> platformIds.add(platform.getId()));
			station.connectedStations.forEach(connectingStation -> connectingStation.savedRails.forEach(platform -> platformIds.add(platform.getId())));

			final ObjectArrayList<IntObjectImmutablePair<String>> selectedRoutesSorted = new ObjectArrayList<>();
			final IntOpenHashSet addedColors = new IntOpenHashSet();
			MinecraftClientData.getInstance().simplifiedRoutes.forEach(simplifiedRoute -> {
				if (!simplifiedRoute.getName().isEmpty()) {
					final int color = simplifiedRoute.getColor();
					if (!addedColors.contains(color) && selectedIds.contains(color) && simplifiedRoute.getPlatforms().stream().anyMatch(simplifiedRoutePlatform -> platformIds.contains(simplifiedRoutePlatform.getPlatformId()))) {
						selectedRoutesSorted.add(new IntObjectImmutablePair<>(color, simplifiedRoute.getName().split("\\|\\|")[0]));
						addedColors.add(color);
					}
				}
			});

			selectedRoutesSorted.sort(Comparator.comparingInt(IntObjectImmutablePair::leftInt));
			final float maxWidth = Math.max(0, ((flipCustomText ? maxWidthLeft : maxWidthRight) + 1) * size - margin * 2);
			final float height = size - margin * 2;
			final List<DynamicTextureCache.DynamicResource> resourceLocationDataList = new ArrayList<>();
			float totalTextWidth = 0;
			for (final IntObjectImmutablePair<String> route : selectedRoutesSorted) {
				final DynamicTextureCache.DynamicResource resourceLocationData = DynamicTextureCache.instance.getRouteSquare(route.leftInt(), route.right(), flipCustomText ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT);
				resourceLocationDataList.add(resourceLocationData);
				totalTextWidth += height * resourceLocationData.width / resourceLocationData.height + margin / 2F;
			}

			final StoredMatrixTransformations storedMatrixTransformations2 = storedMatrixTransformations.copy();
			storedMatrixTransformations2.add(graphicsHolderNew -> graphicsHolderNew.translate(flipCustomText ? x + size - margin : x + margin, 0, 0));

			if (totalTextWidth > margin / 2F) {
				totalTextWidth -= margin / 2F;
			}
			if (totalTextWidth > maxWidth) {
				final float finalTotalTextWidth = totalTextWidth;
				storedMatrixTransformations2.add(graphicsHolderNew -> graphicsHolderNew.scale(maxWidth / finalTotalTextWidth, 1, 1));
			}

			float xOffset = 0;
			for (final DynamicTextureCache.DynamicResource resourceLocationData : resourceLocationDataList) {
				final float width = height * resourceLocationData.width / resourceLocationData.height;
				final float finalXOffset = xOffset;
				MainRenderer.scheduleRender(resourceLocationData.identifier, true, QueuedRenderLayer.LIGHT, (graphicsHolderNew, offset) -> {
					storedMatrixTransformations2.transform(graphicsHolderNew, offset);
					IDrawing.drawTexture(graphicsHolderNew, flipCustomText ? -finalXOffset - width : finalXOffset, margin, width, height, Direction.UP, GraphicsHolder.getDefaultLight());
					graphicsHolderNew.pop();
				});
				xOffset += width + margin / 2F;
			}
		} else if (storedMatrixTransformations != null && isPlatform) {
			final Station station = InitClient.findStation(pos);
			if (station == null) {
				return;
			}

			final LongArrayList selectedIdsSorted = station.savedRails.stream().sorted().mapToLong(NameColorDataBase::getId).filter(selectedIds::contains).boxed().collect(Collectors.toCollection(LongArrayList::new));
			final int selectedCount = selectedIdsSorted.size();

			final float extraMargin = margin - margin / selectedCount;
			final float height = (size - extraMargin * 2) / selectedCount;
			for (int i = 0; i < selectedIdsSorted.size(); i++) {
				final float topOffset = i * height + extraMargin;
				final float bottomOffset = (i + 1) * height + extraMargin;
				final float left = flipCustomText ? x - maxWidthLeft * size : x + margin;
				final float right = flipCustomText ? x + size - margin : x + (maxWidthRight + 1) * size;
				MainRenderer.scheduleRender(DynamicTextureCache.instance.getDirectionArrow(selectedIdsSorted.getLong(i), false, false, flipCustomText ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT, false, margin / size, (right - left) / (bottomOffset - topOffset), backgroundColor, ARGB_WHITE, backgroundColor).identifier, true, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolderNew, offset) -> {
					storedMatrixTransformations.transform(graphicsHolderNew, offset);
					IDrawing.drawTexture(graphicsHolderNew, left, topOffset, 0, right, bottomOffset, 0, 0, 0, 1, 1, facing, -1, GraphicsHolder.getDefaultLight());
					graphicsHolderNew.pop();
				});
			}
		} else {
			if (fullSizeSign && !isGui) {
				final float fixedMargin = size * (1 - BlockRailwaySign.SMALL_SIGN_PERCENTAGE) / 8;
				final float maxWidth = Math.max(0, maxWidthRight * size - fixedMargin * 2);
				final float start = x + size + fixedMargin;

				MainRenderer.scheduleRender(sign.getTexture(), true, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolderNew, offset) -> {
					storedMatrixTransformations.transform(graphicsHolderNew, offset);
					IDrawing.drawTexture(graphicsHolderNew, x + margin, y + margin, 0, start + maxWidth, size, 0, 0, 0, 1, 1, facing, -1, GraphicsHolder.getDefaultLight());
					graphicsHolderNew.pop();
				});
			} else {
				drawTexture.drawTexture(sign.getTexture(), x + margin, y + margin, signSize, flipTexture);
			}

			if (hasCustomText) {
				final float fixedMargin = size * (1 - BlockRailwaySign.SMALL_SIGN_PERCENTAGE) / 2;
				final boolean isSmall = sign.getSmall();
				final float maxWidth = Math.max(0, (flipCustomText ? maxWidthLeft : maxWidthRight) * size - fixedMargin * (isSmall ? 1 : 2));
				final float start = flipCustomText ? x - (isSmall ? 0 : fixedMargin) : x + size + (isSmall ? 0 : fixedMargin);
				if (storedMatrixTransformations == null) {
					IDrawing.drawStringWithFont(graphicsHolder, isExit || isLine ? "..." : sign.getCustomText().getString(), flipCustomText ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT, VerticalAlignment.TOP, start, y + fixedMargin, maxWidth, size - fixedMargin * 2, 0.01F, ARGB_WHITE, false, GraphicsHolder.getDefaultLight(), null);
				} else {
					final String signText;
					if (isStation) {
						signText = IGui.mergeStations(selectedIds.longStream()
								.filter(MinecraftClientData.getInstance().stationIdMap::containsKey)
								.sorted()
								.mapToObj(stationId -> IGui.insertTranslation(TranslationProvider.GUI_MTR_STATION_CJK, TranslationProvider.GUI_MTR_STATION, 1, MinecraftClientData.getInstance().stationIdMap.get(stationId).getName()))
								.collect(Collectors.toList())
						);
					} else {
						signText = sign.getCustomText().getString();
					}
					renderCustomText(signText, storedMatrixTransformations, facing, size, start, flipCustomText, maxWidth, backgroundColor);
				}
			}
		}
	}

	private static void renderCustomText(String signText, StoredMatrixTransformations storedMatrixTransformations, Direction facing, float size, float start, boolean flipCustomText, float maxWidth, int backgroundColor) {
		final DynamicTextureCache.DynamicResource dynamicResource = DynamicTextureCache.instance.getSignText(signText, flipCustomText ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT, (1 - BlockRailwaySign.SMALL_SIGN_PERCENTAGE) / 2, backgroundColor, ARGB_WHITE);
		final float width = Math.min(size * dynamicResource.width / dynamicResource.height, maxWidth);
		MainRenderer.scheduleRender(dynamicResource.identifier, true, QueuedRenderLayer.LIGHT_TRANSLUCENT, (graphicsHolderNew, offset) -> {
			storedMatrixTransformations.transform(graphicsHolderNew, offset);
			IDrawing.drawTexture(graphicsHolderNew, start - (flipCustomText ? width : 0), 0, 0, start + (flipCustomText ? 0 : width), size, 0, 0, 0, 1, 1, facing, -1, GraphicsHolder.getDefaultLight());
			graphicsHolderNew.pop();
		});
	}

	public static SignResource getSign(@Nullable String signId) {
		// TODO load existing signs from BlockRailwaySign.SignType using the resource pack format
		if (signId == null) {
			return null;
		} else {
			return CustomResourceLoader.getSignById(signId);
		}
	}

	public static float getMaxWidth(String[] signIds, int index, boolean right) {
		if (index < 0 || index >= signIds.length || signIds[index] == null) {
			return getMaxWidthUncached(signIds, index, right);
		}
		final SignLayout signLayout = getSignLayout(signIds);
		return (right ? signLayout.maxWidthsRight : signLayout.maxWidthsLeft)[index];
	}

	private static float getMaxWidthUncached(String[] signIds, int index, boolean right) {
		float maxWidth = 0;
		for (int i = index + (right ? 1 : -1); right ? i < signIds.length : i >= 0; i += right ? 1 : -1) {
			if (signIds[i] != null) {
				final SignResource sign = getSign(signIds[i]);
				if (sign != null && sign.hasCustomText && right == sign.getFlipCustomText()) {
					maxWidth /= 2;
				}
				return maxWidth;
			}
			maxWidth++;
		}
		return maxWidth;
	}

	private static SignLayout getSignLayout(String[] signIds) {
		synchronized (SIGN_LAYOUT_CACHE) {
			final int resourceGeneration = CustomResourceLoader.getResourceReloadGeneration();
			if (resourceGeneration != cachedResourceGeneration) {
				SIGN_LAYOUT_CACHE.clear();
				cachedResourceGeneration = resourceGeneration;
			}
			SignLayout signLayout = SIGN_LAYOUT_CACHE.get(signIds);
			if (signLayout == null || !Arrays.equals(signLayout.signIds, signIds)) {
				signLayout = new SignLayout(signIds);
				SIGN_LAYOUT_CACHE.put(signIds, signLayout);
			}
			return signLayout;
		}
	}

	public static void clearLayoutCache() {
		synchronized (SIGN_LAYOUT_CACHE) {
			SIGN_LAYOUT_CACHE.clear();
			cachedResourceGeneration = CustomResourceLoader.getResourceReloadGeneration();
		}
	}

	private static float[] getMaxWidths(String[] signIds, SignResource[] signs, boolean right) {
		final float[] maxWidths = new float[signIds.length];
		float emptySlots = 0;
		SignResource neighboringSign = null;
		for (int i = right ? signIds.length - 1 : 0; right ? i >= 0 : i < signIds.length; i += right ? -1 : 1) {
			if (signIds[i] == null) {
				emptySlots++;
			} else {
				maxWidths[i] = neighboringSign != null && neighboringSign.hasCustomText && right == neighboringSign.getFlipCustomText() ? emptySlots / 2 : emptySlots;
				emptySlots = 0;
				neighboringSign = signs[i];
			}
		}
		return maxWidths;
	}

	private static final class SignLayout {

		private final String[] signIds;
		private final SignResource[] signs;
		private final float[] maxWidthsLeft;
		private final float[] maxWidthsRight;
		private final boolean renderBackground;
		private final int backgroundColor;

		private SignLayout(String[] signIds) {
			this.signIds = signIds.clone();
			signs = new SignResource[signIds.length];
			boolean hasBackground = false;
			int foundBackgroundColor = 0;
			for (int i = 0; i < signIds.length; i++) {
				final String signId = signIds[i];
				if (signId != null) {
					final SignResource sign = getSign(signId);
					signs[i] = sign;
					if (sign != null) {
						hasBackground = true;
						if (sign.getBackgroundColor() != 0) {
							foundBackgroundColor = sign.getBackgroundColor();
							break;
						}
					}
				}
			}
			// The old background scan stopped at the first colored sign. Resolve the
			// remaining entries as well because this layout also caches sign lookup.
			for (int i = 0; i < signIds.length; i++) {
				if (signIds[i] != null && signs[i] == null) {
					signs[i] = getSign(signIds[i]);
				}
			}
			renderBackground = hasBackground;
			backgroundColor = foundBackgroundColor;
			maxWidthsLeft = getMaxWidths(this.signIds, signs, false);
			maxWidthsRight = getMaxWidths(this.signIds, signs, true);
		}
	}

	@FunctionalInterface
	public interface DrawTexture {
		void drawTexture(Identifier textureId, float x, float y, float size, boolean flipTexture);
	}
}
