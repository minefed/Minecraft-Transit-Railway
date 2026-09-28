package org.mtr.mod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public final class SensorPowerRequestsTest {

	@Test
	public void repeatsAreSuppressedOnlyWhileTheSameStateIsInFlight() {
		final SensorPowerRequests requests = new SensorPowerRequests();
		final Object world = new Object();
		assertTrue(requests.shouldSend(world, 1, 0, 1000));
		// Frames before the server's powered state arrives
		assertFalse(requests.shouldSend(world, 1, 0, 1016));
		assertFalse(requests.shouldSend(world, 1, 0, 1000 + SensorPowerRequests.RESEND_MILLIS - 1));
		// Another sensor is independent
		assertTrue(requests.shouldSend(world, 2, 0, 1016));
		// No response: retry after the timeout
		assertTrue(requests.shouldSend(world, 1, 0, 1000 + SensorPowerRequests.RESEND_MILLIS));
		assertFalse(requests.shouldSend(world, 1, 0, 1000 + SensorPowerRequests.RESEND_MILLIS + 1));
		// The observed state decayed from 2 to 1: send immediately
		assertTrue(requests.shouldSend(world, 1, 1, 1300));
		assertFalse(requests.shouldSend(world, 1, 1, 1310));
		assertTrue(requests.shouldSend(world, 1, 0, 1320));
		// A clock step backwards never suppresses indefinitely
		assertTrue(requests.shouldSend(world, 1, 0, 100));
		// A different world starts empty
		assertTrue(requests.shouldSend(new Object(), 1, 0, 101));
	}

	@Test
	public void staleRequestsArePrunedWithoutChangingDecisions() {
		final SensorPowerRequests requests = new SensorPowerRequests();
		final Object world = new Object();
		for (int i = 0; i < 1000; i++) {
			assertTrue(requests.shouldSend(world, i, 0, i * 10L));
		}
		for (int i = 0; i < 1000; i++) {
			final long now = 10_000 + i;
			assertTrue(requests.shouldSend(world, 5000 + i, 1, now));
			assertFalse(requests.shouldSend(world, 5000 + i, 1, now));
		}
	}
}
