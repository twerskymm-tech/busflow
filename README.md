# BusFlow Tablet

The in-bus Android app for YS Transportation. It guides the driver turn by turn on the exact roads
the office approved for each route in BusFlow (Routes & Riders → Route Paths).

- **Install:** https://github.com/twerskymm-tech/busflow/releases/latest/download/busflow-tablet.apk
- **Connect a tablet:** BusFlow → Route Paths → Tablets → Add tablet, then type the code in the app.
- **Builds:** every push to `main` builds the app on GitHub and replaces the download above.

## How navigation works
- BusFlow sends the approved path (`tabletApi` backend function). The app cuts it into pieces and snaps
  each piece to Mapbox roads with Map Matching, so voice turn-by-turn follows exactly that line.
- Mapbox's own reroute is off. Off the path, the app guides the bus back to the nearest spot ahead
  on the planned path, then continues on it.
- No approved path for that day (or the stops changed since approval): plain directions through the stops.
- Stops are checked off within 40 m; a stop the bus drives past is marked skipped.
- **Practice** drives the route by itself, for trying it at a desk.

## Code
- `app/src/main/java/.../NavActivity.kt` — the navigation screen
- `Geo.kt` — path math, `Api.kt` — talks to BusFlow, `Models.kt` — data
- Hidden settings: long-press "Routes" on the route list (server address, disconnect).
