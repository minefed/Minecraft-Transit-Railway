package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class VehiclePathCacheTest {

	@Test
	public void keptPathDoesNotNeedRepeatedInitialization() {
		for (final TransportMode mode : TransportMode.values()) {
			for (final long speed : new long[]{0, 40, 160, 300}) {
				final Rail rail = Rail.newRail(new Position(0, 64, 0), Angle.E, new Position(100, 64, 0), Angle.W, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), speed, speed, false, false, true, false, false, mode);
				final PathData source = new PathData(rail, 0, 0, 0, new Position(0, 64, 0), new Position(100, 64, 0));
				final PathData received = new PathData(new JsonReader(Utilities.getJsonObjectFromData(source)));
				final ObjectArrayList<PathData> path = ObjectArrayList.of(received);
				PathData.writePathCache(path, new ClientData(), mode);
				final String firstPath = Utilities.getJsonObjectFromData(received).toString();
				final String firstRail = Utilities.getJsonObjectFromData(received.getRail()).toString();
				for (int i = 0; i < 3; i++) {
					PathData.writePathCache(path, new ClientData(), mode);
					assertEquals(firstPath, Utilities.getJsonObjectFromData(received).toString());
					assertEquals(firstRail, Utilities.getJsonObjectFromData(received.getRail()).toString());
				}
			}
		}
	}
}
