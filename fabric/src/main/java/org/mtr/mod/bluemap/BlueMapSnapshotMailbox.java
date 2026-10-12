package org.mtr.mod.bluemap;

/** Latest-only handoff with a revision fence: revoked snapshots can never be republished. */
final class BlueMapSnapshotMailbox<T> {

	private long revision;
	private T latest;

	synchronized void reset(long revision) {
		this.revision = revision;
		latest = null;
	}

	synchronized boolean publish(long revision, T value) {
		if (revision != this.revision) {
			return false;
		}
		latest = value;
		return true;
	}

	synchronized T take() {
		final T result = latest;
		latest = null;
		return result;
	}
}
