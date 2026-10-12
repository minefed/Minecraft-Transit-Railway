/* Run with node --test tools/bluemap/mtr-live.test.cjs. No third-party dependencies. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { SnapshotBuffer, normalizeSnapshot, catalogEntry, railChunks, routeChunks, safeUrl, Overlay, STALE_MS, BUFFER_MS } = require('../../fabric/src/main/resources/assets/mtr/bluemap/mtr-live.js');

const vehicle = (x = 0, yaw = 0, id = 'custom:unlisted', extra = {}) => ({ id: 'fleet-1', routeName: '<img src=x onerror=alert(1)>', color: '#3F81DA', cars: [{ id, position: [x, 64, -20], yaw, pitch: 0, length: 24, width: 3.2 }], ...extra });
const snapshot = (timestamp, vehicles = [vehicle()]) => ({ schema: 1, timestamp, vehicles });

test('all valid models survive normalization and unknown models get route colors and real dimensions', () => {
    const data = normalizeSnapshot(snapshot(1, [vehicle(0, 0, 'known'), { ...vehicle(20, 0, 'custom:not-in-catalog'), id: 'fleet-2' }]));
    assert.equal(data.cars.size, 2);
    const custom = data.cars.get('fleet-2:0');
    assert.deepEqual(catalogEntry({ vehicles: {} }, custom), { name: 'custom:not-in-catalog', length: 24, width: 3.2, color: '#3F81DA' });
    assert.equal(custom.routeName, '<img src=x onerror=alert(1)>'); // Retained as plain text, never evaluated as markup.
    assert.equal(catalogEntry({ vehicles: { known: { name: 'Display name', colors: ['#ff0000'] } } }, data.cars.get('fleet-1:0')).color, '#3F81DA');
});

test('malformed positions are rejected, empty fleets are valid, and bad colors fall back safely', () => {
    assert.throws(() => normalizeSnapshot({ schema: 2, vehicles: [] }));
    const bad = vehicle(); bad.cars.push({ ...bad.cars[0], position: [NaN, 0, 0] }); bad.color = 'url(javascript:alert(1))';
    const parsed = normalizeSnapshot(snapshot(1, [bad]));
    assert.equal(parsed.cars.size, 1);
    assert.equal(parsed.cars.get('fleet-1:0').color, '#8b949e');
    assert.equal(normalizeSnapshot(snapshot(2, [])).cars.size, 0);
});

test('buffer interpolates the short yaw arc, supports clock offset, and never extrapolates', () => {
    const buffer = new SnapshotBuffer();
    buffer.push(snapshot(1_700_000_000_000, [vehicle(0, 179 * Math.PI / 180)]), 0);
    buffer.push(snapshot(1_700_000_000_500, [vehicle(10, -179 * Math.PI / 180)]), 500);
    const middle = buffer.sample(BUFFER_MS + 250).get('fleet-1:0');
    assert.equal(middle.position[0], 5);
    assert.ok(Math.abs(Math.abs(middle.yaw) - Math.PI) < 1e-8);
    assert.equal(buffer.sample(3000).get('fleet-1:0').position[0], 10);
});

test('HTTP-success cached snapshots cannot keep stale trains alive', () => {
    const buffer = new SnapshotBuffer();
    buffer.push(snapshot(1000), 100);
    assert.equal(buffer.push(snapshot(1000), STALE_MS), false);
    assert.equal(buffer.push(snapshot(999), STALE_MS), false);
    assert.equal(buffer.sample(STALE_MS + 101).size, 0);
    assert.equal(buffer.push(snapshot(2000, [vehicle(30)]), STALE_MS + 200), true);
    assert.equal(buffer.sample(STALE_MS + 200).get('fleet-1:0').position[0], 30);
});

test('despawn, appearance, model replacement and teleport do not produce phantom interpolated cars', () => {
    const buffer = new SnapshotBuffer();
    buffer.push(snapshot(1000), 0);
    buffer.push(snapshot(1500, []), 500);
    assert.equal(buffer.sample(BUFFER_MS + 500).size, 0);
    buffer.push(snapshot(2000, [vehicle(1000)]), 1000);
    assert.equal(buffer.sample(BUFFER_MS + 750).get('fleet-1:0').position[0], 1000);
    buffer.push(snapshot(2500, [vehicle(2000)]), 1500);
    assert.equal(buffer.sample(BUFFER_MS + 1250).get('fleet-1:0').position[0], 2000);
    buffer.clear();
    assert.equal(buffer.sample(1600).size, 0);
});

test('rail chunking retains sloped and negative-coordinate rails and segments long sparse input', () => {
    const chunks = railChunks({ schema: 1, rails: [{ id: 'r', points: [[-300, 64, 0], [300, 94, 0]] }] });
    assert.ok(chunks.has('-3:0'));
    assert.ok(chunks.has('2:0'));
    const segments = [...chunks.values()].flatMap(chunk => chunk.rails);
    assert.ok(segments.every(segment => segment.length <= 8.001));
    assert.ok(segments.every(segment => segment.pitch < 0));
    assert.ok(Math.abs(segments[0].yaw - Math.PI / 2) < 1e-9);
    assert.ok([...chunks.values()].flatMap(chunk => chunk.sleepers).length >= 400);
    assert.equal(railChunks({ schema: 1, rails: [{ points: [[1, 1, 1], [1, 1, 1]] }] }).size, 0);
});

test('route geometry follows provided paths and never joins separate or missing legs', () => {
    const chunks = routeChunks({ routes: [{ id: 'red', color: '#ff0044', missingLegs: 1, paths: [[[0, 1, 0], [0, 1, 10]], [[0, 1, 500], [0, 1, 510]]] }, { id: 'empty', paths: [] }] });
    const segments = [...chunks.values()].flatMap(chunk => chunk.segments);
    assert.equal(segments.length, 4);
    assert.ok(segments.every(segment => segment.position[2] < 10 || segment.position[2] > 500));
    assert.ok(segments.every(segment => segment.color === '#ff0044'));
});

test('data requests stay under the BlueMap webroot, rejecting traversal and external endpoints', () => {
    const base = 'https://map.example.test/sub/map/index.html';
    assert.equal(safeUrl('mtr-live/world-a-vehicles.json', base), 'https://map.example.test/sub/map/mtr-live/world-a-vehicles.json');
    for (const path of ['https://evil.test/mtr-live/a.json', '../secret.json', 'mtr-live/../secret.json', 'mtr-live/%2e%2e%2fsecret.json', 'mtr-live/a.json?redirect=1', 'mtr-live/a.json#fragment', '//evil.test/mtr-live/a.json']) {
        assert.throws(() => safeUrl(path, base));
    }
});

test('a late response for the old map is discarded on map change', async () => {
    let resolveIndex;
    const state = {
        generation: 1, mapId: 'old', disposed: false, index: null, now: () => 0,
        json: () => new Promise(resolve => { resolveIndex = resolve; })
    };
    const pending = Overlay.prototype.pollOnce.call(state);
    state.generation++;
    state.mapId = 'new';
    resolveIndex({ schema: 1, maps: { old: { vehicles: 'mtr-live/old-vehicles.json' } } });
    await pending;
    assert.equal(state.index, null);
    assert.equal(state.entry, undefined);
});

test('catalog failures do not prevent unknown vehicles from loading', async () => {
    const state = {
        generation: 1, mapId: 'map', disposed: false, index: null, now: () => 1000, lastRailsAt: 1000,
        buffer: new SnapshotBuffer(),
        refreshCatalog: Overlay.prototype.refreshCatalog,
        json: async path => {
            if (path === 'mtr-live/index.json') return { schema: 1, maps: { map: { vehicles: 'mtr-live/world-vehicles.json' } }, catalog: 'mtr-live/catalog.json' };
            if (path === 'mtr-live/catalog.json') throw new Error('Missing catalog');
            return snapshot(3000, [vehicle(42)]);
        }
    };
    await Overlay.prototype.pollOnce.call(state);
    assert.equal(state.buffer.sample(1000).get('fleet-1:0').position[0], 42);
});

test('slow static data never blocks subsequent live vehicle requests', async () => {
    let requests = 0, finishRails;
    const state = {
        generation: 1, mapId: 'map', disposed: false, index: {}, entry: { vehicles: 'mtr-live/a-vehicles.json', rails: 'mtr-live/a-rails.json' },
        now: () => 1000, lastIndexAt: 1000, lastRailsAt: -Infinity, buffer: new SnapshotBuffer(),
        refreshRails(generation) {
            this.railRequestGeneration = generation;
            return new Promise(resolve => { finishRails = resolve; });
        },
        json: async () => snapshot(++requests * 500)
    };
    await Overlay.prototype.pollOnce.call(state);
    await Overlay.prototype.pollOnce.call(state);
    assert.equal(requests, 2);
    assert.equal(state.buffer.snapshots.length, 2);
    finishRails();
});

test('shared route paths have separated ribbons even when traversed in opposite directions', () => {
    const chunks = routeChunks({ routes: [
        { id: 'a', color: '#ff0000', paths: [[[0, 64, 0], [16, 64, 0]]] },
        { id: 'b', color: '#0000ff', paths: [[[16, 64, 0], [0, 64, 0]]] }
    ] });
    const segments = [...chunks.values()].flatMap(chunk => chunk.segments).filter(segment => segment.position[0] === 4);
    assert.equal(segments.length, 2);
    const actualZ = segments.map(segment => -Math.sin(segment.yaw) * segment.offset);
    assert.ok(Math.abs(actualZ[0] - actualZ[1]) > 0.8);
});
