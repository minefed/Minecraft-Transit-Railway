/* Run with node --test tools/bluemap/mtr-live.test.cjs. No third-party dependencies. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { SnapshotBuffer, normalizeSnapshot, catalogEntry, trainLabel, modelName, publicRoutes, stationRoutes, stationConnections, uniqueOperators, uniqueServices, upcomingArrivals, railChunks, routeChunks, safeUrl, safeApiBase, safeApiUrl, bootstrap, Overlay, STALE_MS, BUFFER_MS } = require('../../fabric/src/main/resources/assets/mtr/bluemap/mtr-live.js');

const vehicle = (x = 0, yaw = 0, id = 'custom:unlisted', extra = {}) => ({ id: 'fleet-1', routeName: '<img src=x onerror=alert(1)>', color: '#3F81DA', cars: [{ id, position: [x, 64, -20], yaw, pitch: 0, length: 24, width: 3.2 }], ...extra });
const snapshot = (timestamp, vehicles = [vehicle()]) => ({ schema: 1, timestamp, vehicles });
const worldId = 'bc1529d0-314b-3547-a7be-6dea7491573d';
const apiBase = 'https://api.example.test/v1/transit/minefed';
const manifest = () => ({schema:2,apiBase,maps:{map:{worldId:'minecraft/overworld',id:worldId}}});
const live = (timestamp, vehicles) => ({...snapshot(timestamp,vehicles),publicationRevision:'current',sourceAgeMs:0,stale:false});
function overlayState(extra = {}) {
    return Object.assign(Object.create(Overlay.prototype), {
        generation:1,mapId:'map',disposed:false,index:null,entry:null,sourceVersion:0,staticVersion:0,
        base:'https://map.example.test/sub/',now:()=>1000,buffer:new SnapshotBuffer(),controllers:new Set(),
        cars:new Map(),trainGroup:{clear(){}},loadedChunks:new Map(),chunks:new Map(),
        loadedRouteChunks:new Map(),routeChunks:new Map(),stationData:[],stationCatalog:new Map(),
        catalog:{schema:1,vehicles:{}},arrivals:[],lastIndexAt:-Infinity,lastRailsAt:-Infinity,
        removeRailChunk(){},clearTrainDots(){},clearStations(){},updateRouteLegend(){},clearDetail(){this.selection=null},leave(){},setStatus(){}
    },extra);
}

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
    assert.equal(safeUrl('mtr-live/index.json', base), 'https://map.example.test/sub/map/mtr-live/index.json');
    for (const path of ['mtr-live/world-a-vehicles.json', 'mtr-live/catalog.json', 'https://evil.test/mtr-live/a.json', '../secret.json', 'mtr-live/../secret.json', 'mtr-live/%2e%2e%2fsecret.json', 'mtr-live/a.json?redirect=1', 'mtr-live/a.json#fragment', '//evil.test/mtr-live/a.json']) {
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
    resolveIndex({ ...manifest(), maps: { old: { worldId:'minecraft/overworld',id:worldId } } });
    await pending;
    assert.equal(state.index, null);
    assert.equal(state.entry, undefined);
});

test('catalog failures do not prevent unknown vehicles from loading', async () => {
    const state = overlayState({
        generation: 1, mapId: 'map', disposed: false, index: null, now: () => 1000, lastRailsAt: 1000,
        refreshRails(){},
        json: async path => {
            if (path === 'mtr-live/index.json') return manifest();
            if (path === 'catalog') throw new Error('Missing catalog');
            return live(3000, [vehicle(42)]);
        }
    });
    await Overlay.prototype.pollOnce.call(state);
    assert.equal(state.buffer.sample(1000).get('fleet-1:0').position[0], 42);
});

test('slow static data never blocks subsequent live vehicle requests', async () => {
    let requests = 0, finishRails;
    const state = overlayState({
        generation: 1, mapId: 'map', disposed: false, index: manifest(), entry: manifest().maps.map, apiBase,
        now: () => 1000, lastIndexAt: 1000, lastRailsAt: -Infinity, buffer: new SnapshotBuffer(),
        acceptPublicationRevision(){}, acceptArrivals(){},
        refreshRails(generation) {
            this.railRequestGeneration = generation;
            return new Promise(resolve => { finishRails = resolve; });
        },
        json: async () => live(++requests * 500)
    });
    await Overlay.prototype.pollOnce.call(state);
    await Overlay.prototype.pollOnce.call(state);
    assert.equal(requests, 2);
    assert.equal(state.buffer.snapshots.length, 2);
    finishRails();
});

test('API bases and derived resources reject credentials, insecure origins and path injection', () => {
    for (const base of [apiBase, `${apiBase}/`, 'http://localhost:3100/v1/transit/minefed', 'http://127.0.0.1:3100/v1', 'http://[::1]:3100/v1']) {
        assert.equal(safeApiUrl(`worlds/${worldId}/vehicles`, base), `${safeApiBase(base)}/worlds/${worldId}/vehicles`);
    }
    for (const base of ['http://api.example.test/v1', 'https://user:secret@api.example.test/v1', 'https://api.example.test/v1?key=secret',
        'https://api.example.test/v1#fragment', '//api.example.test/v1', 'https:api.example.test/v1', 'https://api.example.test/a/../v1',
        'https://api.example.test/%2e%2e/v1', 'https://api.example.test/a\\v1', 'https://api.example.test/v1\n', 'http://localhost.evil.test/v1']) {
        assert.throws(() => safeApiBase(base), base);
    }
    for (const path of ['mtr-live/index.json','../catalog','catalog?token=x',`${apiBase}/catalog`,'//evil.test/catalog',
        `worlds/${worldId}/vehicles/other`,`worlds/${worldId}/../vehicles`,`worlds/${worldId}/vehicles#x`,'worlds/%2e%2e/vehicles']) {
        assert.throws(() => safeApiUrl(path, apiBase), path);
    }
    assert.throws(() => bootstrap({...manifest(),schema:1}));
    assert.throws(() => bootstrap({...manifest(),maps:{map:{id:'../private',worldId:'minecraft/overworld'}}}));
});

test('schema two bootstrap derives credential-free API requests and never loads old local snapshots', async () => {
    const requests=[];
    const state=overlayState({fetch:async(url,options)=>{
        requests.push({url,options});
        const data=url.endsWith('/mtr-live/index.json')?manifest():url.endsWith('/catalog')?{schema:1,vehicles:{}}:
            url.endsWith('/vehicles')?live(1000):{schema:1,publicationRevision:'current',rails:[],routes:[],stations:[]};
        return {ok:true,json:async()=>data};
    }});
    await state.pollOnce();
    await new Promise(resolve=>setImmediate(resolve));
    assert.deepEqual(requests.map(request=>request.url),[
        'https://map.example.test/sub/mtr-live/index.json',`${apiBase}/catalog`,`${apiBase}/worlds/${worldId}/vehicles`,`${apiBase}/worlds/${worldId}/infrastructure`
    ]);
    assert.equal(requests[0].options.credentials,'same-origin');
    assert.ok(requests.slice(1).every(request=>request.options.credentials==='omit' && request.options.redirect==='error'));
    assert.equal(state.buffer.snapshots.length,1);
    assert.equal(state.infrastructureReady,true);
});

test('missing, malformed and old bootstrap documents clear existing transit without local fallback', async () => {
    for (const result of [new Error('404'),{schema:1,maps:{}},{...manifest(),apiBase:'http://public.example.test/api'}]) {
        const requests=[];
        const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:-Infinity,
            cars:new Map([['previous',{}]]),chunks:new Map([['previous',{}]]),stationData:[{id:'previous'}],
            json:async path=>{requests.push(path);if(result instanceof Error)throw result;return result}
        });
        await assert.rejects(state.pollOnce());
        assert.deepEqual(requests,['mtr-live/index.json']);
        assert.equal(state.entry,null);assert.equal(state.cars.size,0);assert.equal(state.chunks.size,0);assert.deepEqual(state.stationData,[]);
    }
});

test('expired or missing API source age clears every transit layer, even on a first successful fetch', async () => {
    for (const extra of [{stale:true},{sourceAgeMs:6001},{sourceAgeMs:undefined},{sourceAgeMs:-1},{sourceAgeMs:'0'},{sourceAgeMs:NaN}]) {
        const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,
            cars:new Map([['old',{}]]),chunks:new Map([['old',{}]]),routeChunks:new Map([['old',{}]]),stationData:[{id:'old'}],
            arrivals:[{stationId:'old'}],json:async()=>({...live(1000),...extra})
        });
        await state.pollOnce();
        assert.equal(state.buffer.snapshots.length,0);assert.equal(state.cars.size,0);assert.equal(state.chunks.size,0);
        assert.equal(state.routeChunks.size,0);assert.deepEqual(state.stationData,[]);assert.deepEqual(state.arrivals,[]);
    }
});

test('source age and network delay reduce the remaining freshness budget without using the browser wall clock', async () => {
    let now=1000;
    const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,now:()=>now,refreshRails(){},
        json:async()=>{now+=500;return {...live(1_700_000_000_000),sourceAgeMs:4000}}
    });
    await state.pollOnce();
    assert.equal(state.buffer.isStale(now),false);
    assert.equal(state.buffer.isStale(now+1501),true);
    now+=1000;state.json=async()=>{now+=4500;return {...live(1_700_000_001_000),sourceAgeMs:2000}};
    await state.pollOnce();
    assert.equal(state.buffer.snapshots.length,0);
});

test('stale infrastructure clears the fleet and prevents an older in-flight live response from repopulating it', async () => {
    let finishVehicle;
    const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,publicationRevision:'current',
        json:async path=>path.endsWith('/vehicles')?new Promise(resolve=>{finishVehicle=resolve}):{schema:1,publicationRevision:'current',stale:true,rails:[]}
    });
    state.buffer.push(live(500),500);
    const pending=state.pollOnce();
    await state.refreshRails(1,`worlds/${worldId}/infrastructure`);
    finishVehicle(live(1000));await pending;
    assert.equal(state.buffer.snapshots.length,0);assert.equal(state.publicationRevision,null);
});

test('a stale optional catalogue cannot clear a fresh map when another world has stopped', async () => {
    let finishCatalog;
    const state=overlayState({publicationRevision:'current',catalog:{schema:1,vehicles:{model:{name:'Previous name'}}},
        cars:new Map([['current',{}]]),chunks:new Map([['current',{}]]),stationData:[{id:'current'}],
        json:async()=>new Promise(resolve=>{finishCatalog=resolve})});
    const pending=state.refreshCatalog(1,'catalog');
    state.buffer.push(live(1000),1000);
    finishCatalog({schema:1,stale:true,vehicles:{}});await pending;
    assert.deepEqual(state.catalog,{schema:1,vehicles:{}});
    assert.equal(state.publicationRevision,'current');assert.equal(state.buffer.snapshots.length,1);
    assert.equal(state.cars.size,1);assert.equal(state.chunks.size,1);assert.deepEqual(state.stationData,[{id:'current'}]);
});

test('first-received ETA accounts for arrival cache age, source age and request travel time', async () => {
    let now=1000;
    const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,now:()=>now,refreshRails(){},
        json:async()=>{now+=200;return {...live(100000),sourceAgeMs:1000,arrivalsTimestamp:95000,arrivals:[{stationId:'first'}]}}
    });
    await state.pollOnce();
    assert.equal(state.buffer.isStale(now),false);
    assert.equal(state.arrivalsFreshAt,-5000);
    assert.ok(now-state.arrivalsFreshAt>STALE_MS);
    state.json=async()=>({...live(100500),sourceAgeMs:1000,arrivalsTimestamp:99500,arrivals:[{stationId:'first'}]});
    await state.pollOnce();
    assert.equal(state.arrivalsFreshAt,now-2000);
    assert.ok(now-state.arrivalsFreshAt<STALE_MS);
});

test('a late API vehicle response for the previous map cannot become visible', async () => {
    let finishVehicle;
    const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,
        json:async()=>new Promise(resolve=>{finishVehicle=resolve})});
    const pending=state.pollOnce();
    state.generation++;state.mapId='new';state.clearTransit();
    finishVehicle(live(1000));await pending;
    assert.equal(state.buffer.snapshots.length,0);
});

test('publication changes discard old infrastructure without overlapping static requests', async () => {
    let finishOld,staticRequests=0,timestamp=1000;
    const state=overlayState({index:manifest(),entry:manifest().maps.map,apiBase,lastIndexAt:1000,publicationRevision:'old',
        json:async path=>{
            if(path.endsWith('/vehicles'))return {...live(++timestamp),publicationRevision:'new'};
            staticRequests++;
            if(staticRequests===1)return new Promise(resolve=>{finishOld=resolve});
            return {schema:1,publicationRevision:'new',rails:[],routes:[],stations:[]};
        }
    });
    const pending=state.refreshRails(1,`worlds/${worldId}/infrastructure`);
    await state.pollOnce();assert.equal(state.publicationRevision,'new');assert.equal(staticRequests,1);
    await state.pollOnce();assert.equal(staticRequests,1);
    finishOld({schema:1,publicationRevision:'old',rails:[],routes:[],stations:[{id:'old',position:[0,0,0]}]});
    await pending;assert.deepEqual(state.stationData,[]);
    await state.pollOnce();await new Promise(resolve=>setImmediate(resolve));
    assert.equal(staticRequests,2);assert.equal(state.infrastructureReady,true);
});

test('poll waits for its request before scheduling the next cycle', async t => {
    t.mock.timers.enable({apis:['setTimeout']});
    let requests=0,finish;
    const state={disposed:false,poll:Overlay.prototype.poll,pollOnce:()=>{requests++;return new Promise(resolve=>{finish=resolve})}};
    const pending=state.poll();
    t.mock.timers.tick(2000);assert.equal(requests,1);
    finish();await pending;
    t.mock.timers.tick(499);assert.equal(requests,1);
    t.mock.timers.tick(1);assert.equal(requests,2);
    state.disposed=true;finish();
});

test('grouped routes and station badges deduplicate IDs and never invent private catalog entries', () => {
    const routes = publicRoutes([
        {id:'group:central',name:'중앙선',color:'#317fce',stations:['a','a','b'],services:[{id:'local',name:'일반'},{id:'express',name:'급행'},{id:'local',name:'일반'}]},
        {id:'group:central',name:'중앙선 급행'}, {id:'group:coast',name:'해안선',color:'#3ba879'}
    ]);
    assert.equal(routes.size,2);
    assert.deepEqual(routes.get('group:central').stations,['a','b']);
    assert.equal(routes.get('group:central').services.length,2);
    assert.deepEqual(stationRoutes({routes:['group:central','group:central','private','group:coast']},routes).map(route=>route.name),['중앙선','해안선']);
    assert.equal(uniqueServices(undefined).length,0);
});

test('shared service IDs deduplicate independently of source routes and name visibility defaults to true', () => {
    const services = uniqueServices([
        {id:'service:central:local',name:'완행',sourceRouteId:'a'},
        {id:'service:central:local',name:'완행',sourceRouteId:'b'},
        {id:'service:central:express',name:'급행',showName:false}
    ]);
    assert.equal(services.length,2);
    assert.equal(services[0].showName,true);
    assert.equal(services[1].showName,false);
    const cars = normalizeSnapshot(snapshot(1,[
        vehicle(0,0,'known',{serviceId:'service:central:local',serviceName:'완행'}),
        {...vehicle(20,0,'known',{serviceId:'service:central:express',serviceName:'급행',showServiceName:false}),id:'fleet-2'}
    ])).cars;
    assert.equal(cars.size,2);
    assert.equal(cars.get('fleet-1:0').serviceId,'service:central:local');
    assert.equal(cars.get('fleet-1:0').showServiceName,true);
    assert.equal(cars.get('fleet-2:0').showServiceName,false);
    const arrivals = upcomingArrivals([
        {stationId:'a',arrivalTime:1000,vehicleId:'one',serviceId:'service:central:local',serviceName:'완행'},
        {stationId:'a',arrivalTime:2000,vehicleId:'two',serviceId:'service:central:express',serviceName:'급행',showServiceName:false}
    ],'a',0,false);
    assert.equal(arrivals.length,2);
    assert.equal(arrivals[0].serviceId,'service:central:local');
    assert.equal(arrivals[0].showServiceName,true);
    assert.equal(arrivals[1].showServiceName,false);
});

test('operators are optional, normalized and combined for shared services without inventing names', () => {
    const company={id:'company',name:'<운영사>'}, other={id:'other',name:'다른 운영사'};
    const input=[company,company,null,{id:'missing-name'},{id:3,name:'bad'},{id:'blank',name:'  '}];
    assert.deepEqual(uniqueOperators(input),[company]);
    const routes=publicRoutes([{id:'old'},{id:'new',operators:input}]);
    assert.deepEqual(routes.get('old').operators,[]);
    assert.deepEqual(routes.get('new').operators,[company]);
    const services=uniqueServices([{id:'shared',operators:[company]},{id:'shared',operators:[other,company]}]);
    assert.deepEqual(services[0].operators,[company,other]);
    const car=normalizeSnapshot(snapshot(1,[vehicle(0,0,'model',{operatorId:company.id,operatorName:company.name})])).cars.get('fleet-1:0');
    assert.equal(car.operatorId,company.id); assert.equal(car.operatorName,company.name);
    assert.equal(normalizeSnapshot(snapshot(1)).cars.get('fleet-1:0').operatorName,'');
});

test('only configured public peers create indirect transfers, including a shared display route', () => {
    const routes=publicRoutes([{id:'red'},{id:'blue'}]);
    const a={id:'a',name:'수인역',routes:['red']}, b={id:'b',name:'수인역',routes:['blue']};
    const stations=new Map([[a.id,a],[b.id,b],['private',{id:'private',routes:['secret'],interchangeId:'interchange:suin'}],['unrelated',{id:'unrelated',routes:['blue']}] ]);
    assert.equal(stationConnections(a,stations,routes).interchange,false);
    Object.assign(a,{interchangeId:'interchange:suin',transferStationIds:['b','b','a','private','missing','unrelated']});
    b.interchangeId=a.interchangeId;
    const connected=stationConnections(a,stations,routes);
    assert.equal(connected.interchange,true);
    assert.deepEqual(connected.direct.map(route=>route.id),['red']);
    assert.deepEqual(connected.peers.map(station=>station.id),['b']);
    assert.deepEqual(connected.routes.map(route=>route.id),['red','blue']);
    b.routes=['red'];
    a.services=[{id:'local-a',routeId:'red',operators:[{id:'a-op',name:'운영사 A'}]}];
    b.services=[{id:'local-b',routeId:'red',operators:[{id:'b-op',name:'운영사 B'}]}];
    assert.equal(stationConnections(a,stations,routes).interchange,true);
    assert.equal(stationConnections(a,stations,routes).routes.length,1);
    assert.deepEqual(stationConnections(a,stations,routes).routes[0].operators.map(operator=>operator.id),['a-op','b-op']);
    stations.delete('b');
    assert.equal(stationConnections(a,stations,routes).interchange,false);
});

test('ETA keeps trains until predicted departure, excludes past forecasts and respects stale data', () => {
    const data = [
        {stationId:'a',arrivalTime:900,departureTime:1200,vehicleId:'stopped',realtime:true},
        {stationId:'a',arrivalTime:800,departureTime:950,vehicleId:'gone'},
        {stationId:'a',arrivalTime:61000,vehicleId:'future',realtime:false},
        {stationId:'other',arrivalTime:2000}
    ];
    const arrivals = upcomingArrivals(data,'a',1000,false);
    assert.equal(arrivals.length,2);
    assert.equal(arrivals[0].atStation,true);
    assert.equal(arrivals[1].minutes,1);
    assert.equal(arrivals[1].realtime,false);
    assert.equal(upcomingArrivals(data,'a',1000,true).length,0);
});

test('ETA freshness is independent from new vehicle timestamps and prediction errors clear immediately', () => {
    const state = {arrivalsTimestamp:-Infinity,arrivalsFreshAt:-Infinity,arrivals:[]};
    Overlay.prototype.acceptArrivals.call(state,{timestamp:1000,arrivalsTimestamp:800,arrivals:[{stationId:'a'}]},100);
    Overlay.prototype.acceptArrivals.call(state,{timestamp:8000,arrivalsTimestamp:800,arrivals:[{stationId:'a'}]},7100);
    assert.equal(state.arrivalsFreshAt,-100);
    Overlay.prototype.acceptArrivals.call(state,{timestamp:8500,arrivalsTimestamp:0,arrivals:[]},7600);
    assert.equal(state.arrivalsFreshAt,-Infinity);
    assert.deepEqual(state.arrivals,[]);
});

test('publication change drops all visible objects, ETA, selection and blocks an old static response', async () => {
    let resolveOld;
    const state = {
        generation:1,disposed:false,publicationRevision:'old',staticVersion:1,now:()=>0,clearTransit:Overlay.prototype.clearTransit,
        json:()=>new Promise(resolve=>{resolveOld=resolve}),buffer:new SnapshotBuffer(),cars:new Map([['old',{}]]),
        trainGroup:{clear(){}},loadedChunks:new Map([['old',{}]]),chunks:new Map([['old',{}]]),
        loadedRouteChunks:new Map([['old',{}]]),routeChunks:new Map([['old',{}]]),stationData:[{id:'old'}],stationCatalog:new Map([['old',{id:'old',interchangeId:'interchange:old'}]]),
        removeRailChunk(){},clearTrainDots(){},clearStations(){},updateRouteLegend(){},clearDetail(){this.selection=null},leave(){},selection:'private',
        arrivals:[{stationId:'old'}],arrivalsFreshAt:100
    };
    const pending = Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    Overlay.prototype.acceptPublicationRevision.call(state,'new');
    resolveOld({schema:1,publicationRevision:'old',rails:[{points:[[0,0,0],[1,0,0]]}],routes:[],stations:[{id:'old',position:[0,0,0]}]});
    await pending;
    assert.equal(state.cars.size,0); assert.equal(state.chunks.size,0); assert.equal(state.routeChunks.size,0);
    assert.deepEqual(state.stationData,[]); assert.deepEqual(state.arrivals,[]); assert.equal(state.selection,null);
    assert.equal(state.stationCatalog.size,0);
    assert.equal(state.publicationRevision,'new'); assert.equal(state.lastRailsAt,-Infinity);
});

test('static response from another publication is rejected even if the map generation matches', async () => {
    const state = {generation:1,disposed:false,staticVersion:1,publicationRevision:'new',lastRailsAt:-Infinity,json:async()=>({schema:1,publicationRevision:'old',rails:[]})};
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.equal(state.lastRailsAt,-Infinity);
    assert.equal(state.chunks,undefined);
});

test('unchanged infrastructure refreshes preserve loaded meshes and markers while geometry and labels still update', async () => {
    let now = 1000, clears = 0, legends = 0;
    const data = {schema:1,publicationRevision:'current',timestamp:1,rails:[{points:[[0,0,0],[8,0,0]]}],routes:[{id:'r',name:'Line',paths:[[[0,0,0],[8,0,0]]]}],stations:[{id:'s',name:'Station',position:[0,0,0]}]};
    const state = {
        generation:1,disposed:false,staticVersion:1,publicationRevision:'current',now:()=>now,
        json:async()=>structuredClone(data),loadedChunks:new Map(),loadedRouteChunks:new Map(),
        removeRailChunk(){throw new Error('Unchanged meshes must not be disposed')},
        clearStations(){clears++},updateRouteLegend(){legends++}
    };
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    const chunks = state.chunks, routes = state.routeChunks, stations = state.stationData;
    state.loadedChunks.set('mesh',{}); state.loadedRouteChunks.set('route-mesh',{});
    now = 32000; data.timestamp++;
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.equal(state.chunks,chunks); assert.equal(state.routeChunks,routes); assert.equal(state.stationData,stations);
    assert.equal(state.loadedChunks.size,1); assert.equal(state.loadedRouteChunks.size,1);
    assert.equal(clears,1); assert.equal(legends,1); assert.equal(state.lastRailsAt,now);
    assert.equal(state.railRequestGeneration,null);
    let disposed = 0; state.removeRailChunk=()=>disposed++;
    now = 63000; data.timestamp++; data.stations[0].name='Renamed station';
    data.rails[0].points[1][0]=16;
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.notEqual(state.chunks,chunks); assert.equal(state.stationData[0].name,'Renamed station');
    assert.equal(disposed,2); assert.equal(clears,2); assert.equal(legends,2);
    assert.equal(state.lastRailsAt,now);
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

test('operator and interchange changes refresh station selection and remove vanished peers', async () => {
    const a={id:'a',name:'수인역',position:[0,0,0],routes:['r'],interchangeId:'suin',transferStationIds:['b']};
    const b={id:'b',name:'수인역',position:[4,0,0],routes:['r'],interchangeId:'suin',transferStationIds:['a']};
    const data={schema:1,publicationRevision:'current',rails:[],routes:[{id:'r',operators:[{id:'company',name:'이전 운영사'}]}],stations:[a,b]};
    let refreshed=0, cleared=0;
    const state={
        generation:1,disposed:false,staticVersion:1,publicationRevision:'current',now:()=>1000,json:async()=>structuredClone(data),
        loadedChunks:new Map(),loadedRouteChunks:new Map(),removeRailChunk(){},clearStations(){},updateRouteLegend(){},selectedStation:a,
        showStationDetail(station){this.selectedStation=station;refreshed++},clearDetail(){this.selectedStation=null;cleared++}
    };
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.equal(refreshed,1); assert.equal(state.stationCatalog.size,2);
    data.routes[0].operators[0].name='새 운영사'; data.stations=[{...a,transferStationIds:[]}];
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.equal(refreshed,2); assert.equal(state.stationCatalog.has('b'),false);
    assert.deepEqual(state.selectedStation.transferStationIds,[]);
    assert.equal(state.routeCatalog.get('r').operators[0].name,'새 운영사');
    data.stations=[];
    await Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    assert.equal(cleared,1); assert.equal(state.selectedStation,null);
});

// A strict DOM double catches accidental HTML parsing without adding browser/test dependencies to Gradle.
class ElementDouble {
    constructor(tag) { this.tagName=tag;this.children=[];this.attributes=new Map();this.listeners=new Map();this._text='';this.className='';this.style={setProperty(name,value){this[name]=value}};this.classList={toggle:(name,on)=>{const classes=new Set(this.className.split(' ').filter(Boolean));if(on)classes.add(name);else classes.delete(name);this.className=[...classes].join(' ')}}; }
    set innerHTML(_value) { throw new Error('Untrusted data must never be parsed as HTML'); }
    set textContent(value) { this._text=String(value);this.children=[]; }
    get textContent() { return this._text+this.children.map(child=>child.textContent).join(''); }
    append(...children) { for(const child of children){this.children.push(child);child.parentNode=this} }
    appendChild(child) { this.append(child);return child; }
    replaceChildren(...children) { this._text='';this.children=[];this.append(...children); }
    setAttribute(key,value) { this.attributes.set(key,String(value)); }
    getAttribute(key) { return this.attributes.get(key); }
    addEventListener(type,callback) { this.listeners.set(type,callback); }
    removeEventListener(type) { this.listeners.delete(type); }
    remove() { this.removed=true; }
}

function withDocument(callback) {
    const previous=global.document;
    global.document={createElement:tag=>new ElementDouble(tag),createElementNS:(_namespace,tag)=>new ElementDouble(tag),createTextNode:text=>{const node=new ElementDouble('#text');node.textContent=text;return node}};
    try { callback(); } finally {global.document=previous;}
}

test('operator labels stay inert in legend and cards, and train/ETA use the source operator', () => withDocument(() => {
    const company={id:'red-company',name:'<img src=x onerror=alert(1)>'}, other={id:'blue-company',name:'해안철도'};
    const routes=[{id:'group:joint',name:'공동 운행선',operators:[company,other]}];
    const overlay=Object.assign(Object.create(Overlay.prototype),{
        detail:new ElementDouble('section'),routeLegend:new ElementDouble('ul'),routeCatalog:publicRoutes(routes),
        now:()=>1000,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,
        arrivals:[{stationId:'a',routeId:'group:joint',arrivalTime:220000,operatorId:company.id,operatorName:company.name}]
    });
    overlay.updateRouteLegend(routes);
    assert.ok(overlay.routeLegend.textContent.includes(company.name));
    assert.ok(overlay.routeLegend.textContent.includes(other.name));
    overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:joint'],services:[{id:'local',routeId:'group:joint',operators:[company],showName:false}]});
    assert.ok(overlay.detail.textContent.includes(company.name));
    assert.ok(!overlay.detail.textContent.includes(other.name));
    assert.ok(overlay.arrivalsElement.textContent.includes(company.name));
    const previous=overlay.arrivalsSignature;
    overlay.arrivals[0]={...overlay.arrivals[0],operatorId:other.id,operatorName:other.name};
    overlay.updateArrivals();
    assert.notEqual(overlay.arrivalsSignature,previous);
    assert.ok(!overlay.arrivalsElement.textContent.includes(company.name));
    assert.ok(overlay.arrivalsElement.textContent.includes(other.name));
    const car=normalizeSnapshot(snapshot(1,[vehicle(0,0,'known',{routeId:'group:joint',routeName:'공동 운행선',operatorId:company.id,operatorName:company.name})])).cars.get('fleet-1:0');
    overlay.showTrainDetail(car);
    assert.ok(overlay.detail.textContent.includes(company.name));
    assert.ok(!overlay.detail.textContent.includes(other.name));
    overlay.showTrainDetail({...car,operatorId:'',operatorName:''});
    assert.ok(!overlay.detail.textContent.includes(company.name));
    assert.ok(!overlay.detail.textContent.includes(other.name));
    overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:joint'],services:[{id:'unassigned',routeId:'group:joint',operators:[]}]});
    const chips=overlay.detail.children.find(child=>child.className==='mtr-chips');
    assert.ok(!chips.textContent.includes(company.name));
    assert.ok(!chips.textContent.includes(other.name));
}));

test('indirect interchange cards separate each physical station and only change ETA after selection', () => withDocument(() => {
    const routes=[{id:'red',name:'중앙선',operators:[{id:'red-op',name:'중앙철도'}]},{id:'blue',name:'해안선',operators:[{id:'blue-op',name:'해안철도'}]}];
    const a={id:'a',name:'수인역',routes:['red'],services:[{id:'red-service',routeId:'red',operators:routes[0].operators}],interchangeId:'interchange:suin',interchangeName:'수인 <환승>',transferStationIds:['b','private']};
    const b={id:'b',name:'수인역',routes:['blue'],services:[{id:'blue-service',routeId:'blue',operators:routes[1].operators}],interchangeId:'interchange:suin',interchangeName:'수인 <환승>',transferStationIds:['a']};
    const overlay=Object.assign(Object.create(Overlay.prototype),{
        detail:new ElementDouble('section'),routeCatalog:publicRoutes(routes),stationCatalog:new Map([['a',a],['b',b]]),
        now:()=>1000,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,
        arrivals:[{stationId:'a',routeId:'red',arrivalTime:220000,destination:'중앙 종점',vehicleId:'a-train'},
            {stationId:'b',routeId:'blue',arrivalTime:160000,destination:'해안 종점',vehicleId:'b-train'}]
    });
    overlay.showStationDetail(a);
    assert.ok(overlay.detail.textContent.includes('간접환승'));
    assert.ok(overlay.detail.textContent.includes('수인 <환승>'));
    const directChips=overlay.detail.children.find(child=>child.className==='mtr-chips');
    assert.ok(directChips.textContent.includes('중앙선')); assert.ok(!directChips.textContent.includes('해안선'));
    assert.ok(overlay.arrivalsElement.textContent.includes('중앙 종점')); assert.ok(!overlay.arrivalsElement.textContent.includes('해안 종점'));
    const section=overlay.detail.children.find(child=>child.className==='mtr-interchanges');
    const buttons=section.children.filter(child=>child.className==='mtr-interchange-station');
    assert.equal(buttons.length,1); assert.ok(buttons[0].textContent.includes('해안철도'));
    assert.ok(buttons[0].getAttribute('aria-label').includes('해안선'));
    buttons[0].listeners.get('click')();
    assert.equal(overlay.selectedStation.id,'b');
    assert.ok(overlay.arrivalsElement.textContent.includes('해안 종점')); assert.ok(!overlay.arrivalsElement.textContent.includes('중앙 종점'));
    overlay.stationCatalog.delete('a'); overlay.showStationDetail(b);
    assert.ok(!overlay.detail.textContent.includes('간접환승'));
    assert.equal(overlay.detail.children[0].children[0].textContent,'철도역');
}));

test('configured transfer marker includes partner lines without requiring different route IDs', () => withDocument(() => {
    const previous=global.BlueMap;
    class GroupDouble { constructor(){this.userData={};this.position={fromArray(){}};this.children=[]} add(child){this.children.push(child)} }
    class MarkerDouble extends GroupDouble { constructor(){super();this.element=new ElementDouble('div')} }
    global.BlueMap={HtmlMarker:MarkerDouble};
    try {
        const a={id:'a',name:'수인역',position:[0,0,0],routes:['red'],interchangeId:'suin',transferStationIds:['b']};
        const b={id:'b',name:'수인역',position:[4,0,0],routes:['red'],interchangeId:'suin',transferStationIds:['a']};
        const overlay=Object.assign(Object.create(Overlay.prototype),{
            THREE:{Group:GroupDouble,Mesh:GroupDouble},stationsGroup:new GroupDouble(),stations:new Map(),material(){},
            routeCatalog:publicRoutes([{id:'red',name:'중앙선'}]),stationCatalog:new Map([['a',a],['b',b]])
        });
        assert.ok(overlay.createStation(a).userData.labelElement.className.includes('mtr-station--transfer'));
        const unconfigured={...a,interchangeId:undefined};
        assert.ok(!overlay.createStation(unconfigured).userData.labelElement.className.includes('mtr-station--transfer'));
        overlay.stationCatalog.delete('b');
        assert.ok(!overlay.createStation(a).userData.labelElement.className.includes('mtr-station--transfer'));
    } finally {global.BlueMap=previous;}
}));

test('hidden service names disappear from legend, station and train cards without hiding their route or arrival', () => withDocument(() => {
    const services=[
        {id:'service:central:local',name:'<완행>',routeId:'group:central'},
        {id:'service:central:local',name:'<완행>',routeId:'group:central'},
        {id:'service:central:express',name:'숨긴 급행',routeId:'group:central',showName:false}
    ];
    const routes=[{id:'group:central',name:'중앙선',color:'#123456',stations:['a'],services}];
    const overlay=Object.assign(Object.create(Overlay.prototype),{
        detail:new ElementDouble('section'),routeLegend:new ElementDouble('ul'),routeCatalog:publicRoutes(routes),
        now:()=>1000,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,
        arrivals:[{stationId:'a',routeId:'group:central',serviceId:'service:central:express',serviceName:'숨긴 급행',showServiceName:false,arrivalTime:220000,destination:'강변역',vehicleId:'train'}]
    });
    overlay.updateRouteLegend(routes);
    assert.ok(overlay.routeLegend.textContent.includes('중앙선'));
    assert.equal(overlay.routeLegend.textContent.split('<완행>').length-1,1);
    assert.ok(!overlay.routeLegend.textContent.includes('숨긴 급행'));
    overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:central'],services});
    assert.equal(overlay.detail.textContent.split('<완행>').length-1,1);
    assert.ok(!overlay.detail.textContent.includes('숨긴 급행'));
    assert.ok(overlay.detail.textContent.includes('강변역 방면'));
    assert.ok(overlay.detail.textContent.includes('2분'));
    const car=normalizeSnapshot(snapshot(1,[vehicle(0,0,'known',{routeId:'group:central',routeName:'중앙선',serviceId:'service:central:express',serviceName:'숨긴 급행',showServiceName:false})])).cars.get('fleet-1:0');
    overlay.showTrainDetail(car);
    assert.ok(overlay.detail.textContent.includes('중앙선'));
    assert.ok(!overlay.detail.textContent.includes('known')); // uncatalogued model IDs stay internal
    assert.ok(overlay.detail.textContent.includes('1량 편성'));
    assert.ok(!overlay.detail.textContent.includes('숨긴 급행'));
    overlay.showTrainDetail({...car,serviceName:'호환 완행',showServiceName:undefined});
    assert.ok(overlay.detail.textContent.includes('호환 완행'));
    const hiddenServices=services.map(service=>({...service,showName:false}));
    overlay.updateRouteLegend([{...routes[0],services:hiddenServices}]);
    assert.ok(overlay.routeLegend.textContent.includes('중앙선'));
    assert.ok(!overlay.routeLegend.textContent.includes('<완행>'));
    assert.ok(!overlay.routeLegend.children[0].children[1].children.some(element=>element.className==='mtr-services'));
    overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:central'],services:hiddenServices});
    assert.ok(!overlay.detail.children.some(element=>element.className==='mtr-services'));
}));

test('arrival name visibility changes invalidate rendered text while keeping the same train and ETA', () => withDocument(() => {
    const arrival={stationId:'a',routeId:'group:central',serviceId:'service:central:express',serviceName:'특급',arrivalTime:220000,destination:'강변역',vehicleId:'train'};
    const overlay=Object.assign(Object.create(Overlay.prototype),{
        detail:new ElementDouble('section'),routeCatalog:publicRoutes([{id:'group:central',name:'중앙선'}]),
        now:()=>1000,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,arrivals:[arrival]
    });
    overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:central']});
    assert.ok(overlay.arrivalsElement.textContent.includes('특급'));
    const before=overlay.arrivalsSignature;
    overlay.arrivals=[{...arrival,showServiceName:false}];overlay.updateArrivals();
    assert.notEqual(overlay.arrivalsSignature,before);
    assert.ok(!overlay.arrivalsElement.textContent.includes('특급'));
    assert.ok(overlay.arrivalsElement.textContent.includes('중앙선'));
    assert.ok(overlay.arrivalsElement.textContent.includes('2분'));
    overlay.arrivals=[{...arrival,showServiceName:true}];overlay.updateArrivals();
    assert.ok(overlay.arrivalsElement.textContent.includes('특급'));
}));

test('floating card toggles accessibly, has no vehicle select, and grouped names remain inert text', () => {
    const previous=global.document,created=[];
    global.document={head:new ElementDouble('head'),createElement:tag=>{const element=new ElementDouble(tag);created.push(element);return element},createElementNS:(_namespace,tag)=>new ElementDouble(tag),createTextNode:text=>{const node=new ElementDouble('#text');node.textContent=text;return node}};
    try {
        const overlay=Object.assign(Object.create(Overlay.prototype),{viewer:{rootElement:new ElementDouble('main'),renderer:{domElement:new ElementDouble('canvas')},redraw(){}},showTrains:true,showRoutes:true,showStations:true,showRails:true});
        overlay.createUi();
        assert.equal(overlay.panelToggle.getAttribute('aria-expanded'),'true');
        overlay.panelToggle.listeners.get('click')();
        assert.equal(overlay.panelContent.hidden,true);
        assert.equal(overlay.panelToggle.getAttribute('aria-expanded'),'false');
        overlay.layerButtons.get('showRails').listeners.get('click')();
        assert.equal(overlay.showRails,false);
        assert.equal(overlay.layerButtons.get('showRails').getAttribute('aria-pressed'),'false');
        const routes=[{id:'group:x',name:'<img src=x onerror=alert(1)>',color:'#123456',stations:['a'],services:[{id:'local',name:'일반'},{id:'express',name:'급행'}]},{id:'group:x',name:'duplicate'}];
        overlay.updateRouteLegend(routes);
        assert.equal(overlay.routeLegend.children.length,1);
        assert.ok(overlay.routeLegend.textContent.includes('<img src=x onerror=alert(1)>'));
        assert.ok(overlay.routeLegend.textContent.includes('일반급행'));
        assert.ok(!created.some(element=>['select','option','img'].includes(element.tagName)));
        overlay.updateRouteLegend([]);
        assert.ok(overlay.routeLegend.textContent.includes('공개된 노선이 없습니다'));
    } finally {global.document=previous;}
});

test('selected station ETA updates independently and clears its old forecast on failure and selection reset', () => {
    const previous=global.document;
    global.document={createElement:tag=>new ElementDouble(tag),createElementNS:(_namespace,tag)=>new ElementDouble(tag),createTextNode:text=>{const node=new ElementDouble('#text');node.textContent=text;return node}};
    try {
        let now=1000;
        const overlay=Object.assign(Object.create(Overlay.prototype),{detail:new ElementDouble('section'),routeCatalog:publicRoutes([{id:'group:x',name:'중앙선',color:'#123456',services:[{id:'local',name:'일반'}]}]),now:()=>now,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,arrivalsTimestamp:100000,arrivals:[{stationId:'a',routeId:'group:x',arrivalTime:220000,serviceName:'급행',destination:'<강변역>',vehicleId:'train'}]});
        overlay.showStationDetail({id:'a',name:'중앙역',routes:['group:x','group:x'],services:[{id:'local',name:'일반',routeId:'group:x'},{id:'local',name:'일반',routeId:'group:x'}]});
        assert.ok(overlay.detail.textContent.includes('2분'));
        assert.ok(overlay.detail.textContent.includes('<강변역> 방면'));
        now=8500; overlay.updateArrivals();
        assert.ok(overlay.arrivalsElement.textContent.includes('도착 정보를 갱신하지 못했습니다'));
        assert.ok(!overlay.arrivalsElement.textContent.includes('<강변역>'));
        overlay.acceptArrivals({timestamp:107500,arrivalsTimestamp:107500,arrivals:[]},now);overlay.updateArrivals();
        assert.ok(overlay.arrivalsElement.textContent.includes('도착 정보가 없습니다'));
        overlay.clearDetail();assert.equal(overlay.detail.hidden,true);assert.equal(overlay.selectedStation,null);assert.equal(overlay.detail.children.length,0);
    } finally {global.document=previous;}
});

test('map UI mode shows stations, routes and trains in the BlueMap place panel', () => withDocument(() => {
    const opened=[], layers=[], chips=[], search=new Map(), resolvers=new Map();
    let closed=0;
    const ui={state:{selectedKey:null},
        registerLayer(layer){layers.push(layer);return ()=>{}},registerChip(chip){chips.push(chip);return ()=>{}},
        registerSearchProvider(id,provider){search.set(id,provider);return ()=>{}},registerResolver(prefix,resolver){resolvers.set(prefix,resolver);return ()=>{}},
        openPlace(place){opened.push(place);ui.state.selectedKey=place.key},openList(list){ui.list=list},closePanel(){closed++;ui.state.selectedKey=null},
        setStatus(){},refresh(){},flyTo(position){ui.flown=position},formatBlocks:n=>`${Math.round(n)}블록`,formatCoordinates:p=>`${p.x},${p.z}`,positionRows:()=>[],addressAt:()=>null};
    const routes=[{id:'group:a',name:'중앙선',color:'#317fce',stations:['a','b'],services:[{id:'s',name:'급행'}],operators:[{id:'o',name:'중앙철도'}]},{id:'group:b',name:'해안선',color:'#3ba879',stations:['a']}];
    const a={id:'a',name:'중앙역',position:[0,64,0],routes:['group:a','group:b'],services:[{id:'s',name:'급행',routeId:'group:a',operators:[{id:'o',name:'중앙철도'}]}]}, b={id:'b',name:'강변역',position:[100,64,0],routes:['group:a']};
    const overlay=Object.assign(Object.create(Overlay.prototype),{
        viewer:{rootElement:new ElementDouble('main'),renderer:{domElement:new ElementDouble('canvas')},controlsManager:{position:{x:0,z:0},distance:500},redraw(){}},
        showTrains:true,showRoutes:true,showStations:true,showRails:true,uiDisposers:[],lastPoses:new Map(),
        routeCatalog:publicRoutes(routes),stationData:[a,b],stationCatalog:new Map([['a',a],['b',b]]),catalog:{schema:1,vehicles:{}},
        now:()=>1000,buffer:{offset:-99000,isStale:()=>false},arrivalsFreshAt:1000,
        arrivals:[{stationId:'a',routeId:'group:a',arrivalTime:220000,destination:'강변역',vehicleId:'t'}]
    });
    const find=(element,className)=>element.className===className?element:element.children.map(child=>find(child,className)).find(Boolean);
    assert.equal(overlay.attachMapUi(ui),true);
    assert.equal(layers.map(layer=>layer.id).join(),'mtr-showTrains,mtr-showRoutes,mtr-showStations,mtr-showRails');
    layers[3].set(false);
    assert.equal(overlay.showRails,false);
    assert.equal(chips.map(chip=>chip.id).join(),'mtr-stations,mtr-routes');
    assert.equal(chips[0].visible(),true);
    assert.equal(overlay.uiDisposers.length,9);
    const results=search.get('mtr')('해안');
    assert.ok(results.some(result=>result.title==='중앙역'));
    assert.ok(results.some(result=>result.title==='해안선'));

    overlay.showStationDetail(a);
    const station=opened.pop();
    assert.equal(station.key,'mtr-station:a');
    assert.equal(station.kind,'환승역');
    assert.equal(station.badges.map(badge=>badge.label).join(),'중앙선,해안선');
    assert.ok(!overlay.selectedStation,'state activates only when the panel mounts the content');
    const mount=new ElementDouble('div'), cleanup=station.mount(mount);
    assert.equal(overlay.selectedStation,a);
    for (const text of ['곧 도착하는 열차','강변역 방면','2분','운행 노선','중앙철도','급행']) assert.ok(mount.textContent.includes(text),text);

    find(mount,'mtr-place-route').listeners.get('click')();
    const route=opened.pop();
    assert.equal(route.key,'mtr-route:group:a');
    assert.equal(overlay.selectedStation,a,'opening another place leaves cleanup to the unmounted view');
    const diagram=new ElementDouble('div');
    route.mount(diagram);
    assert.ok(diagram.textContent.includes('중앙역해안선'));
    assert.ok(diagram.textContent.includes('강변역'));
    cleanup();
    assert.equal(overlay.selectedStation,null);
    station.mount(new ElementDouble('div'));
    assert.equal(overlay.selectedStation,a,'navigating back re-activates live arrivals');

    ui.state.selectedKey='mtr-station:a';
    overlay.clearDetail();
    assert.equal(closed,1);
    assert.equal(resolvers.get('mtr-station')('mtr-station:b'),true);
    assert.equal(opened.pop().title,'강변역');
    assert.equal(resolvers.get('mtr-station')('mtr-station:missing'),false);
    assert.equal(resolvers.get('mtr-route')('mtr-route:group:b'),true);

    overlay.lastPoses=new Map([['t:0',{vehicleId:'t',index:0,position:[5,64,6],routeId:'group:a',routeName:'중앙선',operatorName:'중앙철도',color:'#317fce'}]]);
    overlay.showTrainDetail({vehicleId:'t',index:0,position:[1,64,1],routeId:'group:a',routeName:'중앙선',serviceName:'급행',carCount:4,color:'#317fce',id:'model'});
    const train=opened.pop();
    assert.equal(train.key,'mtr-train:t');
    assert.equal(train.defaultActions,false);
    assert.deepEqual(train.position(),{x:5,y:64,z:6});
    assert.ok(train.rows().some(row=>row.text==='중앙철도'));
    train.actions[0].onClick();
    assert.deepEqual(ui.flown,{x:5,y:64,z:6});
}));

test('train labels name the line and service without internal vehicle or model IDs', () => {
    const car = { id: 'sp1900_cab_1', vehicleId: '8658071617266451595', index: 2, routeName: '해안선', serviceName: '급행', showServiceName: true, carCount: 6 };
    assert.equal(trainLabel(car), '해안선 · 급행 · 6량 편성');
    assert.equal(trainLabel({ ...car, showServiceName: false }), '해안선 · 6량 편성');
    assert.equal(trainLabel({ ...car, serviceName: '해안선' }), '해안선 · 6량 편성');
    assert.equal(trainLabel({ id: 'x', routeName: '', carCount: 0 }), '운행 열차');
    assert.equal(modelName({ vehicles: {} }, car), '');
    assert.equal(modelName({ vehicles: { sp1900_cab_1: { name: 'SP1900 Cab' } } }, car), 'SP1900 Cab');
});
