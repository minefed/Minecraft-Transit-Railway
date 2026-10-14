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
                    serviceId: typeof vehicle.serviceId === "string" ? vehicle.serviceId : "",
                    serviceName: typeof vehicle.serviceName === "string" ? vehicle.serviceName : "",
                    showServiceName: vehicle.showServiceName !== false,
                    operatorId: typeof vehicle.operatorId === "string" ? vehicle.operatorId : "",
                    operatorName: typeof vehicle.operatorName === "string" ? vehicle.operatorName : "",
                    sourceRouteId: typeof vehicle.sourceRouteId === "string" ? vehicle.sourceRouteId : "",
                    carCount: vehicle.cars.length,
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

    function publicRoutes(routes) {
        const result = new Map();
        for (const route of Array.isArray(routes) ? routes : []) {
            if (!route || typeof route.id !== "string" || result.has(route.id)) continue;
            result.set(route.id, { id: route.id, name: typeof route.name === "string" && route.name ? route.name : "철도 노선", color: color(route.color), stations: [...new Set(Array.isArray(route.stations) ? route.stations : [])], services: uniqueServices(route.services), operators: uniqueOperators(route.operators) });
        }
        return result;
    }

    function stationRoutes(station, routes) {
        const services = uniqueServices(station.services);
        return [...new Set(Array.isArray(station.routes) ? station.routes : [])].map(id => routes.get(id)).filter(Boolean)
            .map(route => ({ ...route, operators: uniqueOperators(services.filter(service => service.routeId === route.id).flatMap(service => service.operators)) }));
    }

    function transferStations(station, stations, routes) {
        if (typeof station.interchangeId !== "string" || !station.interchangeId || !(stations instanceof Map)) return [];
        return [...new Set(Array.isArray(station.transferStationIds) ? station.transferStationIds : [])]
            .filter(id => typeof id === "string" && id !== station.id)
            .map(id => stations.get(id))
            .filter(peer => peer && peer.interchangeId === station.interchangeId && stationRoutes(peer, routes).length);
    }

    function stationConnections(station, stations, routes) {
        const direct = stationRoutes(station, routes), peers = transferStations(station, stations, routes);
        const connected = new Map(direct.map(route => [route.id, route]));
        peers.forEach(peer => stationRoutes(peer, routes).forEach(route => {
            const existing = connected.get(route.id);
            connected.set(route.id, { ...route, operators: uniqueOperators([...(existing ? existing.operators : []), ...route.operators]) });
        }));
        return { direct, peers, routes: [...connected.values()], interchange: peers.length > 0 || direct.length > 1 };
    }

    function uniqueOperators(operators) {
        const result = new Map();
        for (const operator of Array.isArray(operators) ? operators : []) {
            if (!operator || typeof operator.id !== "string" || !operator.id || typeof operator.name !== "string" || !operator.name.trim() || result.has(operator.id)) continue;
            result.set(operator.id, { id: operator.id, name: operator.name });
        }
        return [...result.values()];
    }

    function operatorNames(operators) {
        return uniqueOperators(operators).map(operator => operator.name).join(" / ");
    }

    function uniqueServices(services) {
        const result = new Map();
        for (const service of Array.isArray(services) ? services : []) {
            if (!service || typeof service.id !== "string") continue;
            const existing = result.get(service.id);
            if (existing) { existing.operators = uniqueOperators([...existing.operators, ...uniqueOperators(service.operators)]); continue; }
            result.set(service.id, { id: service.id, name: typeof service.name === "string" ? service.name : "", showName: service.showName !== false, routeId: typeof service.routeId === "string" ? service.routeId : "", color: color(service.color), operators: uniqueOperators(service.operators) });
        }
        return [...result.values()];
    }

    function upcomingArrivals(arrivals, stationId, now, stale) {
        if (stale) return [];
        const result = new Map();
        for (const arrival of Array.isArray(arrivals) ? arrivals : []) {
            if (!arrival || arrival.stationId !== stationId || !finite(arrival.arrivalTime)) continue;
            if (arrival.arrivalTime < now && (!finite(arrival.departureTime) || arrival.departureTime < now)) continue;
            const key = `${arrival.vehicleId || ""}:${arrival.platformId || ""}:${arrival.routeId || ""}:${arrival.sourceRouteId || ""}:${arrival.arrivalTime}`;
            if (!result.has(key)) result.set(key, {
                ...arrival,
                serviceId: typeof arrival.serviceId === "string" ? arrival.serviceId : "",
                serviceName: typeof arrival.serviceName === "string" ? arrival.serviceName : "",
                showServiceName: arrival.showServiceName !== false,
                operatorId: typeof arrival.operatorId === "string" ? arrival.operatorId : "",
                operatorName: typeof arrival.operatorName === "string" ? arrival.operatorName : "",
                destination: typeof arrival.destination === "string" ? arrival.destination : "",
                minutes: Math.max(0, Math.ceil((arrival.arrivalTime - now) / 60000)), soon: arrival.arrivalTime - now < 30000,
                atStation: arrival.arrivalTime <= now, terminating: arrival.terminating === true, realtime: arrival.realtime !== false
            });
        }
        return [...result.values()].sort((a, b) => a.arrivalTime - b.arrivalTime).slice(0, 6);
    }

    function icon(name) {
        const paths = {
            train: ["M6 3h12a2 2 0 0 1 2 2v11a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2Z", "M4 11h16M8 6h8M8 15h.01M16 15h.01M8 19l-2 3M16 19l2 3"],
            route: ["M6 18V8a4 4 0 0 1 8 0v8a3 3 0 0 0 6 0V6", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0M18 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0"],
            rail: ["m8 2-4 20M16 2l4 20M7 5h10M6 10h12M5 15h14M4 20h16"],
            station: ["M12 21s7-6.3 7-12a7 7 0 0 0-14 0c0 5.7 7 12 7 12Z", "M9 9a3 3 0 1 0 6 0a3 3 0 1 0-6 0"],
            transfer: ["M4 8h15m-4-4 4 4-4 4M20 16H5m4-4-4 4 4 4"],
            chevron: ["m6 9 6 6 6-6"], close: ["m6 6 12 12M18 6 6 18"], layers: ["m12 3 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 16l9 5 9-5"],
            check: ["m5 12 4 4L19 6"]
        };
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("fill", "none"); svg.setAttribute("stroke", "currentColor");
        svg.setAttribute("stroke-width", "1.7"); svg.setAttribute("stroke-linecap", "round"); svg.setAttribute("stroke-linejoin", "round");
        svg.setAttribute("aria-hidden", "true");
        for (const d of paths[name] || paths.train) { const path = document.createElementNS("http://www.w3.org/2000/svg", "path"); path.setAttribute("d", d); svg.appendChild(path); }
        return svg;
    }

    const UI_STYLES = `
.mtr-ui{font:13px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI","Malgun Gothic",sans-serif;color:#273343;box-sizing:border-box}
.mtr-ui *,.mtr-station *,.mtr-train-dot *{box-sizing:border-box}.mtr-ui button{font:inherit;cursor:pointer}.mtr-ui svg,.mtr-station svg,.mtr-train-dot svg{width:20px;height:20px;flex-shrink:0}
.mtr-ui button:focus-visible,.mtr-station:focus-visible,.mtr-train-dot:focus-visible{outline:3px solid #75aaff;outline-offset:3px}
.mtr-panel{position:absolute;top:max(76px,env(safe-area-inset-top));right:max(16px,env(safe-area-inset-right));z-index:20;width:272px;max-width:calc(100vw - 24px);background:#fff;border:1px solid #e2e6eb;border-radius:18px;box-shadow:0 4px 18px #24384d18,0 1px 3px #24384d12;overflow:hidden}
.mtr-panel-header{display:flex;align-items:center;gap:11px;width:100%;padding:16px;border:0;background:#fff;color:inherit;text-align:left}.mtr-panel-header:hover{background:#f8fafc}
.mtr-brand{display:grid;place-items:center;width:38px;height:38px;border-radius:12px;color:#246bc0;background:#eaf2fe}.mtr-panel-heading{display:flex;flex:1;flex-direction:column;gap:2px}.mtr-panel-title{font-size:15px;font-weight:700;letter-spacing:-.3px}.mtr-panel-caption{font-size:11px;color:#788594}.mtr-fold{color:#7d8995;transition:transform .2s}.mtr-panel-header[aria-expanded="false"] .mtr-fold{transform:rotate(-90deg)}
.mtr-panel-content{padding:0 16px 13px;max-height:calc(100dvh - 176px);overflow:auto}.mtr-panel-content[hidden]{display:none}.mtr-section-title{display:flex;justify-content:space-between;align-items:center;margin:0 0 8px;font-size:11px;font-weight:600;letter-spacing:.3px;color:#7b8795}.mtr-route-count{font-weight:500;font-variant-numeric:tabular-nums}
.mtr-route-list{margin:0 -3px 15px;padding:0;max-height:224px;overflow:auto;list-style:none}.mtr-route-row{display:flex;align-items:center;gap:10px;padding:7px 3px}.mtr-route-symbol{width:26px;height:26px;display:grid;place-items:center;color:var(--mtr-route);background:white;border:2px solid var(--mtr-route);border-radius:9px;flex-shrink:0}.mtr-route-symbol svg{width:15px;height:15px}.mtr-route-name{font-size:13px;font-weight:600;flex:1;overflow-wrap:anywhere}.mtr-route-meta{font-size:10px;color:#8a95a2;white-space:nowrap}
.mtr-route-label{flex:1;min-width:0}.mtr-services{display:flex;flex-wrap:wrap;gap:4px;margin-top:4px}.mtr-service{display:inline-block;padding:1px 5px;color:#728091;background:#f1f4f7;border-radius:4px;font-size:9px;font-weight:500}.mtr-info .mtr-services{margin:12px 0 0}.mtr-info .mtr-service{font-size:10px;padding:3px 7px}
.mtr-operators{display:block;margin-top:3px;font-size:10px;font-weight:400;color:#788594;overflow-wrap:anywhere}.mtr-chip-copy{min-width:0;overflow-wrap:anywhere}.mtr-chip .mtr-operators{font-size:9px;margin-top:1px}.mtr-interchanges{border-top:1px solid #eef1f4;margin-top:15px;padding-top:12px}.mtr-interchanges h3{font-size:12px;margin:0 0 5px;color:#465b70}.mtr-interchanges p{font-size:11px;color:#8190a0;margin:0 0 9px;overflow-wrap:anywhere}.mtr-interchange-station{display:block;width:100%;padding:10px;margin-top:6px;border:1px solid #e3e9ef;border-radius:10px;background:#fafcfe;color:#344354;text-align:left}.mtr-interchange-station:hover{background:#eef5ff}.mtr-interchange-name{display:block;margin-bottom:6px;font-weight:600;font-size:12px}.mtr-interchange-station .mtr-chips{gap:4px}.mtr-interchange-station .mtr-chip{padding:3px 6px;font-size:10px}.mtr-info{max-height:calc(100dvh - 120px);overflow:auto}
.mtr-empty{padding:18px 8px 22px;text-align:center;color:#8995a4}.mtr-empty svg{display:block;width:30px;height:30px;margin:0 auto 9px;color:#acb8c5}.mtr-empty p{margin:0;font-size:12px;color:#697889}
.mtr-layer-grid{display:grid;grid-template-columns:1fr 1fr;gap:7px;padding-bottom:12px}.mtr-layer{display:flex;align-items:center;gap:7px;min-height:39px;padding:8px 9px;border:1px solid #e5e9ee;border-radius:10px;background:#fafbfc;color:#7b8795;text-align:left}.mtr-layer svg{width:17px;height:17px}.mtr-layer[aria-pressed="true"]{color:#266bc0;background:#eef5ff;border-color:#caddf7}.mtr-layer:hover{filter:brightness(.98)}.mtr-layer span{font-size:11px;font-weight:600;flex:1}.mtr-layer .mtr-check{width:13px;height:13px;opacity:0}.mtr-layer[aria-pressed="true"] .mtr-check{opacity:1}
.mtr-status{display:flex;align-items:center;gap:6px;border-top:1px solid #eef0f3;padding-top:11px;color:#83909e;font-size:10px;min-height:27px}.mtr-status:before{content:"";width:5px;height:5px;background:#95a2b1;border-radius:50%;flex-shrink:0}.mtr-status[data-state="live"]:before{background:#3baf7b}.mtr-status[data-state="offline"]:before{background:#d4a156}
.mtr-info{position:absolute;left:max(20px,env(safe-area-inset-left));bottom:max(28px,env(safe-area-inset-bottom));z-index:22;width:312px;max-width:calc(100vw - 32px);padding:18px 20px 20px;background:#fff;border:1px solid #e0e6ec;border-radius:18px;box-shadow:0 5px 25px #24384d20}.mtr-info[hidden]{display:none}.mtr-info-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:9px}.mtr-info-kind{display:flex;gap:6px;align-items:center;font-size:11px;color:#8190a0}.mtr-info-kind svg{width:15px;height:15px}.mtr-info-close{border:0;background:#f4f6f8;color:#8190a0;border-radius:50%;display:grid;place-items:center;width:27px;height:27px}.mtr-info-close svg{width:15px;height:15px}.mtr-info-title{font-size:21px;line-height:1.3;font-weight:700;letter-spacing:-.6px;margin:0 0 6px;overflow-wrap:anywhere}.mtr-info-description{font-size:12px;color:#8190a0;margin:0 0 14px}.mtr-chips{display:flex;flex-wrap:wrap;gap:7px}.mtr-chip{display:inline-flex;align-items:center;gap:6px;padding:5px 9px;border-radius:8px;background:#f4f7fb;color:#344354;font-size:11px;font-weight:600}.mtr-chip-dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--mtr-route)}
.mtr-arrivals{border-top:1px solid #eef1f4;margin-top:15px;padding-top:12px;max-height:260px;overflow:auto}.mtr-arrivals h3{font-size:11px;font-weight:600;color:#84909d;margin:0 0 8px}.mtr-arrival-row{display:flex;align-items:center;gap:8px;padding:9px 0}.mtr-arrival-line{width:3px;height:31px;border-radius:3px;background:var(--mtr-route);flex-shrink:0}.mtr-arrival-heading{font-size:12px;font-weight:600;color:#33475b}.mtr-arrival-meta{margin-top:3px;font-size:10px;color:#8c97a4}.mtr-arrival-copy{flex:1;min-width:0}.mtr-arrival-time{text-align:right;flex-shrink:0}.mtr-arrival-minutes{font-size:15px;font-weight:700;color:#2876c7;letter-spacing:-.4px}.mtr-arrival-clock{font-size:10px;color:#9aa3af;margin-top:2px}.mtr-arrival-empty{font-size:12px;color:#8895a4;padding:12px 0 6px}
.mtr-station{display:flex;align-items:center;gap:5px;border:0;padding:4px;background:none;color:#29425d;font:600 12px/1.2 -apple-system,BlinkMacSystemFont,"Segoe UI","Malgun Gothic",sans-serif;white-space:nowrap;cursor:pointer;pointer-events:auto;transform:translate(-12px,-12px)}.mtr-station-icon{display:grid;place-items:center;width:18px;height:18px;background:#fff;border:3px solid var(--mtr-route);border-radius:50%;box-shadow:0 1px 3px #17304a30;flex-shrink:0}.mtr-station-icon svg{width:13px;height:13px;color:#46576a}.mtr-station--transfer .mtr-station-icon{border:2px solid #66798b;width:22px;height:22px}.mtr-station-name{text-shadow:0 0 3px #fff,0 0 3px #fff,1px 1px 0 #fff,-1px -1px 0 #fff,1px -1px 0 #fff,-1px 1px 0 #fff;max-width:210px;overflow:hidden;text-overflow:ellipsis}.mtr-station--compact .mtr-station-name{display:none}.mtr-station:hover .mtr-station-name{color:#1769c4}.mtr-station:focus-visible .mtr-station-name{display:inline}
.mtr-train-dot{display:grid;place-items:center;width:27px;height:27px;padding:4px;border:2px solid #fff;border-radius:9px;background:var(--mtr-route);color:#fff;box-shadow:0 2px 6px #122f4d40;cursor:pointer;pointer-events:auto;transform:translate(-50%,-50%)}.mtr-train-dot svg{width:16px;height:16px}.mtr-train-dot:hover{filter:brightness(1.09)}
.mtr-tooltip{position:fixed;z-index:25;pointer-events:none;max-width:300px;padding:9px 12px;background:#fff;color:#263c51;border:1px solid #e3e9ef;box-shadow:0 3px 12px #223c5520;border-radius:10px;font:12px/1.4 sans-serif}.mtr-tooltip[hidden]{display:none}
@media(max-width:600px){.mtr-panel{top:max(70px,env(safe-area-inset-top));right:max(12px,env(safe-area-inset-right));width:248px;border-radius:16px}.mtr-panel-header{padding:12px 14px}.mtr-panel-content{max-height:calc(100dvh - 170px);padding:0 14px 11px}.mtr-panel-title{font-size:14px}.mtr-brand{width:32px;height:32px;border-radius:10px}.mtr-info{left:12px;bottom:max(20px,env(safe-area-inset-bottom));width:calc(100vw - 24px);max-width:360px;padding:15px 17px}.mtr-info-title{font-size:19px}.mtr-route-list{max-height:170px}}
@media(prefers-reduced-motion:reduce){.mtr-fold{transition:none}}
`;

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
        if (path !== "mtr-live/index.json") throw new Error("Invalid MTR bootstrap URL");
        const url = new URL(path, base), root = new URL("mtr-live/", base);
        if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname) || url.username || url.password) {
            throw new Error("MTR data URL must stay inside this map's mtr-live directory");
        }
        return url.href;
    }

    const WORLD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

    function safeApiBase(value) {
        if (typeof value !== "string" || !/^https?:\/\//.test(value) || /[\u0000-\u0020\u007f\\%?#@]/.test(value)) throw new Error("Invalid MTR API base");
        const url = new URL(value);
        const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
        if ((url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) || url.username || url.password ||
            !/^\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]*$/.test(url.pathname) || /(?:^|\/)\.{1,2}(?:\/|$)/.test(value)) {
            throw new Error("MTR API requires HTTPS or loopback HTTP and a fixed path");
        }
        return url.href.replace(/\/$/, "");
    }

    function safeApiUrl(path, base) {
        if (typeof path !== "string" || !(path === "catalog" || /^worlds\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(vehicles|infrastructure)$/.test(path))) {
            throw new Error("Invalid MTR API resource");
        }
        return `${safeApiBase(base)}/${path}`;
    }

    function bootstrap(data) {
        if (!data || data.schema !== 2 || !data.maps || typeof data.maps !== "object" || Array.isArray(data.maps)) throw new Error("Unsupported MTR bootstrap");
        const apiBase = safeApiBase(data.apiBase);
        for (const entry of Object.values(data.maps)) {
            if (!entry || typeof entry.worldId !== "string" || !entry.worldId.trim() || typeof entry.id !== "string" || !WORLD_ID.test(entry.id)) {
                throw new Error("Invalid MTR world mapping");
            }
        }
        return { schema: 2, apiBase, maps: data.maps };
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
            this.stationCatalog = new Map();
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
            this.click = event => { const car = this.pickCar(event); if (car && car.userData.car) this.showTrainDetail(car.userData.car); };
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
            group.userData.car = car;
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
            const connections = stationConnections(station, this.stationCatalog, this.routeCatalog), routes = connections.routes, names = routes.map(route => [route.name, operatorNames(route.operators)].filter(Boolean).join(" · "));
            group.userData.mtrLabel = `${station.name || "철도역"}${names.length ? ` · ${names.join(" / ")}` : ""}`;
            const HtmlMarker = scope.BlueMap && scope.BlueMap.HtmlMarker;
            if (HtmlMarker && typeof document !== "undefined") {
                const label = document.createElement("button");
                label.type = "button";
                label.className = `mtr-station${connections.interchange ? " mtr-station--transfer" : ""}`;
                label.style.setProperty("--mtr-route", routes[0] ? routes[0].color : NEUTRAL_COLOR);
                label.title = group.userData.mtrLabel;
                label.setAttribute("aria-label", group.userData.mtrLabel);
                const symbol = document.createElement("span"), name = document.createElement("span");
                symbol.className = "mtr-station-icon";
                if (connections.interchange) symbol.appendChild(icon("transfer"));
                name.className = "mtr-station-name"; name.textContent = station.name || "철도역";
                label.append(symbol, name);
                label.addEventListener("click", event => { event.stopPropagation(); this.showStationDetail(station); });
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
            button.className = "mtr-train-dot";
            button.style.setProperty("--mtr-route", car.color);
            button.appendChild(icon("train"));
            marker.element.replaceChildren(button);
            marker.userData.button = button;
            marker.userData.car = car;
            button.addEventListener("click", event => { event.stopPropagation(); this.showTrainDetail(marker.userData.car); });
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
            this.index = null;
            this.apiBase = null;
            this.sourceVersion = (this.sourceVersion || 0) + 1;
            this.catalog = { schema: 1, vehicles: {} };
            this.lastIndexAt = -Infinity;
            this.lastRailsAt = -Infinity;
            this.railRequestGeneration = null;
            this.catalogRequestGeneration = null;
            this.buffer.clear();
            this.arrivals = [];
            this.arrivalsTimestamp = -Infinity; this.arrivalsFreshAt = -Infinity;
            this.cars.clear();
            this.clearTrainDots();
            this.trainGroup.clear();
            this.clearDetail();
            this.missingLegs = 0;
            this.loadedChunks.forEach(group => this.removeRailChunk(group));
            this.loadedChunks.clear();
            this.chunks.clear();
            this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
            this.loadedRouteChunks.clear();
            this.routeChunks.clear();
            this.clearStations();
            this.stationData = [];
            this.stationCatalog = new Map();
            this.routeNames = new Map();
            this.routeCatalog = new Map();
            this.publicationRevision = null;
            this.staticVersion = (this.staticVersion || 0) + 1;
            this.infrastructureReady = false;
            this.infrastructureSignature = null;
            this.updateRouteLegend([]);
            this.group.visible = false;
            this.leave();
            this.setStatus("연결 중…");
        }

        async json(path, fromApi = false) {
            const controller = new AbortController();
            this.controllers.add(controller);
            const timeout = setTimeout(() => controller.abort(), 4500);
            try {
                const url = fromApi ? safeApiUrl(path, this.apiBase) : safeUrl(path, this.base);
                const response = await this.fetch(url, { cache: "no-store", credentials: fromApi ? "omit" : "same-origin", redirect: "error", signal: controller.signal });
                if (!response.ok) throw new Error(`MTR HTTP ${response.status}`);
                const data = await response.json();
                if (!data || data.schema !== (fromApi ? 1 : 2)) throw new Error("Unsupported MTR data schema");
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
                let index;
                try { index = bootstrap(await this.json("mtr-live/index.json")); }
                catch (error) {
                    if (current()) {
                        this.index = null; this.entry = null; this.apiBase = null;
                        this.sourceVersion = (this.sourceVersion || 0) + 1;
                        this.catalog = { schema: 1, vehicles: {} };
                        this.clearTransit();
                        this.setStatus("실시간 연결을 기다리고 있습니다", "offline");
                    }
                    throw error;
                }
                if (!current()) return;
                const entry = Object.prototype.hasOwnProperty.call(index.maps, mapId) ? index.maps[mapId] : null;
                if (index.apiBase !== this.apiBase || (entry && entry.id) !== (this.entry && this.entry.id)) {
                    this.sourceVersion = (this.sourceVersion || 0) + 1;
                    this.clearTransit();
                    this.catalog = { schema: 1, vehicles: {} };
                }
                this.index = index;
                this.apiBase = index.apiBase;
                this.lastIndexAt = this.now();
                this.entry = entry;
                if (entry && this.catalogRequestGeneration == null) this.refreshCatalog(generation, "catalog");
            }
            if (!current()) return;
            if (!this.entry) {
                this.setStatus("공개된 노선이 없습니다", "empty");
                return;
            }
            const path = `worlds/${this.entry.id}`;
            const requestedAt = this.now(), staticVersion = this.staticVersion;
            const data = await this.json(`${path}/vehicles`, true);
            if (!current() || staticVersion !== this.staticVersion) return;
            const receivedAt = this.now(), age = data.sourceAgeMs + Math.max(0, receivedAt - requestedAt);
            if (data.stale === true || !finite(data.sourceAgeMs) || data.sourceAgeMs < 0 || age > STALE_MS ||
                typeof data.publicationRevision !== "string" || !data.publicationRevision || !finite(data.timestamp)) {
                this.clearTransit();
                this.setStatus("실시간 연결을 기다리고 있습니다", "offline");
                return;
            }
            this.acceptPublicationRevision(data.publicationRevision);
            if (this.buffer.push(data, receivedAt)) {
                // Network travel and source age use the same freshness budget as newly captured data.
                this.buffer.lastFreshAt = receivedAt - age;
                this.acceptArrivals(data, receivedAt);
            }
            this.errors = 0;
            if (this.now() - this.lastRailsAt > 30000 && this.railRequestGeneration == null) this.refreshRails(generation, `${path}/infrastructure`);
        }

        async refreshCatalog(generation, path) {
            const sourceVersion = this.sourceVersion;
            const request = { generation, sourceVersion };
            this.catalogRequestGeneration = request;
            try {
                const catalog = await this.json(path, true);
                if (!this.disposed && generation === this.generation && sourceVersion === this.sourceVersion) {
                    if (catalog.stale === true) this.clearTransit();
                    else this.catalog = catalog;
                }
            } catch (error) {
                // Names are optional: catalog failures never stop neutral/route-colored vehicles.
            } finally {
                if (this.catalogRequestGeneration === request) this.catalogRequestGeneration = null;
            }
        }

        async refreshRails(generation, path) {
            const version = this.staticVersion, revision = this.publicationRevision;
            const request = { generation, version };
            this.railRequestGeneration = request;
            const current = () => !this.disposed && generation === this.generation && version === this.staticVersion && revision === this.publicationRevision;
            try {
                const rails = await this.json(path, true);
                if (!current()) return;
                if (rails.stale === true) { this.clearTransit(); return; }
                if ((typeof rails.publicationRevision === "string" ? rails.publicationRevision : null) !== revision) return;
                // The exporter changes its timestamp every refresh, even when the infrastructure is unchanged.
                // Keep the existing GPU meshes and HTML markers instead of rebuilding the whole network.
                const signature = JSON.stringify([rails.rails, rails.routes, rails.stations]);
                if (signature === this.infrastructureSignature) {
                    this.lastRailsAt = this.now();
                    return;
                }
                const chunks = railChunks(rails);
                this.loadedChunks.forEach(group => this.removeRailChunk(group));
                this.loadedChunks.clear();
                this.chunks = chunks;
                this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
                this.loadedRouteChunks.clear();
                this.routeChunks = routeChunks(rails);
                const routes = Array.isArray(rails.routes) ? rails.routes.filter(route => route && typeof route.id === "string") : [];
                this.routeNames = new Map(routes.map(route => [route.id, route.name || route.id]));
                this.routeCatalog = publicRoutes(routes);
                this.infrastructureReady = true;
                this.updateRouteLegend(routes);
                this.clearStations();
                this.stationData = (Array.isArray(rails.stations) ? rails.stations : []).filter(station => station && typeof station.id === "string" && vector(station.position));
                this.stationCatalog = new Map(this.stationData.map(station => [station.id, station]));
                if (this.selectedStation) {
                    const selected = this.stationCatalog.get(this.selectedStation.id);
                    if (selected) this.showStationDetail(selected); else this.clearDetail();
                }
                this.missingLegs = routes.reduce((sum, route) => sum + (finite(route.missingLegs) ? route.missingLegs : 0), 0);
                this.infrastructureSignature = signature;
                this.lastRailsAt = this.now();
                this.lastCullAt = -Infinity;
            } catch (error) {
                if (current()) this.lastRailsAt = this.now() - 25000;
            } finally {
                if (this.railRequestGeneration === request) this.railRequestGeneration = null;
            }
        }

        acceptPublicationRevision(revision) {
            if (revision === this.publicationRevision) return;
            this.clearTransit();
            this.publicationRevision = revision;
        }

        clearTransit() {
            this.publicationRevision = null;
            this.staticVersion = (this.staticVersion || 0) + 1;
            this.lastRailsAt = -Infinity;
            this.infrastructureReady = false;
            this.infrastructureSignature = null;
            this.buffer.clear();
            this.arrivals = [];
            this.arrivalsTimestamp = -Infinity; this.arrivalsFreshAt = -Infinity;
            this.cars.clear();
            this.clearTrainDots();
            this.trainGroup.clear();
            this.loadedChunks.forEach(group => this.removeRailChunk(group));
            this.loadedChunks.clear(); this.chunks.clear();
            this.loadedRouteChunks.forEach(group => this.removeRailChunk(group));
            this.loadedRouteChunks.clear(); this.routeChunks.clear();
            this.clearStations(); this.stationData = [];
            this.stationCatalog = new Map();
            this.routeCatalog = new Map(); this.routeNames = new Map();
            this.updateRouteLegend([]); this.clearDetail(); this.leave();
            this.lastCullAt = -Infinity;
        }

        acceptArrivals(data, receivedAt) {
            if (data.arrivalsTimestamp === 0) { this.arrivals = []; this.arrivalsFreshAt = -Infinity; return; }
            const timestamp = finite(data.arrivalsTimestamp) ? data.arrivalsTimestamp : data.timestamp;
            if (!finite(timestamp) || timestamp <= this.arrivalsTimestamp) return;
            this.arrivalsTimestamp = timestamp; this.arrivalsFreshAt = receivedAt;
            this.arrivals = Array.isArray(data.arrivals) ? data.arrivals : [];
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
                    group.userData.car = car;
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
                    const label = `${car.routeName ? `${car.routeName} · ` : ""}${catalogEntry(this.catalog, car).name} · ${car.vehicleId}`;
                    if (dot.userData.mtrLabel !== label) {
                        dot.userData.mtrLabel = label;
                        dot.userData.button.title = label;
                        dot.userData.button.setAttribute("aria-label", label);
                    }
                    if (dot.userData.color !== car.color) {
                        dot.userData.color = car.color;
                        dot.userData.button.style.setProperty("--mtr-route", car.color);
                    }
                    dot.userData.car = car;
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
                            group.userData.labelElement.classList.toggle("mtr-station--compact", !named);
                        }
                    }
                }
                if (this.entry) {
                    if (this.infrastructureReady && !this.routeCatalog.size) this.setStatus("공개된 노선이 없습니다", "empty");
                    else if (stale) this.setStatus("실시간 연결을 기다리고 있습니다", "offline");
                    else this.setStatus(`${new Set([...poses.values()].map(car => car.vehicleId)).size}대 운행 중 · 실시간`, "live");
                }
                this.updateArrivals();
            }
            // BlueMap already renders idle frames at 20 FPS. Calling redraw here on every live sample
            // keeps its full terrain render at the monitor refresh rate, even for distant or stopped trains.
            if (stale) this.leave();
        }

        createUi() {
            this.styleElement = document.createElement("style");
            this.styleElement.textContent = UI_STYLES;
            document.head.appendChild(this.styleElement);
            const panel = document.createElement("section"), header = document.createElement("button");
            panel.className = "mtr-ui mtr-panel";
            panel.setAttribute("aria-label", "철도 지도 설정");
            header.type = "button"; header.className = "mtr-panel-header";
            const brand = document.createElement("span"), heading = document.createElement("span"), title = document.createElement("span"), caption = document.createElement("span"), fold = icon("chevron");
            brand.className = "mtr-brand"; brand.appendChild(icon("train"));
            heading.className = "mtr-panel-heading";
            title.className = "mtr-panel-title"; title.textContent = "철도 지도";
            caption.className = "mtr-panel-caption"; caption.textContent = "노선과 실시간 열차";
            heading.append(title, caption); fold.setAttribute("class", "mtr-fold");
            header.append(brand, heading, fold);
            this.panelContent = document.createElement("div");
            this.panelContent.className = "mtr-panel-content"; this.panelContent.id = "mtr-panel-content";
            header.setAttribute("aria-controls", this.panelContent.id);
            this.panelToggle = header;
            this.setPanelExpanded(!(scope.matchMedia && scope.matchMedia("(max-width: 600px)").matches));
            header.addEventListener("click", () => this.setPanelExpanded(header.getAttribute("aria-expanded") !== "true"));
            const routeHeading = document.createElement("div"); routeHeading.className = "mtr-section-title";
            this.routeCount = document.createElement("span"); this.routeCount.className = "mtr-route-count";
            routeHeading.append(document.createTextNode("노선"), this.routeCount);
            this.routeLegend = document.createElement("ul"); this.routeLegend.className = "mtr-route-list";
            this.routeLegend.setAttribute("aria-label", "공개 노선");
            const layerHeading = document.createElement("div"); layerHeading.className = "mtr-section-title"; layerHeading.textContent = "지도에 표시";
            const layers = document.createElement("div"); layers.className = "mtr-layer-grid";
            this.layerButtons = new Map();
            for (const [property, text, symbol] of [["showTrains", "열차", "train"], ["showRoutes", "노선", "route"], ["showStations", "역 이름", "station"], ["showRails", "3D 선로", "rail"]]) {
                const button = document.createElement("button"), label = document.createElement("span"), check = icon("check");
                button.type = "button"; button.className = "mtr-layer"; button.setAttribute("aria-pressed", String(this[property]));
                label.textContent = text; label.style.whiteSpace = "nowrap"; check.setAttribute("class", "mtr-check"); button.append(icon(symbol), label, check);
                button.addEventListener("click", () => {
                    this[property] = !this[property]; button.setAttribute("aria-pressed", String(this[property]));
                    this.lastCullAt = -Infinity; this.viewer.redraw();
                });
                this.layerButtons.set(property, button); layers.appendChild(button);
            }
            this.status = document.createElement("div");
            this.status.className = "mtr-status"; this.status.setAttribute("role", "status"); this.status.setAttribute("aria-live", "polite");
            this.panelContent.append(routeHeading, this.routeLegend, layerHeading, layers, this.status);
            panel.append(header, this.panelContent);
            this.detail = document.createElement("section"); this.detail.className = "mtr-ui mtr-info"; this.detail.hidden = true;
            this.detail.setAttribute("aria-label", "선택한 철도 정보"); this.detail.setAttribute("aria-live", "polite");
            this.tooltip = document.createElement("div");
            this.tooltip.className = "mtr-tooltip"; this.tooltip.hidden = true; this.tooltip.setAttribute("role", "tooltip");
            this.viewer.rootElement.append(panel, this.detail, this.tooltip);
            this.panel = panel;
            this.viewer.renderer.domElement.addEventListener("pointermove", this.hover);
            this.viewer.renderer.domElement.addEventListener("pointerleave", this.leave);
            this.viewer.renderer.domElement.addEventListener("click", this.click);
        }

        setPanelExpanded(expanded) {
            this.panelContent.hidden = !expanded;
            this.panelToggle.setAttribute("aria-expanded", String(expanded));
            this.panelToggle.setAttribute("aria-label", expanded ? "철도 지도 접기" : "철도 지도 펼치기");
        }

        setStatus(text, state = "idle") {
            if (this.status) {
                if (this.status.textContent !== text) this.status.textContent = text;
                this.status.setAttribute("data-state", state);
            }
        }

        clearDetail() {
            this.selectedStation = null; this.arrivalsElement = null; this.arrivalsSignature = null;
            if (this.detail) { this.detail.replaceChildren(); this.detail.hidden = true; }
        }

        showDetail({ title, kind, description, routes, symbol }) {
            if (!this.detail) return;
            this.selectedStation = null; this.arrivalsElement = null; this.arrivalsSignature = null;
            this.detail.replaceChildren();
            const top = document.createElement("div"), category = document.createElement("span"), close = document.createElement("button");
            top.className = "mtr-info-top"; category.className = "mtr-info-kind";
            category.append(icon(symbol), document.createTextNode(kind));
            close.type = "button"; close.className = "mtr-info-close"; close.setAttribute("aria-label", "철도 정보 닫기"); close.appendChild(icon("close"));
            close.addEventListener("click", () => this.clearDetail()); top.append(category, close);
            const heading = document.createElement("h2"), subtitle = document.createElement("p");
            heading.className = "mtr-info-title"; heading.textContent = title;
            subtitle.className = "mtr-info-description"; subtitle.textContent = description;
            this.detail.append(top, heading, subtitle, this.routeChips(routes)); this.detail.hidden = false;
        }

        routeChips(routes) {
            const chips = document.createElement("div");
            chips.className = "mtr-chips";
            for (const route of routes) {
                const chip = document.createElement("span"), dot = document.createElement("span"), copy = document.createElement("span");
                chip.className = "mtr-chip"; dot.className = "mtr-chip-dot"; dot.style.setProperty("--mtr-route", color(route.color));
                copy.className = "mtr-chip-copy"; copy.textContent = route.name;
                const operators = operatorNames(route.operators);
                if (operators) { const label = document.createElement("span"); label.className = "mtr-operators"; label.textContent = operators; copy.appendChild(label); }
                dot.setAttribute("aria-hidden", "true"); chip.append(dot, copy); chips.appendChild(chip);
            }
            return chips;
        }

        showStationDetail(station) {
            const connections = stationConnections(station, this.stationCatalog, this.routeCatalog), routes = connections.direct;
            this.showDetail({ title: station.name || "철도역", kind: connections.interchange ? "환승역" : "철도역", description: routes.length > 1 ? `${routes.length}개 노선에서 이용할 수 있습니다` : "운행 노선", routes, symbol: connections.interchange ? "transfer" : "station" });
            if (!this.detail) return;
            const services = uniqueServices(station.services).filter(service => service.showName && (!service.routeId || this.routeCatalog.has(service.routeId)));
            if (services.length) this.detail.appendChild(this.serviceBadges(services));
            if (connections.peers.length) {
                const section = document.createElement("div"), heading = document.createElement("h3"), description = document.createElement("p");
                section.className = "mtr-interchanges"; heading.textContent = "간접환승";
                description.textContent = [typeof station.interchangeName === "string" ? station.interchangeName : "", "연결된 역을 선택하면 해당 역의 도착 정보를 볼 수 있습니다."].filter(Boolean).join(" · ");
                section.append(heading, description);
                for (const peer of connections.peers) {
                    const button = document.createElement("button"), name = document.createElement("span"), peerRoutes = stationRoutes(peer, this.routeCatalog);
                    button.type = "button"; button.className = "mtr-interchange-station";
                    name.className = "mtr-interchange-name"; name.textContent = peer.name || "철도역";
                    button.setAttribute("aria-label", `${peer.name || "철도역"} · ${peerRoutes.map(route => [route.name, operatorNames(route.operators)].filter(Boolean).join(" · ")).join(" / ")} 도착 정보 보기`);
                    button.append(name, this.routeChips(peerRoutes));
                    button.addEventListener("click", () => this.showStationDetail(peer)); section.appendChild(button);
                }
                this.detail.appendChild(section);
            }
            this.selectedStation = station;
            this.arrivalsElement = document.createElement("div"); this.arrivalsElement.className = "mtr-arrivals";
            this.arrivalsElement.setAttribute("aria-live", "polite");
            this.detail.appendChild(this.arrivalsElement); this.updateArrivals();
        }

        showTrainDetail(car) {
            const route = this.routeCatalog.get(car.routeId);
            const model = catalogEntry(this.catalog, car).name;
            const operators = uniqueOperators([{ id: car.operatorId, name: car.operatorName }]);
            this.showDetail({ title: car.routeName || "운행 열차", kind: "실시간 열차", description: [car.showServiceName === false ? "" : car.serviceName, model, car.carCount ? `${car.carCount}량 편성` : ""].filter(Boolean).join(" · "), routes: route ? [{ ...route, operators }] : [], symbol: "train" });
        }

        serviceBadges(services) {
            const element = document.createElement("div"); element.className = "mtr-services";
            for (const service of uniqueServices(services)) {
                if (!service.showName) continue;
                const badge = document.createElement("span"); badge.className = "mtr-service"; badge.textContent = service.name; element.appendChild(badge);
            }
            return element;
        }

        updateArrivals() {
            if (!this.selectedStation || !this.arrivalsElement) return;
            const now = this.now(), stale = this.buffer.isStale(now) || now - this.arrivalsFreshAt > STALE_MS;
            const serverNow = this.buffer.offset === null ? Date.now() : now - this.buffer.offset;
            const arrivals = upcomingArrivals(this.arrivals, this.selectedStation.id, serverNow, stale).filter(arrival => this.routeCatalog.has(arrival.routeId));
            const signature = JSON.stringify([stale, arrivals.map(arrival => [arrival.vehicleId, arrival.arrivalTime, arrival.minutes, arrival.soon, arrival.atStation, arrival.terminating, arrival.realtime, arrival.destination, arrival.serviceName, arrival.showServiceName, arrival.routeId, arrival.operatorId, arrival.operatorName])]);
            if (signature === this.arrivalsSignature) return;
            this.arrivalsSignature = signature; this.arrivalsElement.replaceChildren();
            const title = document.createElement("h3"); title.textContent = "곧 도착하는 열차"; this.arrivalsElement.appendChild(title);
            if (!arrivals.length) {
                const empty = document.createElement("div"); empty.className = "mtr-arrival-empty";
                empty.textContent = stale ? "도착 정보를 갱신하지 못했습니다" : "도착 정보가 없습니다";
                this.arrivalsElement.appendChild(empty);
            }
            for (const arrival of arrivals) {
                const route = this.routeCatalog.get(arrival.routeId), row = document.createElement("div"), line = document.createElement("span"), copy = document.createElement("div"), heading = document.createElement("div"), meta = document.createElement("div"), time = document.createElement("div"), minutes = document.createElement("div"), clock = document.createElement("div");
                row.className = "mtr-arrival-row"; line.className = "mtr-arrival-line"; line.style.setProperty("--mtr-route", route.color);
                copy.className = "mtr-arrival-copy"; heading.className = "mtr-arrival-heading"; heading.textContent = arrival.destination ? `${arrival.destination} 방면` : route.name;
                meta.className = "mtr-arrival-meta"; meta.textContent = [route.name, arrival.showServiceName ? arrival.serviceName : "", arrival.operatorName, arrival.realtime ? "" : "시간표 예정"].filter(Boolean).join(" · ");
                time.className = "mtr-arrival-time"; minutes.className = "mtr-arrival-minutes";
                minutes.textContent = arrival.atStation ? (arrival.realtime ? arrival.terminating ? "종착" : "정차 중" : "출발 예정") : arrival.soon ? "곧 도착" : `${arrival.minutes}분`;
                clock.className = "mtr-arrival-clock"; clock.textContent = new Date(arrival.arrivalTime).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });
                copy.append(heading, meta); time.append(minutes, clock); row.append(line, copy, time); this.arrivalsElement.appendChild(row);
            }
        }

        updateRouteLegend(routes) {
            if (!this.routeLegend) return;
            this.routeLegend.replaceChildren();
            const entries = publicRoutes(routes);
            if (this.routeCount) this.routeCount.textContent = entries.size ? `${entries.size}개 노선` : "";
            if (!entries.size) {
                const empty = document.createElement("li"), message = document.createElement("p");
                empty.className = "mtr-empty"; message.textContent = "공개된 노선이 없습니다"; empty.append(icon("route"), message); this.routeLegend.appendChild(empty);
            }
            for (const route of entries.values()) {
                const row = document.createElement("li"), swatch = document.createElement("span"), label = document.createElement("div"), name = document.createElement("span"), meta = document.createElement("span");
                row.className = "mtr-route-row"; swatch.className = "mtr-route-symbol"; swatch.style.setProperty("--mtr-route", route.color);
                swatch.appendChild(icon("train")); name.className = "mtr-route-name"; name.textContent = route.name;
                meta.className = "mtr-route-meta"; meta.textContent = `${route.stations.length}개 역`;
                label.className = "mtr-route-label"; label.appendChild(name);
                const operators = operatorNames(route.operators);
                if (operators) { const names = document.createElement("span"); names.className = "mtr-operators"; names.textContent = operators; label.appendChild(names); }
                if (route.services.some(service => service.showName)) label.appendChild(this.serviceBadges(route.services));
                row.append(swatch, label, meta);
                this.routeLegend.appendChild(row);
            }
        }

        pickCar(event) {
            if (!this.trainGroup.visible || !this.group.visible || this.disposed) return null;
            const rect = this.viewer.renderer.domElement.getBoundingClientRect();
            this.pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1);
            this.viewer.camera.updateMatrixWorld();
            this.trainGroup.updateMatrixWorld(true);
            this.raycaster.setFromCamera(this.pointer, this.viewer.camera);
            const candidates = [...this.cars.values()].filter(group => group.visible);
            const hit = this.raycaster.intersectObjects(candidates, true)[0];
            if (!hit) return null;
            let car = hit.object;
            while (car && !car.userData.mtrLabel) car = car.parent;
            return car || null;
        }

        showHover(event) {
            const car = this.pickCar(event);
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
            this.viewer.renderer.domElement.removeEventListener("click", this.click);
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
            if (this.detail) this.detail.remove();
            if (this.tooltip) this.tooltip.remove();
            if (this.styleElement) this.styleElement.remove();
        }
    }

    const api = { SnapshotBuffer, Overlay, normalizeSnapshot, catalogEntry, publicRoutes, stationRoutes, transferStations, stationConnections, uniqueOperators, uniqueServices, upcomingArrivals, railChunks, routeChunks, angleBetween, safeUrl, safeApiBase, safeApiUrl, bootstrap, STALE_MS, BUFFER_MS };
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
