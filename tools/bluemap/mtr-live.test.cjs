/* Run with node --test tools/bluemap/mtr-live.test.cjs. No third-party dependencies. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { SnapshotBuffer, normalizeSnapshot, catalogEntry, publicRoutes, stationRoutes, uniqueServices, upcomingArrivals, railChunks, routeChunks, safeUrl, Overlay, STALE_MS, BUFFER_MS } = require('../../fabric/src/main/resources/assets/mtr/bluemap/mtr-live.js');

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
        acceptPublicationRevision(){}, acceptArrivals(){},
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
        acceptPublicationRevision(){}, acceptArrivals(){},
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
    assert.equal(state.arrivalsFreshAt,100);
    Overlay.prototype.acceptArrivals.call(state,{timestamp:8500,arrivalsTimestamp:0,arrivals:[]},7600);
    assert.equal(state.arrivalsFreshAt,-Infinity);
    assert.deepEqual(state.arrivals,[]);
});

test('publication change drops all visible objects, ETA, selection and blocks an old static response', async () => {
    let resolveOld;
    const state = {
        generation:1,disposed:false,publicationRevision:'old',staticVersion:1,now:()=>0,
        json:()=>new Promise(resolve=>{resolveOld=resolve}),buffer:new SnapshotBuffer(),cars:new Map([['old',{}]]),
        trainGroup:{clear(){}},loadedChunks:new Map([['old',{}]]),chunks:new Map([['old',{}]]),
        loadedRouteChunks:new Map([['old',{}]]),routeChunks:new Map([['old',{}]]),stationData:[{id:'old'}],
        removeRailChunk(){},clearTrainDots(){},clearStations(){},updateRouteLegend(){},clearDetail(){this.selection=null},leave(){},selection:'private',
        arrivals:[{stationId:'old'}],arrivalsFreshAt:100
    };
    const pending = Overlay.prototype.refreshRails.call(state,1,'mtr-live/rails.json');
    Overlay.prototype.acceptPublicationRevision.call(state,'new');
    resolveOld({schema:1,publicationRevision:'old',rails:[{points:[[0,0,0],[1,0,0]]}],routes:[],stations:[{id:'old',position:[0,0,0]}]});
    await pending;
    assert.equal(state.cars.size,0); assert.equal(state.chunks.size,0); assert.equal(state.routeChunks.size,0);
    assert.deepEqual(state.stationData,[]); assert.deepEqual(state.arrivals,[]); assert.equal(state.selection,null);
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
    assert.ok(overlay.detail.textContent.includes('known'));
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
