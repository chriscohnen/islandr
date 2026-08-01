# world-land-110m.json

Land-mass outlines (no political borders — deliberate, see ADR-0021's R-169) for
the dashboard world-map topology view. Derived from
[world-atlas@2](https://github.com/topojson/world-atlas) (`countries-110m.json`,
`objects.land`), itself built from [Natural Earth](https://www.naturalearthdata.com/)
1:110m data (public domain). world-atlas is ISC-licensed (Copyright 2013-2019
Michael Bostock) — permissive, redistribution permitted.

Converted from TopoJSON (quantized, delta-encoded arcs) to plain longitude/latitude
polygon rings, rounded to 1 decimal place (~11 km precision — coarser than the
source's own 110m resolution, so no information is lost by the rounding). Ships
as a static asset, not fetched from any CDN at runtime — see ADR-0021 and
ADR-0002 (no external runtime dependencies).

To regenerate (e.g. to bump to a higher-resolution `50m`/`10m` source): fetch
`countries-<res>.json` from world-atlas, decode its `objects.land` arcs (delta-
decode + apply the topojson `transform` scale/translate), stitch rings per
geometry, round coordinates, and write `{"polygons": [[[ [lon,lat], ... ], ...], ...]}`.
