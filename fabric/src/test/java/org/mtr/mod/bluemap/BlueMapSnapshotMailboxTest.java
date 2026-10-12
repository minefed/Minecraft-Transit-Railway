package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public final class BlueMapSnapshotMailboxTest {

	@Test
	public void aLateRevokedCaptureCannotOverwriteTheNewPublication() throws Exception {
		final BlueMapSnapshotMailbox<String> mailbox = new BlueMapSnapshotMailbox<>();
		mailbox.reset(1);
		assertTrue(mailbox.publish(1, "already queued old snapshot"));
		final CountDownLatch captured = new CountDownLatch(1);
		final CountDownLatch allowCompletion = new CountDownLatch(1);
		final ExecutorService simulator = Executors.newSingleThreadExecutor();
		try {
			final Future<Boolean> late = simulator.submit(() -> {
				captured.countDown();
				if (!allowCompletion.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Test did not release the old simulator capture");
				}
				return mailbox.publish(1, "revoked private content");
			});
			assertTrue(captured.await(10, TimeUnit.SECONDS));
			mailbox.reset(2);
			assertNull(mailbox.take()); // Previously queued content was discarded immediately.
			assertTrue(mailbox.publish(2, "current public content"));
			allowCompletion.countDown();
			assertFalse(late.get(10, TimeUnit.SECONDS));
			assertEquals("current public content", mailbox.take());
			assertNull(mailbox.take());
		} finally {
			allowCompletion.countDown();
			simulator.shutdownNow();
			assertTrue(simulator.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	public void resetAndRepeatedUpdatesNeverResurrectOldValues() {
		final BlueMapSnapshotMailbox<String> mailbox = new BlueMapSnapshotMailbox<>();
		mailbox.reset(4);
		assertTrue(mailbox.publish(4, "first"));
		assertTrue(mailbox.publish(4, "newest"));
		assertEquals("newest", mailbox.take());
		assertNull(mailbox.take());
		mailbox.reset(5);
		assertFalse(mailbox.publish(4, "old"));
		assertFalse(mailbox.publish(6, "unaccepted future"));
		assertNull(mailbox.take());
		assertTrue(mailbox.publish(5, "pending"));
		mailbox.reset(6);
		assertNull(mailbox.take());
		assertFalse(mailbox.publish(5, "late pending"));
	}
}
