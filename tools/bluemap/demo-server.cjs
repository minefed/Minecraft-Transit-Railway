/* Local synthetic visual QA. Uses the sibling BlueMap checkout's installed Three/Vue, never a CDN. */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const web = path.resolve(root, '../BlueMap/BlueMapCommon/webapp');
const files = new Map([
    ['/', path.join(__dirname, 'demo.html')],
    ['/mtr-live.js', path.join(root, 'fabric/src/main/resources/assets/mtr/bluemap/mtr-live.js')],
    ['/vendor/three.js', path.join(web, 'node_modules/three/build/three.module.js')],
    ['/vendor/vue.js', path.join(web, 'node_modules/vue/dist/vue.esm-browser.prod.js')],
    ['/vendor/OrbitControls.js', path.join(web, 'node_modules/three/examples/jsm/controls/OrbitControls.js')]
]);
const server = http.createServer((request, response) => {
    let pathname;
    try { pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname); }
    catch { response.writeHead(400).end(); return; }
    let file = files.get(pathname);
    if (!file && pathname.startsWith('/bluemap/')) {
        const base = path.join(web, 'src/js');
        const candidate = path.resolve(base, pathname.slice('/bluemap/'.length));
        if (candidate.startsWith(base + path.sep)) file = candidate.endsWith('.js') ? candidate : `${candidate}.js`;
    }
    if (!file || !fs.existsSync(file)) { response.writeHead(404).end('Not found'); return; }
    let content = fs.readFileSync(file, 'utf8');
    if (file.endsWith('.js')) content = content
        .replace(/from\s*(["'])three\1/g, 'from "/vendor/three.js"')
        .replace(/from\s*(["'])vue\1/g, 'from "/vendor/vue.js"')
        .replace(/from\s*(["'])(\.[^"']+)\1/g, (_match, quote, specifier) => `from ${quote}${specifier.endsWith('.js') ? specifier : `${specifier}.js`}${quote}`);
    response.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html; charset=utf-8' : 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(content);
});
const port = Number(process.argv[2] || 8776);
server.listen(port, '127.0.0.1', () => process.stdout.write(`Synthetic MTR / BlueMap QA: http://127.0.0.1:${port}/\n`));
