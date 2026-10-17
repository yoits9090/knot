# Bloor–Yonge cached geometry

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
available under the [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
The scene contains an attribution link. This directory's OSM data retains its data
licence; the application code is separate.

`bloor-yonge.osm` is a bounded, filtered extract downloaded from the public OSM API
on 2026-10-17. `bloor-yonge-source.json` records its source URL, SHA-256, projection
origin and height assumptions. The application loads the cache without network
requests. `python3 scripts/cache-bloor-yonge.py` explicitly refreshes it.

The local origin is the shared Bloor/Yonge intersection node 20964462 at
43.6702351 N, 79.3867611 W. The 460 × 460 m scene uses local east/south metres.
Polygons are clipped to its bounds. Building parts preserve setbacks and raised
structures; multipolygon holes are retained. Explicit low podiums remain below
parts, while overlapping full-height tower outlines are omitted.

The supplied cache produces 336 building/part volumes: 184 use `height`, 133 use
`building:levels × 3 m` (plus tagged roof height), and 19 use an explicit 12 m
fallback. These numbers refer to volumes/parts, not distinct addresses. Heights
inferred from floor counts and default heights are disclosed in the HUD. Tags,
including construction geometry, describe the OSM snapshot; construction state,
building accuracy and present-day heights have not been independently surveyed.

Ground is a flat plane, not real Toronto elevation data. Street centrelines come
from the extract. Buildings are solid voxel obstacles in both fluid and A* grids.
The nominal 7.19 m fluid cells cannot resolve narrow alleys or fine facade details.
The city wind is a prescribed 12 m/s incoming flow with a random direction per
reset, not a live weather feed. Numerical flow is for visual exploration, not a
validated assessment of pedestrian wind safety.

## Citywide Toronto atlas and weather

The default citywide atlas in `toronto/` supersedes the small OSM scene described
above. It uses official [City of Toronto 3D massing](https://open.toronto.ca/dataset/3d-massing/)
(2025 release) and [Toronto Centreline](https://open.toronto.ca/dataset/toronto-centreline-tcl/).
Contains information licensed under the
[Open Government Licence – Toronto](https://open.toronto.ca/open-data-license/).
`toronto/source.json` records exact download URLs, input SHA-256 hashes, counts,
bounds and assumptions. The source shapefile is Web Mercator despite its WGS84
filename; the builder reads and converts its declared CRS to local metres.

All 428,184 building footprints are retained, with City `AVG_HEIGHT` as extrusion
height (no fallback heights were needed in this release). Nearby tiles retain
footprint courtyards. Distant overview heights use tall-building bounding volumes;
all footprints and selected streets remain visible in the overview texture.
Ground is flat. Shoreline geometry is from the same official centreline dataset;
endpoint gaps up to 200 m are bridged for the overview lake polygon. This is an
approximate display coastline, not a navigation chart. Street intersection IDs
and names come from centreline endpoints with three or more incident segments
and at least two named roads. Air routing uses 10 m spacing with 1 m swept segment clearance; CFD uses a
separate 640 m window with 10 m cells and a 15 m AGL heatmap sampling height.

Weather comes from [ECCC Hourly Climate Observations](https://api.weather.gc.ca/collections/climate-hourly),
Toronto City Centre (climate identifier 6158667). The raw GeoJSON response, source
URL and fetch time are in `weather/eccc-city-centre.json`. Valid records (including
calm records but excluding nonempty quality flags and missing noncalm directions)
are normalized in `weather/observations.csv`. `backtest.json` contains the exact
time range, chronological split, fitted coefficients and aggregate results;
`backtest-predictions.csv` contains every held-out prediction and observation.
Use `scripts/cache-weather.py --offline` to reproduce these results without a new
download. ECCC data is subject to the
[Government of Canada's open-data terms](https://climate.weather.gc.ca/prods_servs/attachment1_e.html).

The app fetches the latest available valid station observation at startup and at
15-minute intervals, with a 25-second request deadline; it retains dated cached
wind on failure. The displayed observation time is never substituted with the
fetch time. R replays older actual records, and L returns to the latest available
record. Aged observations are labelled delayed. No local ground or altitude wind
measurements are implied. The backtest evaluates one-hour station input forecasts;
it does not validate the CFD around buildings.
