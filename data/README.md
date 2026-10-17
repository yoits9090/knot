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
