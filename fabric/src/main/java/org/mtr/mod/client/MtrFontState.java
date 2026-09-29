package org.mtr.mod.client;

/**
 * Whether the TrueType files of the {@code mtr:mtr} font were loaded by the last font reload.
 * The font is only drawn when "Use MTR Font" is enabled, so the files are skipped while the option is off.
 */
public final class MtrFontState {

	public static volatile boolean loaded;

	private MtrFontState() {
	}
}
