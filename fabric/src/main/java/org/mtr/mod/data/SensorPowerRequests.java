package org.mtr.mod.data;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;

/**
 * Suppresses repeated client requests to power the same redstone sensor while an earlier request is in flight.
 * <p>
 * The server's {@code power()} only raises the sensor to its maximum level, so a repeated request is a no-op
 * until that level has decayed. A request is sent again as soon as the client observes a different power level
 * for the position, or after {@link #RESEND_MILLIS} without a change.
 */
public final class SensorPowerRequests {

	public static final long RESEND_MILLIS = 250;
	private static final int PRUNE_SIZE = 256;

	/** Packed {@code (millis << 2) | powered} of the last request per position. */
	private final Long2LongOpenHashMap requests = new Long2LongOpenHashMap();
	private WeakReference<Object> world = new WeakReference<>(null);

	/**
	 * @param currentMillis a monotonic clock in milliseconds
	 * @return whether the request should be sent now; if so, it is recorded as in flight
	 */
	public boolean shouldSend(@Nullable Object world, long position, int powered, long currentMillis) {
		if (this.world.get() != world) {
			requests.clear();
			this.world = new WeakReference<>(world);
		}
		if (requests.containsKey(position)) {
			final long request = requests.get(position);
			final long elapsed = currentMillis - (request >> 2);
			if ((request & 3) == (powered & 3) && elapsed >= 0 && elapsed < RESEND_MILLIS) {
				return false;
			}
		}
		if (requests.size() >= PRUNE_SIZE) {
			requests.long2LongEntrySet().removeIf(entry -> currentMillis - (entry.getLongValue() >> 2) >= RESEND_MILLIS);
		}
		requests.put(position, (currentMillis << 2) | (powered & 3));
		return true;
	}
}
