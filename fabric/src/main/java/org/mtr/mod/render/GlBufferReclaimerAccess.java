package org.mtr.mod.render;

import javax.annotation.Nullable;

public interface GlBufferReclaimerAccess {

	@Nullable
	GlBufferReclaimer.Handle mtr$getReclaimerHandle();
}
