package org.mtr.mod.render;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class PidsTextCacheTest {

	@Test
	public void preservesDelimiterAndLanguageCycleSemantics() {
		final PidsTextCache cache = new PidsTextCache();
		Assertions.assertArrayEquals(new String[]{"", "\uC11C\uC6B8", "Seoul"}, cache.split("|\uC11C\uC6B8|Seoul||"));
		Assertions.assertArrayEquals(new String[]{""}, cache.split(""));
		Assertions.assertArrayEquals(new String[0], cache.split("||"));
		Assertions.assertArrayEquals(new String[]{"1 \uC11C\uC6B8", "2 Seoul", "1 \u9996\u5C14", "2 \uC11C\uC6B8", "1 Seoul", "2 \u9996\u5C14"}, cache.getDestinations("1|2", "\uC11C\uC6B8|Seoul|\u9996\u5C14"));
		// Existing cycling stops at the first repeated combined text, even when
		// more combinations would follow if the whole cycle were traversed.
		Assertions.assertArrayEquals(new String[]{"1 A"}, cache.getDestinations("1|1|2", "A|A|B"));
		Assertions.assertArrayEquals(new String[]{"\uC11C\uC6B8", "Seoul"}, cache.getDestinations("", "\uC11C\uC6B8|Seoul"));
	}

	@Test
	public void keysIncludeBothUnmodifiedStringsAndCacheCanBeCleared() {
		final PidsTextCache cache = new PidsTextCache();
		final String[] original = cache.getDestinations("1", "A|B");
		Assertions.assertSame(original, cache.getDestinations(new String("1"), new String("A|B")));
		Assertions.assertArrayEquals(new String[]{"1 A", "2 B"}, cache.getDestinations("1|2", "A|B"));
		Assertions.assertArrayEquals(new String[]{"1 C"}, cache.getDestinations("1", "C"));
		cache.clear();
		Assertions.assertNotSame(original, cache.getDestinations("1", "A|B"));
		Assertions.assertArrayEquals(original, cache.getDestinations("1", "A|B"));
	}

	@Test
	public void evictsOldEntriesAndDoesNotRetainOversizedText() {
		final PidsTextCache cache = new PidsTextCache();
		final String[] split = cache.split("first");
		final String[] destination = cache.getDestinations("1", "first");
		for (int i = 0; i < 4097; i++) {
			cache.split("split" + i);
			cache.getDestinations("1", "destination" + i);
		}
		Assertions.assertNotSame(split, cache.split("first"));
		Assertions.assertNotSame(destination, cache.getDestinations("1", "first"));
		final String longText = new String(new char[4097]).replace('\0', 'A');
		Assertions.assertNotSame(cache.split(longText), cache.split(longText));
		Assertions.assertNotSame(cache.getDestinations("1", longText), cache.getDestinations("1", longText));
		Assertions.assertArrayEquals(new String[]{"1 " + longText}, cache.getDestinations("1", longText));
	}
}
