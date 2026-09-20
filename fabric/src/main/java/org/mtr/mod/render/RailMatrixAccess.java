package org.mtr.mod.render;

import org.joml.Matrix4f;

/** Client-only bridge to the matrix consumed by Minecraft-Mappings' optimized renderer. */
public interface RailMatrixAccess {
	Matrix4f mtr$getRailPositionMatrix();
}
