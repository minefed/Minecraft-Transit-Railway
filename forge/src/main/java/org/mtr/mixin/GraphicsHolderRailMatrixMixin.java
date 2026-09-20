package org.mtr.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.render.RailMatrixAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GraphicsHolder.class, remap = false)
public abstract class GraphicsHolderRailMatrixMixin implements RailMatrixAccess {

	@Shadow @Final private PoseStack matrixStack;

	@Override
	public Matrix4f mtr$getRailPositionMatrix() {
		return matrixStack.last().pose();
	}
}
