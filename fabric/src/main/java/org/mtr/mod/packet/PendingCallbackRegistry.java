package org.mtr.mod.packet;

import org.mtr.core.Main;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores callbacks for packet round trips with collision-safe identifiers.
 * Callers that supply a timeout action are expired automatically; other callers
 * retain late-response behavior and clear the registry at session shutdown.
 * A response and its timeout race through the same atomic removal, so at most
 * one of them can complete a callback.
 */
final class PendingCallbackRegistry<T> {

	static final long DEFAULT_TIMEOUT_MILLIS = 15000;

	private final Map<Long, Entry<T>> callbacks = new ConcurrentHashMap<>();

	long register(T callback) {
		return register(callback, null, false);
	}

	long register(T callback, @Nullable Runnable timeoutAction) {
		return register(callback, timeoutAction, true);
	}

	private long register(T callback, @Nullable Runnable timeoutAction, boolean scheduleTimeout) {
		final Entry<T> entry = new Entry<>(Objects.requireNonNull(callback), timeoutAction);
		long callbackId;
		do {
			callbackId = ThreadLocalRandom.current().nextLong();
		} while (callbacks.putIfAbsent(callbackId, entry) != null);

		if (scheduleTimeout) {
			final long finalCallbackId = callbackId;
			entry.setTimeoutFuture(TimeoutExecutor.INSTANCE.schedule(() -> expire(finalCallbackId, entry), DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
		}
		return callbackId;
	}

	@Nullable
	T remove(long callbackId) {
		final Entry<T> entry = callbacks.remove(callbackId);
		if (entry == null || !entry.complete()) {
			return null;
		}
		entry.cancelTimeout();
		return entry.callback;
	}

	void clear() {
		clear(false);
	}

	void clearAndExpire() {
		clear(true);
	}

	private void clear(boolean runTimeoutAction) {
		for (final Map.Entry<Long, Entry<T>> callbackEntry : callbacks.entrySet()) {
			final Entry<T> entry = callbackEntry.getValue();
			if (callbacks.remove(callbackEntry.getKey(), entry) && entry.complete()) {
				entry.cancelTimeout();
				if (runTimeoutAction) {
					entry.runTimeoutAction();
				}
			}
		}
	}

	private void expire(long callbackId, Entry<T> entry) {
		if (callbacks.remove(callbackId, entry) && entry.complete()) {
			entry.runTimeoutAction();
		}
	}

	private static final class Entry<T> {

		private final T callback;
		@Nullable
		private final Runnable timeoutAction;
		private final AtomicBoolean completed = new AtomicBoolean();
		@Nullable
		private volatile ScheduledFuture<?> timeoutFuture;

		private Entry(T callback, @Nullable Runnable timeoutAction) {
			this.callback = callback;
			this.timeoutAction = timeoutAction;
		}

		private boolean complete() {
			return completed.compareAndSet(false, true);
		}

		private void setTimeoutFuture(ScheduledFuture<?> timeoutFuture) {
			this.timeoutFuture = timeoutFuture;
			if (completed.get()) {
				timeoutFuture.cancel(false);
			}
		}

		private void cancelTimeout() {
			final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
			if (timeoutFuture != null) {
				timeoutFuture.cancel(false);
			}
		}

		private void runTimeoutAction() {
			if (timeoutAction != null) {
				try {
					timeoutAction.run();
				} catch (Throwable throwable) {
					Main.LOGGER.error("Failed to expire a pending packet callback", throwable);
				}
			}
		}
	}

	private static final class TimeoutExecutor {

		private static final ScheduledThreadPoolExecutor INSTANCE = create();

		private static ScheduledThreadPoolExecutor create() {
			final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
				final Thread thread = new Thread(runnable, "mtr-pending-callback-timeouts");
				thread.setDaemon(true);
				return thread;
			});
			executor.setRemoveOnCancelPolicy(true);
			executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
			return executor;
		}
	}
}
