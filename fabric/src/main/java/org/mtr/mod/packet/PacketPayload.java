package org.mtr.mod.packet;

import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

/** Retains the legacy string framing unless this particular recipient negotiated version 1. */
final class PacketPayload {

	private static final String BINARY_MARKER = "\u0000MTR1";
	private String text;
	private JsonObject json;
	private byte[] binary;
	private boolean prepared;

	PacketPayload(String text) {
		this.text = text;
	}

	PacketPayload(JsonObject json) {
		this.json = json;
	}

	static PacketPayload read(PacketBufferReceiver receiver) {
		final String text = receiver.readString();
		if (!BINARY_MARKER.equals(text)) {
			return new PacketPayload(text);
		}
		final int length = receiver.readInt();
		if (length < 0 || length > BinaryPacketCodec.MAX_BYTES) {
			throw new IllegalArgumentException("Invalid binary packet length");
		}
		final byte[] bytes = new byte[length];
		int index = 0;
		while (index + 8 <= length) {
			final long value = receiver.readLong();
			for (int shift = 56; shift >= 0; shift -= 8) {
				bytes[index++] = (byte) (value >>> shift);
			}
		}
		while (index < length) {
			final char value = receiver.readChar();
			bytes[index++] = (byte) (value >>> 8);
			if (index < length) {
				bytes[index++] = (byte) value;
			}
		}
		return new PacketPayload(BinaryPacketCodec.decode(bytes).getAsJsonObject());
	}

	JsonObject json() {
		if (json == null) {
			json = Utilities.parseJson(text);
		}
		return json;
	}

	String text() {
		if (text == null) {
			text = json.toString();
		}
		return text;
	}

	void write(PacketBufferSender sender, boolean allowBinary) {
		if (allowBinary && !prepared) {
			prepared = true;
			try {
				final byte[] encoded = BinaryPacketCodec.encode(json());
				// Include the marker, length and possible final padding byte in the comparison.
				// PacketBufferSender strings use an int length and UTF-16 chars.
				if (encoded.length + (encoded.length & 1) + 14L < text().length() * 2L) {
					binary = encoded;
				}
			} catch (IllegalArgumentException ignored) {
				// Unusually deep/large trees continue to use the existing JSON protocol.
			}
		}
		if (!allowBinary || binary == null) {
			sender.writeString(text());
			return;
		}
		sender.writeString(BINARY_MARKER);
		sender.writeInt(binary.length);
		int index = 0;
		while (index + 8 <= binary.length) {
			long value = 0;
			for (int i = 0; i < 8; i++) {
				value = (value << 8) | (binary[index++] & 0xFFL);
			}
			sender.writeLong(value);
		}
		while (index < binary.length) {
			int value = (binary[index++] & 0xFF) << 8;
			if (index < binary.length) {
				value |= binary[index++] & 0xFF;
			}
			sender.writeChar((char) value);
		}
	}
}
