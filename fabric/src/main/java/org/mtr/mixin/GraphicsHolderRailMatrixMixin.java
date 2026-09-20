package org.mtr.mixin;

import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.render.RailMatrixAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GraphicsHolder.class, remap = false)
public abstract class GraphicsHolderRailMatrixMixin implements RailMatrixAccess {

	@Shadow @Final private MatrixStack matrixStack;

	@Override
	public Matrix4f mtr$getRailPositionMatrix() {
		return matrixStack.peek().getPositionMatrix();
	}
}
