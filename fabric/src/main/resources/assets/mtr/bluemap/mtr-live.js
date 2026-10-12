/* Minefed MTR / BlueMap live overlay. Distributed under the repository MIT license. */
(function (scope) {
    "use strict";

    const POLL_MS = 500;
    const BUFFER_MS = 650;
    const STALE_MS = 6000;
    const CHUNK_SIZE = 128;
    const NEUTRAL_COLOR = "#8b949e";
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    const finite = value => typeof value === "number" && Number.isFinite(value);
    const vector = value => Array.isArray(value) && value.length === 3 && value.every(finite);
    const dimension = (value, fallback) => finite(value) && value > 0 && value < 256 ? value : fallback;
    const color = value => typeof value === "string" && /^#[0-9a-f]{6}$/i.test(value) ? value : NEUTRAL_COLOR;

    function angleBetween(a, b, amount) {
        return a + Math.atan2(Math.sin(b - a), Math.cos(b - a)) * amount;
    }

    function normalizeSnapshot(data) {
        if (!data || data.schema !== 1 || !finite(data.timestamp) || !Array.isArray(data.vehicles)) {
            throw new Error("Unsupported MTR vehicle data");
        }
        const cars = new Map();
        for (const vehicle of data.vehicles) {
            if (!vehicle || typeof vehicle.id !== "string" || !Array.isArray(vehicle.cars)) continue;
            vehicle.cars.forEach((car, index) => {
                if (!car || !vector(car.position) || !finite(car.yaw) || !finite(car.pitch)) return;
                cars.set(`${vehicle.id}:${index}`, {
                    key: `${vehicle.id}:${index}`, vehicleId: vehicle.id, index,
                    routeId: typeof vehicle.routeId === "string" ? vehicle.routeId : "",
                    routeName: typeof vehicle.routeName === "string" ? vehicle.routeName : "",
                    color: color(vehicle.color),
                    id: typeof car.id === "string" ? car.id : "unknown",
                    position: car.position.slice(), yaw: car.yaw, pitch: car.pitch,
                    length: dimension(car.length, 20), width: dimension(car.width, 3)
                });
            });
        }
        return { timestamp: data.timestamp, cars };
    }

    class SnapshotBuffer {
        constructor() { this.clear(); }
        clear() {
            this.snapshots = [];
            this.offset = null;
            this.lastFreshAt = -Infinity;
        }
        push(data, receivedAt) {
            const snapshot = normalizeSnapshot(data);
            const previous = this.snapshots[this.snapshots.length - 1];
            // A stale static webroot may keep returning HTTP 200. Only new server time proves freshness.
            if (previous && snapshot.timestamp <= previous.timestamp) return false;
            if (this.offset === null || receivedAt - this.lastFreshAt > STALE_MS) {
                this.snapshots = [];
                this.offset = receivedAt - snapshot.timestamp;
            }
            this.lastFreshAt = receivedAt;
            this.snapshots.push(snapshot);
            if (this.snapshots.length > 20) this.snapshots.shift();
            return true;
        }
        isStale(now) { return now - this.lastFreshAt > STALE_MS; }
        sample(now) {
            if (this.isStale(now) || !this.snapshots.length) return new Map();
            const time = now - this.offset - BUFFER_MS;
            let before = this.snapshots[0], after = before;
            for (const snapshot of this.snapshots) {
                if (snapshot.timestamp <= time) before = snapshot;
                after = snapshot;
                if (snapshot.timestamp >= time) break;
            }
            const amount = before === after ? 0 : clamp((time - before.timestamp) / (after.timestamp - before.timestamp), 0, 1);
            const result = new Map();
            for (const [key, car] of after.cars) {
                const old = before.cars.get(key);
                if (!old || old.id !== car.id || Math.hypot(...car.position.map((n, i) => n - old.position[i])) > 128) {
                    result.set(key, car);
                    continue;
                }
                result.set(key, {
                    ...car,
                    position: car.position.map((n, i) => old.position[i] + (n - old.position[i]) * amount),
                    yaw: angleBetween(old.yaw, car.yaw, amount),
                    pitch: angleBetween(old.pitch, car.pitch, amount)
                });
            }
            return result; // The last sample is held, never extrapolated past the newest server pose.
        }
    }

    function catalogEntry(catalog, car) {
        const entry = catalog && catalog.vehicles && Object.prototype.hasOwnProperty.call(catalog.vehicles, car.id) ? catalog.vehicles[car.id] : null;
        return {
            name: entry && typeof entry.name === "string" ? entry.name : car.id,
            length: dimension(car.length, dimension(entry && entry.length, 20)),
            width: dimension(car.width, dimension(entry && entry.width, 3)),
            color: color(car.color)
        };
    }

    function railChunks(data) {
        if (!data || data.schema !== 1 || !Array.isArray(data.rails)) throw new Error("Unsupported MTR rail data");
        const chunks = new Map();
        function add(kind, item) {
            const cx = Math.floor(item.position[0] / CHUNK_SIZE), cz = Math.floor(item.position[2] / CHUNK_SIZE);
            const key = `${cx}:${cz}`;
            if (!chunks.has(key)) chunks.set(key, { key, x: (cx + 0.5) * CHUNK_SIZE, z: (cz + 0.5) * CHUNK_SIZE, rails: [], sleepers: [] });
            chunks.get(key)[kind].push(item);
        }
        for (const rail of data.rails) {
            if (!rail || !Array.isArray(rail.points)) continue;
            let nextSleeper = 0;
            for (let i = 1; i < rail.points.length; i++) {
                const a = rail.points[i - 1], b = rail.points[i];
                if (!vector(a) || !vector(b)) continue;
                const delta = b.map((n, j) => n - a[j]);
                const length = Math.hypot(...delta), horizontal = Math.hypot(delta[0], delta[2]);
                if (length < 0.001 || horizontal < 0.001) continue;
                const yaw = Math.atan2(delta[0], delta[2]), pitch = -Math.atan2(delta[1], horizontal);
                // Split even sparse input into small chunks; a long line crossing the view cannot be culled by its midpoint.
                const count = Math.ceil(length / 8);
                for (let part = 0; part < count; part++) {
                    const t = (part + 0.5) / count;
                    add("rails", { position: a.map((n, j) => n + delta[j] * t), yaw, pitch, length: length / count });
                }
                for (; nextSleeper <= length; nextSleeper += 1.5) {
                    add("sleepers", { position: a.map((n, j) => n + delta[j] * nextSleeper / length), yaw, pitch });
                }
                nextSleeper -= length;
            }
        }
        return chunks;
    }

    function safeUrl(path, base) {
        if (typeof path !== "string" || !/^mtr-live\/[a-zA-Z0-9_-]+\.json$/.test(path)) throw new Error("Invalid MTR data URL");
        const url = new URL(path, base), root = new URL("mtr-live/", base);
        if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname) || url.username || url.password) {
            throw new Error("MTR data URL must stay inside this map's mtr-live directory");
        }
        return url.href;
    }

    function routeChunks(data) {
        const chunks = new Map(), shared = new Map();
        for (const route of Array.isArray(data.routes) ? data.routes : []) {
            if (!route || !Array.isArray(route.paths)) continue;
            for (const points of route.paths) {
                if (!Array.isArray(points)) continue;
                for (let i = 1; i < points.length; i++) {
                    const a = points[i - 1], b = points[i];
                    if (!vector(a) || !vector(b)) continue;
                    const delta = b.map((n, j) => n - a[j]), length = Math.hypot(...delta);
                    if (length < 0.001) continue;
                    const count = Math.ceil(length / 8), yaw = Math.atan2(delta[0], delta[2]);
                    const pitch = -Math.atan2(delta[1], Math.hypot(delta[0], delta[2]));
                    for (let part = 0; part < count; part++) {
                        const position = a.map((n, j) => n + delta[j] * (part + 0.5) / count);
                        const cx = Math.floor(position[0] / CHUNK_SIZE), cz = Math.floor(position[2] / CHUNK_SIZE), key = `${cx}:${cz}`;
                        if (!chunks.has(key)) chunks.set(key, { key, x: (cx + 0.5) * CHUNK_SIZE, z: (cz + 0.5) * CHUNK_SIZE, segments: [] });
                        const segment = { position, yaw, pitch, length: length / count, color: color(route.color), routeId: route.id, offset: 0, width: 1 };
                        chunks.get(key).segments.push(segment);
                        const geometryKey = position.map(n => Math.round(n * 20)).join(":") + ":" + Math.round(segment.length * 20)
                            + ":" + Math.round(Math.cos(2 * yaw) * 100) + ":" + Math.round(Math.sin(2 * yaw) * 100);
                        if (!shared.has(geometryKey)) shared.set(geometryKey, []);
                        shared.get(geometryKey).push(segment);
                    }
                }
            }
        }
        // Shared tracks show parallel route ribbons instead of coincident surfaces hiding each other.
        for (const segments of shared.values()) {
            segments.sort((a, b) => String(a.routeId).localeCompare(String(b.routeId)));
            segments.forEach((segment, index) => {
                const axis = Math.abs(Math.cos(segment.yaw)) > 0.01 ? Math.cos(segment.yaw) : Math.sin(segment.yaw);
                const canonical = axis >= 0 ? 1 : -1;
                segment.offset = (index - (segments.length - 1) / 2) * 0.85 * canonical;
                segment.width = segments.length > 1 ? 0.72 : 1;
            });
        }
        return chunks;
    }

    class Overlay {
        constructor(app, THREE, options = {}) {
            this.app = app;
            this.viewer = app.mapViewer;
            this.THREE = THREE;
            this.base = options.base || document.baseURI;
            this.fetch = options.fetch || scope.fetch.bind(scope);
            this.now = options.now || (() => performance.now());
            this.buffer = new SnapshotBuffer();
            this.catalog = { schema: 1, vehicles: {} };
            this.chunks = new Map();
            this.loadedChunks = new Map();
            this.routeChunks = new Map();
            this.loadedRouteChunks = new Map();
            this.stationData = [];
            this.stations = new Map();
            this.cars = new Map();
            this.trainDots = new Map();
            this.materials = new Map();
            this.box = new THREE.BoxGeometry(1, 1, 1);
            this.stationGeometry = new THREE.SphereGeometry(0.8, 12, 8);
            this.group = new THREE.Group();
            this.railsGroup = new THREE.Group();
            this.trainGroup = new THREE.Group();
            this.routesGroup = new THREE.Group();
            this.stationsGroup = new THREE.Group();
            this.group.add(this.railsGroup, this.routesGroup, this.trainGroup, this.stationsGroup);
            this.viewer.markers.add(this.group);
            this.showRails = true;
            this.showTrains = true;
            this.showRoutes = true;
            this.showStations = true;
            this.generation = 0;
            this.disposed = false;
            this.controllers = new Set();
            this.lastCullAt = -Infinity;
            this.lastIndexAt = -Infinity;
            this.lastRailsAt = -Infinity;
            this.index = null;
            this.entry = null;
            this.mapId = null;
            this.errors = 0;
            this.raycaster = new THREE.Raycaster();
            this.pointer = new THREE.Vector2();
            this.frame = () => this.render();
            this.mapChanged = () => this.switchMap();
            this.hover = event => this.showHover(event);
            this.leave = () => { if (this.tooltip) this.tooltip.hidden = true; };
            this.app.events.addEventListener("bluemapRenderFrame", this.frame);
            this.app.events.addEventListener("bluemapMapChanged", this.mapChanged);
            if (options.ui !== false) this.createUi();
            this.switchMap();
            if (options.poll !== false) this.poll();
        }

        material(hex, schematic = false) {
            const key = `${hex}:${schematic}`;
            if (!this.materials.has(key)) this.materials.set(key, new this.THREE.MeshBasicMaterial({ color: hex, depthTest: !schematic, depthWrite: !schematic }));
            return this.materials.get(key);
        }

        createCar(car) {
            const entry = catalogEntry(this.catalog, car), group = new this.THREE.Group();
            const height = 2.8, width = entry.width, length = entry.length;
            const box = (x, y, z, sx, sy, sz, hex) => {
                const mesh = new this.THREE.Mesh(this.box, this.material(hex));
                mesh.position.set(x, y, z);
                mesh.scale.set(sx, sy, sz);
                group.add(mesh);
            };
            // Procedural silhouette intentionally covers every model ID, including custom/resource-pack vehicles.
            box(0, height / 2 + 0.25, 0, width, height, length * 0.96, entry.color);
            box(0, height * 0.70 + 0.25, 0, width * 1.005, height * 0.26, length * 0.84, "#283845");
            box(0, height + 0.30, 0, width * 0.93, 0.14, length * 0.95, "#d2d6db");
            box(0, 0.24, 0, width * 0.78, 0.32, length * 0.82, "#30343a");
            group.rotation.order = "YXZ";
            group.userData.mtrLabel = `${car.routeName ? `${car.routeName} · ` : ""}${entry.name} · ${car.vehicleId} · ${car.index + 1}`;
            group.userData.signature = `${car.id}:${entry.length}:${entry.width}:${entry.color}:${car.routeName}`;
            this.trainGroup.add(group);
            return group;
        }

        createRailChunk(chunk) {
            const THREE = this.THREE, group = new THREE.Group(), transform = new THREE.Object3D();
            group.position.set(chunk.x, 0, chunk.z);
            const rails = new THREE.InstancedMesh(this.box, this.material("#bac3ca"), chunk.rails.length * 2);
            const sleepers = new THREE.InstancedMesh(this.box, this.material("#574b3b"), chunk.sleepers.length);
            transform.rotation.order = "YXZ";
            let index = 0;
            for (const segment of chunk.rails) {
                for (const side of [-1, 1]) {
                    const offset = side * 0.7175;
                    transform.position.set(segment.position[0] - chunk.x + Math.cos(segment.yaw) * offset,
                        segment.position[1] + 0.09, segment.position[2] - chunk.z - Math.sin(segment.yaw) * offset);
                    transform.rotation.set(segment.pitch, segment.yaw, 0, "YXZ");
                    transform.scale.set(0.085, 0.12, segment.length + 0.04);
                    transform.updateMatrix();
                    rails.setMatrixAt(index++, transform.matrix);
                }
            }
            chunk.sleepers.forEach((sleeper, i) => {
                transform.position.set(sleeper.position[0] - chunk.x, sleeper.position[1] + 0.025, sleeper.position[2] - chunk.z);
                transform.rotation.set(sleeper.pitch, sleeper.yaw, 0, "YXZ");
                transform.scale.set(2.4, 0.10, 0.22);
                transform.updateMatrix();
                sleepers.setMatrixAt(i, transform.matrix);
            });
            // Three r147's instanced bounds are based on the unit box, not the remote instances.
            rails.frustumCulled = false;
            sleepers.frustumCulled = false;
            rails.instanceMatrix.needsUpdate = true;
            sleepers.instanceMatrix.needsUpdate = true;
            group.add(rails, sleepers);
            this.railsGroup.add(group);
            return group;
        }

        removeRailChunk(group) {
            group.removeFromParent();
            group.children.forEach(mesh => { if (mesh.dispose) mesh.dispose(); });
        }

        createRouteChunk(chunk, width = 0.55) {
            const THREE = this.THREE, group = new THREE.Group(), transform = new THREE.Object3D();
            group.position.set(chunk.x, 0, chunk.z);
            const mesh = new THREE.InstancedMesh(this.box, this.material("#ffffff", true), chunk.segments.length);
            mesh.renderOrder = 10;
            chunk.segments.forEach((segment, i) => {
                transform.position.set(segment.position[0] - chunk.x + Math.cos(segment.yaw) * segment.offset * width,
                    segment.position[1] + 0.24, segment.position[2] - chunk.z - Math.sin(segment.yaw) * segment.offset * width);
                transform.rotation.set(segment.pitch, segment.yaw, 0, "YXZ");
                transform.scale.set(width * segment.width, 0.07, segment.length + 0.05);
                transform.updateMatrix();
                mesh.setMatrixAt(i, transform.matrix);
                mesh.setColorAt(i, new THREE.Color(segment.color));
            });
            mesh.instanceMatrix.needsUpdate = true;
            if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
            mesh.frustumCulled = false;
            group.add(mesh);
            group.userData.width = width;
            this.routesGroup.add(group);
            return group;
        }

        createStation(station) {
            const group = new this.THREE.Group();
            group.position.fromArray(station.position);
            const marker = new this.THREE.Mesh(this.stationGeometry, this.material("#f4f7fa", true));
            marker.renderOrder = 11;
            marker.position.y = 1.2;
            group.add(marker);
            const names = (Array.isArray(station.routes) ? station.routes : []).map(id => this.routeNames.get(id) || id);
            group.userData.mtrLabel = `${station.name || station.id}${names.length ? ` · ${names.join(" / ")}` : ""}`;
            const HtmlMarker = scope.BlueMap && scope.BlueMap.HtmlMarker;
            if (HtmlMarker && typeof document !== "undefined") {
                const label = document.createElement("button");
                label.type = "button";
                label.textContent = station.name || station.id;
                label.title = group.userData.mtrLabel;
                label.setAttribute("aria-label", group.userData.mtrLabel);
                Object.assign(label.style, { padding: "3px 7px", color: "#192e43", background: "#f4f7faed", border: "2px solid #506b83", borderRadius: "10px", font: "bold 12px sans-serif", cursor: "pointer", pointerEvents: "auto", whiteSpace: "nowrap" });
                label.addEventListener("click", event => { event.stopPropagation(); this.showDetail(group.userData.mtrLabel); });
                const object = new HtmlMarker(`mtr-station-${this.stations.size}`);
                object.element.replaceChildren(label);
                object.position.y = 3.5;
                group.add(object);
                group.userData.labelElement = label;
                group.userData.marker = object;
            }
            this.stationsGroup.add(group);
            return group;
        }

        clearStations() {
            this.stations.forEach(group => {
                if (group.userData.marker) this.disposeHtmlMarker(group.userData.marker);
                group.removeFromParent();
            });
            this.stations.clear();
        }

        createTrainDot(car) {
            const HtmlMarker = scope.BlueMap && scope.BlueMap.HtmlMarker;
            if (!HtmlMarker || typeof document === "undefined") return null;
            const marker = new HtmlMarker(`mtr-train-${this.trainDots.size}`), button = document.createElement("button");
            button.type = "button";
            button.textContent = "▰";
            Object.assign(button.style, { width: "22px", height: "22px", padding: "0", border: "2px solid white", borderRadius: "50%", background: car.color, color: "white", font: "bold 14px/16px sans-serif", boxShadow: "0 1px 4px #0007", pointerEvents: "auto", cursor: "pointer" });
            marker.element.replaceChildren(button);
            marker.userData.button = button;
            button.addEventListener("click", event => { event.stopPropagation(); this.showDetail(marker.userData.mtrLabel); });
            this.trainGroup.add(marker);
            return marker;
        }

        clearTrainDots() {
            this.trainDots.forEach(marker => this.disposeHtmlMarker(marker));
            this.trainDots.clear();
        }

        disposeHtmlMarker(marker) {
            marker.dispose();
            marker.removeFromParent();
            // BlueMap nests the label in a CSS2D wrapper that is attached outside the WebGL scene.
            if (marker.elementObject && marker.elementObject.element) marker.elementObject.element.remove();
        }

        switchMap() {
            this.generation++;
            this.controllers.forEach(controller => controller.abort());
            this.controllers.clear();
            this.mapId = this.viewer.map && this.viewer.map.data.id;
            this.entry = null;
            this.lastIndexAt = -Infinity;
            this.lastRailsAt = -Infinity;
            this.railRequestGeneration = null;
            this.catalogRequestGeneration = null;
            this.buffer.clear();
            this.cars.clear();
            this.clearTrainDots();
            this.trainGroup.clear();
            this.showDetail("");
            this.updateVehicleList(new Map());
            this.missingLegs = 0;
            this.loadedChunks.forEach(group => this.removeRailChunk(group));
            this.loadedChunks.clear();
            this.chunks.clear();
            this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
            this.loadedRouteChunks.clear();
            this.routeChunks.clear();
            this.clearStations();
            this.stationData = [];
            this.routeNames = new Map();
            this.updateRouteLegend([]);
            this.group.visible = false;
            this.leave();
            this.setStatus("연결 중…");
        }

        async json(path) {
            const controller = new AbortController();
            this.controllers.add(controller);
            const timeout = setTimeout(() => controller.abort(), 4500);
            try {
                const response = await this.fetch(safeUrl(path, this.base), { cache: "no-store", credentials: "same-origin", signal: controller.signal });
                if (!response.ok) throw new Error(`MTR HTTP ${response.status}`);
                const data = await response.json();
                if (!data || data.schema !== 1) throw new Error("Unsupported MTR data schema");
                return data;
            } finally {
                clearTimeout(timeout);
                this.controllers.delete(controller);
            }
        }

        async pollOnce() {
            const generation = this.generation, mapId = this.mapId;
            if (!mapId) return;
            const current = () => !this.disposed && generation === this.generation;
            if (!this.index || this.now() - this.lastIndexAt > 30000) {
                const index = await this.json("mtr-live/index.json");
                if (!current()) return;
                this.index = index;
                this.lastIndexAt = this.now();
                this.entry = index.maps && Object.prototype.hasOwnProperty.call(index.maps, mapId) ? index.maps[mapId] : null;
                if (index.catalog && this.catalogRequestGeneration == null) this.refreshCatalog(generation, index.catalog);
            }
            if (!current()) return;
            if (!this.entry) {
                this.setStatus("이 지도에 MTR 데이터가 없습니다");
                return;
            }
            const data = await this.json(this.entry.vehicles);
            if (!current()) return;
            this.buffer.push(data, this.now());
            this.errors = 0;
            if (this.now() - this.lastRailsAt > 30000 && this.railRequestGeneration == null) this.refreshRails(generation, this.entry.rails);
        }

        async refreshCatalog(generation, path) {
            this.catalogRequestGeneration = generation;
            try {
                const catalog = await this.json(path);
                if (!this.disposed && generation === this.generation) this.catalog = catalog;
            } catch (error) {
                // Names are optional: catalog failures never stop neutral/route-colored vehicles.
            } finally {
                if (generation === this.generation) this.catalogRequestGeneration = null;
            }
        }

        async refreshRails(generation, path) {
            this.railRequestGeneration = generation;
            const current = () => !this.disposed && generation === this.generation;
            try {
                const rails = await this.json(path);
                if (!current()) return;
                const chunks = railChunks(rails);
                this.loadedChunks.forEach(group => this.removeRailChunk(group));
                this.loadedChunks.clear();
                this.chunks = chunks;
                this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
                this.loadedRouteChunks.clear();
                this.routeChunks = routeChunks(rails);
                const routes = Array.isArray(rails.routes) ? rails.routes.filter(route => route && typeof route.id === "string") : [];
                this.routeNames = new Map(routes.map(route => [route.id, route.name || route.id]));
                this.updateRouteLegend(routes);
                this.clearStations();
                this.stationData = (Array.isArray(rails.stations) ? rails.stations : []).filter(station => station && typeof station.id === "string" && vector(station.position));
                this.missingLegs = routes.reduce((sum, route) => sum + (finite(route.missingLegs) ? route.missingLegs : 0), 0);
                this.lastRailsAt = this.now();
                this.lastCullAt = -Infinity;
            } catch (error) {
                if (current()) this.lastRailsAt = this.now() - 25000;
            } finally {
                if (current()) this.railRequestGeneration = null;
            }
        }

        async poll() {
            if (this.disposed) return;
            try { await this.pollOnce(); }
            catch (error) { if (error.name !== "AbortError") this.errors++; }
            if (!this.disposed) this.timer = setTimeout(() => this.poll(), POLL_MS);
        }

        render() {
            if (this.disposed) return;
            // Hide immediately during BlueMap's asynchronous map transition, before its changed event.
            if (!this.viewer.map || this.viewer.map.data.id !== this.mapId || this.viewer.data.mapState === "loading") {
                this.group.visible = false;
                return;
            }
            const now = this.now(), stale = this.buffer.isStale(now);
            this.group.visible = Boolean(this.entry);
            this.trainGroup.visible = this.showTrains && !stale;
            this.railsGroup.visible = this.showRails;
            this.routesGroup.visible = this.showRoutes;
            this.stationsGroup.visible = this.showStations;
            const center = this.viewer.controlsManager.position;
            const range = clamp((this.viewer.data.loadedHiresViewDistance || 200) * 1.5, 256, 768);
            const cityRange = clamp(this.viewer.controlsManager.distance * 2, 768, 20000);
            const routeWidth = Math.max(0.55, Math.round(clamp(this.viewer.controlsManager.distance / 220, 0.55, 24) * 2) / 2);
            const distant = this.viewer.controlsManager.distance > 900;
            const poses = this.buffer.sample(now);
            for (const [key, group] of this.cars) {
                if (!poses.has(key)) { group.removeFromParent(); this.cars.delete(key); }
            }
            for (const [key, car] of poses) {
                const entry = catalogEntry(this.catalog, car);
                const signature = `${car.id}:${entry.length}:${entry.width}:${entry.color}:${car.routeName}`;
                let group = this.cars.get(key);
                if (group && group.userData.signature !== signature) { group.removeFromParent(); this.cars.delete(key); group = null; }
                const nearby = !distant && Math.hypot(car.position[0] - center.x, car.position[2] - center.z) < range + car.length;
                if (!group && nearby) { group = this.createCar(car); this.cars.set(key, group); }
                if (group) {
                    group.visible = nearby;
                    group.position.fromArray(car.position);
                    group.rotation.set(car.pitch, car.yaw, 0, "YXZ");
                    if (!nearby) { group.removeFromParent(); this.cars.delete(key); }
                }
            }
            const dotIds = new Set();
            if (distant && !stale && this.showTrains) for (const car of poses.values()) {
                if (dotIds.has(car.vehicleId) || Math.hypot(car.position[0] - center.x, car.position[2] - center.z) > cityRange) continue;
                dotIds.add(car.vehicleId);
                let dot = this.trainDots.get(car.vehicleId);
                if (!dot) { dot = this.createTrainDot(car); if (dot) this.trainDots.set(car.vehicleId, dot); }
                if (dot) {
                    dot.position.fromArray(car.position);
                    dot.position.y += 3.5;
                    dot.userData.mtrLabel = `${car.routeName ? `${car.routeName} · ` : ""}${catalogEntry(this.catalog, car).name} · ${car.vehicleId}`;
                    dot.userData.button.title = dot.userData.mtrLabel;
                    dot.userData.button.setAttribute("aria-label", dot.userData.mtrLabel);
                    dot.userData.button.style.background = car.color;
                }
            }
            for (const [id, dot] of this.trainDots) if (!dotIds.has(id)) {
                this.disposeHtmlMarker(dot); this.trainDots.delete(id);
            }
            if (now - this.lastCullAt > 500) {
                this.lastCullAt = now;
                const detailed = this.showRails && this.viewer.controlsManager.distance < 1600;
                for (const [key, chunk] of this.chunks) {
                    const nearby = detailed && Math.hypot(chunk.x - center.x, chunk.z - center.z) < range + CHUNK_SIZE;
                    if (nearby && !this.loadedChunks.has(key)) this.loadedChunks.set(key, this.createRailChunk(chunk));
                    else if (!nearby && this.loadedChunks.has(key)) {
                        this.removeRailChunk(this.loadedChunks.get(key));
                        this.loadedChunks.delete(key);
                    }
                }
                for (const [key, chunk] of this.routeChunks) {
                    const nearby = this.showRoutes && Math.hypot(chunk.x - center.x, chunk.z - center.z) < cityRange + CHUNK_SIZE;
                    if (nearby && this.loadedRouteChunks.has(key) && this.loadedRouteChunks.get(key).userData.width !== routeWidth) {
                        this.removeRailChunk(this.loadedRouteChunks.get(key));
                        this.loadedRouteChunks.delete(key);
                    }
                    if (nearby && !this.loadedRouteChunks.has(key)) this.loadedRouteChunks.set(key, this.createRouteChunk(chunk, routeWidth));
                    else if (!nearby && this.loadedRouteChunks.has(key)) {
                        this.removeRailChunk(this.loadedRouteChunks.get(key));
                        this.loadedRouteChunks.delete(key);
                    }
                }
                const labelCells = new Set(), projected = new this.THREE.Vector3();
                const viewport = this.viewer.renderer.domElement.getBoundingClientRect();
                this.viewer.camera.updateMatrixWorld();
                for (const station of this.stationData) {
                    const nearby = this.showStations && Math.hypot(station.position[0] - center.x, station.position[2] - center.z) < cityRange;
                    if (nearby && !this.stations.has(station.id)) this.stations.set(station.id, this.createStation(station));
                    const group = this.stations.get(station.id);
                    if (group) {
                        group.visible = nearby;
                        if (!nearby) {
                            if (group.userData.marker) this.disposeHtmlMarker(group.userData.marker);
                            group.removeFromParent();
                            this.stations.delete(station.id);
                            continue;
                        }
                        if (nearby && group.userData.labelElement) {
                            projected.copy(group.position).project(this.viewer.camera);
                            const cell = `${Math.round(projected.x * viewport.width / 180)}:${Math.round(projected.y * viewport.height / 60)}`;
                            const named = !labelCells.has(cell);
                            labelCells.add(cell);
                            group.userData.labelElement.textContent = named ? station.name || station.id : "●";
                            group.userData.labelElement.style.padding = named ? "3px 7px" : "0 3px";
                        }
                    }
                }
                if (this.entry) this.setStatus(stale ? "실시간 차량 데이터 연결 끊김" : `${poses.size}량 · 노선 색상${this.missingLegs ? ` · 경로 미생성 ${this.missingLegs}구간` : ""}`);
                this.updateVehicleList(poses);
            }
            if (!stale && poses.size && this.showTrains) this.viewer.redraw();
            if (stale) this.leave();
        }

        createUi() {
            const panel = document.createElement("details");
            panel.className = "mtr-live-panel";
            panel.open = true;
            Object.assign(panel.style, { position: "absolute", top: "82px", right: "12px", zIndex: "20", padding: "10px 12px", maxWidth: "250px", color: "#edf3f8", background: "rgba(24,31,39,.94)", border: "1px solid #566675", borderRadius: "8px", font: "13px/1.5 sans-serif", boxShadow: "0 2px 10px #0004" });
            const title = document.createElement("summary");
            title.textContent = "MTR · 철도 지도";
            title.style.cursor = "pointer";
            panel.appendChild(title);
            for (const [property, text] of [["showTrains", "실시간 열차 (간략 모델)"], ["showRails", "3D 선로"], ["showRoutes", "노선 색상"], ["showStations", "역 이름"]]) {
                const label = document.createElement("label"), input = document.createElement("input");
                label.style.display = "block";
                input.type = "checkbox";
                input.checked = this[property];
                input.addEventListener("change", () => { this[property] = input.checked; this.lastCullAt = -Infinity; this.viewer.redraw(); });
                label.append(input, document.createTextNode(` ${text}`));
                panel.appendChild(label);
            }
            this.status = document.createElement("div");
            this.status.setAttribute("role", "status");
            this.status.setAttribute("aria-live", "polite");
            this.status.style.color = "#c3ced8";
            panel.appendChild(this.status);
            this.routeLegend = document.createElement("div");
            this.routeLegend.setAttribute("aria-label", "노선 색상 범례");
            Object.assign(this.routeLegend.style, { maxHeight: "130px", overflowY: "auto", marginTop: "4px" });
            panel.appendChild(this.routeLegend);
            this.vehicleList = document.createElement("select");
            this.vehicleList.setAttribute("aria-label", "주변 열차 정보 선택");
            Object.assign(this.vehicleList.style, { maxWidth: "230px", marginTop: "6px", color: "inherit", background: "#283542", border: "1px solid #647484", borderRadius: "4px" });
            this.vehicleList.addEventListener("change", () => {
                const group = this.cars.get(this.vehicleList.value);
                this.showDetail(group ? group.userData.mtrLabel : "주변 열차를 선택하세요");
            });
            panel.appendChild(this.vehicleList);
            this.detail = document.createElement("div");
            this.detail.setAttribute("aria-live", "polite");
            this.detail.style.marginTop = "5px";
            panel.appendChild(this.detail);
            this.tooltip = document.createElement("div");
            this.tooltip.hidden = true;
            this.tooltip.setAttribute("role", "tooltip");
            Object.assign(this.tooltip.style, { position: "fixed", zIndex: "25", pointerEvents: "none", maxWidth: "320px", padding: "6px 9px", borderRadius: "5px", color: "white", background: "#17212bf2", font: "13px sans-serif" });
            this.viewer.rootElement.append(panel, this.tooltip);
            this.panel = panel;
            this.viewer.renderer.domElement.addEventListener("pointermove", this.hover);
            this.viewer.renderer.domElement.addEventListener("pointerleave", this.leave);
        }

        setStatus(text) { if (this.status && this.status.textContent !== text) this.status.textContent = text; }
        showDetail(text) { if (this.detail) this.detail.textContent = text; }

        updateRouteLegend(routes) {
            if (!this.routeLegend) return;
            this.routeLegend.replaceChildren();
            for (const route of routes) {
                const row = document.createElement("div"), swatch = document.createElement("span");
                Object.assign(swatch.style, { display: "inline-block", width: "15px", height: "5px", borderRadius: "3px", background: color(route.color), marginRight: "6px", verticalAlign: "middle" });
                swatch.setAttribute("aria-hidden", "true");
                row.append(swatch, document.createTextNode(String(route.name || route.id)));
                this.routeLegend.appendChild(row);
            }
        }

        updateVehicleList(poses) {
            if (!this.vehicleList) return;
            const visible = [...this.cars.entries()].filter(([, group]) => group.visible && this.trainGroup.visible);
            const signature = visible.map(([key, group]) => `${key}:${group.userData.mtrLabel}`).join("|");
            if (signature === this.listSignature) return;
            this.listSignature = signature;
            const selected = this.vehicleList.value;
            this.vehicleList.replaceChildren();
            const placeholder = document.createElement("option");
            placeholder.value = "";
            placeholder.textContent = `주변 차량 정보 (${visible.length})`;
            this.vehicleList.appendChild(placeholder);
            for (const [key, group] of visible) {
                const option = document.createElement("option");
                option.value = key;
                option.textContent = group.userData.mtrLabel;
                this.vehicleList.appendChild(option);
            }
            if (poses.has(selected)) this.vehicleList.value = selected;
        }

        showHover(event) {
            if (!this.trainGroup.visible || !this.group.visible || this.disposed) return this.leave();
            const rect = this.viewer.renderer.domElement.getBoundingClientRect();
            this.pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1);
            this.viewer.camera.updateMatrixWorld();
            this.trainGroup.updateMatrixWorld(true);
            this.raycaster.setFromCamera(this.pointer, this.viewer.camera);
            const candidates = [...this.cars.values()].filter(group => group.visible);
            const hit = this.raycaster.intersectObjects(candidates, true)[0];
            if (!hit) return this.leave();
            let car = hit.object;
            while (car && !car.userData.mtrLabel) car = car.parent;
            if (!car) return this.leave();
            this.tooltip.textContent = car.userData.mtrLabel;
            this.tooltip.style.left = `${Math.max(8, Math.min(event.clientX + 12, scope.innerWidth - 330))}px`;
            this.tooltip.style.top = `${Math.min(event.clientY + 12, scope.innerHeight - 60)}px`;
            this.tooltip.hidden = false;
        }

        dispose() {
            this.disposed = true;
            clearTimeout(this.timer);
            this.controllers.forEach(controller => controller.abort());
            this.controllers.clear();
            this.app.events.removeEventListener("bluemapRenderFrame", this.frame);
            this.app.events.removeEventListener("bluemapMapChanged", this.mapChanged);
            this.viewer.renderer.domElement.removeEventListener("pointermove", this.hover);
            this.viewer.renderer.domElement.removeEventListener("pointerleave", this.leave);
            this.loadedChunks.forEach(group => this.removeRailChunk(group));
            this.loadedChunks.clear();
            this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
            this.loadedRouteChunks.clear();
            this.clearStations();
            this.clearTrainDots();
            this.group.removeFromParent();
            this.box.dispose();
            this.stationGeometry.dispose();
            this.materials.forEach(material => material.dispose());
            this.materials.clear();
            if (this.panel) this.panel.remove();
            if (this.tooltip) this.tooltip.remove();
        }
    }

    const api = { SnapshotBuffer, Overlay, normalizeSnapshot, catalogEntry, railChunks, routeChunks, angleBetween, safeUrl, STALE_MS, BUFFER_MS };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    else {
        scope.MinefedMtrBlueMap = api;
        function install() {
            const app = scope.bluemap, THREE = scope.BlueMap && scope.BlueMap.Three;
            if (!app || !app.mapViewer || !THREE) { setTimeout(install, 250); return; }
            if (scope.minefedMtrOverlay) scope.minefedMtrOverlay.dispose();
            scope.minefedMtrOverlay = new Overlay(app, THREE);
        }
        install();
    }
})(typeof window !== "undefined" ? window : globalThis);
