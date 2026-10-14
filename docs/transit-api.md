# Public transit API

Minecraft 1.20.4 / Fabric / MTR 4.0.5 publishes the existing public transit
projection to `minefed-api`. BlueMap's web overlay reads that same public API.
Capture runs on the owning Core simulator queue even when BlueMap is absent or
disabled. No Minecraft client classes or new runtime dependencies are required.

## Configuration

Set up the API's optional transit HTTP listener using its
[API contract and deployment guide](https://github.com/minefed/minefed-api/blob/main/docs/TRANSIT_API.md).
Then configure the Minecraft server's `config/mtr/transit-api.json`:

```json
{
  "apiBaseUrl": "https://api.example.com/v1/transit/minefed",
  "publicApiBaseUrl": "https://api.example.com/v1/transit/minefed",
  "tokenEnvironment": "MINEFED_TRANSIT_TOKEN"
}
```

The example hostname is a placeholder. `apiBaseUrl` is the server-to-server
address; `publicApiBaseUrl` is the browser-visible address and defaults to the
former if empty. Both must have the same `/v1/transit/{dataset}` path. An internal
listener such as `http://127.0.0.1:8088/v1/transit/minefed` may be used for ingestion
with a different public HTTPS host. HTTP is accepted only for explicit loopback
addresses. Credentials, query strings, fragments and traversal paths in URLs are
rejected. Redirects are not followed.

Provide the API's `TRANSIT_SERVER_TOKEN` value through the Minecraft process
environment variable named by `tokenEnvironment`. The token must be 32–4096
printable ASCII characters without whitespace or commas. Never place the token
in JSON, the client pack, a public URL, BlueMap files or Git. Only the public API
base and the BlueMap-map-to-MTR-world mapping are written to the web root.

The generated default has empty URLs and disables API publication. Missing or
invalid credentials also disable publication. A server restart is required after
changing API configuration or environment variables. `mtr.bluemapLive.enabled`
continues to control only optional BlueMap integration; it does not disable API
publication. Existing public settings remain at:

- `config/mtr/bluemap-public-routes.json`: administrator-owned public selection;
  checked every 2 seconds, preserving routes, groups, services, operators and
  configured interchanges. Invalid settings publish nothing.
- `config/mtr/bluemap-routes.json`: automatically refreshed private route/station
  catalog. It is never sent to the API or written to the public web directory.
- `config/mtr/bluemap-vehicles.json`: optional public vehicle display names and
  dimensions. Only `name`, `length` and `width` are exported.

The MTR `hidden` flag and the explicit public selection remain separate: a hidden
route can still be explicitly selected, as before.

## Data flow and timing

1. Capture public vehicles every 500 ms, arrivals every 2 seconds, and
   infrastructure and optional vehicle metadata every 30 seconds.
2. A separate HTTP sender publishes `PUT {apiBaseUrl}/snapshot` using the private
   Bearer token. It retains at most one pending complete snapshot and one
   in-flight request. Slow or failed requests do not block simulation or policy
   checks. Headers and body completion are bounded to 2 seconds; failed attempts
   wait at least 500 ms. Infrastructure is sent after each infrastructure capture
   or when recovery requires a full snapshot.
3. The API validates the explicit public schema and serves anonymous GETs:
   `/worlds`, `/worlds/{id}/vehicles`, `/worlds/{id}/infrastructure`, `/catalog`.
   World IDs in paths are stable UUIDs derived from the MTR dimension string;
   `/worlds` includes the original `worldId`, e.g. `minecraft/overworld`.
4. BlueMap reads only local `mtr-live/index.json` (bootstrap schema 2) and the web
   script. All transit data comes from the configured API. Polling remains 500 ms,
   interpolation 650 ms, and static refresh 30 seconds. The browser uses
   credential-free requests and the API's CORS headers.

Original capture timestamps, arrivals timestamps and publication revisions are
preserved. The API uses a 6-second freshness lease and reports source age; the
browser includes that age and request delay when deciding whether data is live.
Synchronize the Minecraft and API host clocks; the API permits up to 5 seconds of
future clock skew. Optional metadata failures do not hide a healthy live world.

Policy changes discard old pending data and clear the API's previous revision
atomically. API restart/rejection triggers a complete resend. Shutdown attempts
to publish an empty world list; the freshness lease also expires data after an
unclean stop. No old local JSON fallback is used. On migration, the old world
files and vehicle catalog are emptied so cached older scripts cannot keep using
the old public feed.

## Validation and rollout

Java tests cover credential/URL validation, latest-only delivery, recovery after
API cache loss, redirect rejection, stalled response bodies and real Core public
snapshot generation. `TransitContractFixtureTest` writes
`fabric/build/transit-contract.json`; the API tests consume it through
`TRANSIT_CONTRACT_FIXTURE` to check the real producer and public response contract.
Existing policy, geometry, arrival and web/Three.js regression tests still apply.

Deploy the API listener and public HTTPS route before enabling the MTR
configuration and upgrading the MTR server JAR. Existing public-route settings
must be preserved. The migration needs the new Java publisher and schema-2 web
script together; replacing only the web script is insufficient. Code builds and
local tests do not constitute production deployment or live game validation.
