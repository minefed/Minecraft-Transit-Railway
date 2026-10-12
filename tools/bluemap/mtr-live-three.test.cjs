/* Optional integration test: requires npm install in sibling BlueMap/BlueMapCommon/webapp. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const THREE = require(path.resolve(__dirname, '../../../BlueMap/BlueMapCommon/webapp/node_modules/three/build/three.cjs'));
const { Overlay, railChunks, routeChunks } = require('../../fabric/src/main/resources/assets/mtr/bluemap/mtr-live.js');

test('real Three r147 renderer builds dimensioned cars, sloped instances, route colors and cleans map state', () => {
    let now = 1000;
    const dom = { addEventListener(){}, removeEventListener(){}, getBoundingClientRect:()=>({width:1000,height:800}) };
    const viewer = { markers:new THREE.Scene(), map:{data:{id:'a'}}, data:{mapState:'loaded',loadedHiresViewDistance:200},
        controlsManager:{position:new THREE.Vector3(),distance:300}, camera:new THREE.PerspectiveCamera(), renderer:{domElement:dom}, redraw(){} };
    const overlay = new Overlay({ mapViewer:viewer, events:new EventTarget() }, THREE, {ui:false,poll:false,base:'http://localhost/',fetch:async()=>{},now:()=>now});
    overlay.entry = {};
    overlay.buffer.push({schema:1,timestamp:1,vehicles:[{id:'train',routeName:'Blue',color:'#1244ff',cars:[{id:'unknown',position:[0,0,0],yaw:Math.PI/2,pitch:-0.2,length:30,width:4}]}]},now);
    overlay.chunks = railChunks({schema:1,rails:[{points:[[0,0,0],[30,5,0]]}]});
    overlay.routeChunks = routeChunks({routes:[{id:'r',color:'#1244ff',paths:[[[0,0,0],[30,5,0]]]}]});
    overlay.render();
    assert.equal(overlay.cars.size,1);
    const car = overlay.cars.get('train:0');
    assert.equal(car.children[0].scale.x,4);
    assert.ok(Math.abs(car.children[0].scale.z - 28.8) < 1e-9);
    assert.equal(car.children[0].material.color.getHexString(),'1244ff');
    assert.equal(car.rotation.order,'YXZ');
    assert.equal(overlay.loadedChunks.size,1);
    const railMesh = [...overlay.loadedChunks.values()][0].children[0];
    assert.equal(railMesh.isInstancedMesh,true);
    assert.equal(railMesh.material.depthTest,true);
    assert.equal([...overlay.loadedRouteChunks.values()][0].children[0].material.depthTest,false);
    now += 7000;
    overlay.render();
    assert.equal(overlay.trainGroup.visible,false);
    assert.equal(overlay.cars.size,0);
    viewer.map = {data:{id:'b'}};
    overlay.detail = {textContent:'Previous world station',replaceChildren(){this.textContent=''},remove(){}};
    overlay.switchMap();
    assert.equal(overlay.detail.textContent,'');
    assert.equal(overlay.detail.hidden,true);
    assert.equal(overlay.loadedChunks.size,0);
    assert.equal(overlay.routesGroup.children.length,0);
    overlay.dispose();
    assert.equal(viewer.markers.children.length,0);
});

test('live trains keep interpolating and expire on idle frames without forcing full-rate terrain renders', () => {
    let now = 1000, redraws = 0;
    const viewer = { markers:new THREE.Scene(), map:{data:{id:'a'}}, data:{mapState:'loaded',loadedHiresViewDistance:200},
        controlsManager:{position:new THREE.Vector3(),distance:300},camera:new THREE.PerspectiveCamera(),
        renderer:{domElement:{addEventListener(){},removeEventListener(){},getBoundingClientRect:()=>({width:1000,height:800})}},redraw(){redraws++} };
    const overlay = new Overlay({mapViewer:viewer,events:new EventTarget()},THREE,{ui:false,poll:false,base:'http://localhost/',fetch:async()=>{},now:()=>now});
    overlay.entry={};
    const sample = (timestamp,x) => ({schema:1,timestamp,vehicles:[{id:'t',cars:[{id:'car',position:[x,0,0],yaw:0,pitch:0}]}]});
    overlay.buffer.push(sample(1000,0),1000);
    overlay.buffer.push(sample(1500,10),1500);
    now=1900; overlay.render();
    assert.equal(overlay.cars.get('t:0').position.x,5);
    now=2150; overlay.render();
    assert.equal(overlay.cars.get('t:0').position.x,10);
    viewer.controlsManager.position.x=10000; now+=50; overlay.render();
    assert.equal(overlay.cars.size,0);
    assert.equal(redraws,0);
    now=8000; overlay.render();
    assert.equal(overlay.trainGroup.visible,false);
    overlay.dispose();
});

test('distant train markers only rewrite changed labels and colors and still update positions', () => {
    let now=1000, labelWrites=0, colorWrites=0, removals=0;
    const viewer={markers:new THREE.Scene(),map:{data:{id:'a'}},data:{mapState:'loaded'},
        controlsManager:{position:new THREE.Vector3(),distance:2000},camera:new THREE.PerspectiveCamera(),
        renderer:{domElement:{addEventListener(){},removeEventListener(){},getBoundingClientRect:()=>({width:1000,height:800})}},redraw(){throw new Error('Idle train markers must not force high FPS')}};
    const overlay=new Overlay({mapViewer:viewer,events:new EventTarget()},THREE,{ui:false,poll:false,base:'http://localhost/',fetch:async()=>{},now:()=>now});
    overlay.entry={};
    overlay.createTrainDot=()=>{const dot=new THREE.Group();dot.userData.button={setAttribute(){labelWrites++},style:{setProperty(){colorWrites++}}};overlay.trainGroup.add(dot);return dot};
    overlay.disposeHtmlMarker=dot=>{dot.removeFromParent();removals++};
    const sample=(timestamp,x,name='Line',color='#ff0000')=>({schema:1,timestamp,vehicles:[{id:'t',routeName:name,color,cars:[{id:'car',position:[x,0,0],yaw:0,pitch:0}]}]});
    overlay.buffer.push(sample(1000,0),1000);overlay.render();
    now+=50;overlay.render();
    assert.equal(labelWrites,1);assert.equal(colorWrites,1);
    overlay.buffer.push(sample(1500,10),1500);now=2150;overlay.render();
    assert.equal(overlay.trainDots.get('t').position.x,10);
    assert.equal(labelWrites,1);assert.equal(colorWrites,1);
    overlay.buffer.push(sample(2000,20,'Renamed','#0000ff'),2000);now=2650;overlay.render();
    assert.equal(labelWrites,2);assert.equal(colorWrites,2);
    now=9000;overlay.render();assert.equal(overlay.trainDots.size,0);assert.equal(removals,1);
    overlay.dispose();
});
