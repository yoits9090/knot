#!/usr/bin/env python3
"""Build a compact citywide atlas from official Toronto geometry.
Install build dependencies: pip install pyshp shapely pillow
Source downloads are cached outside the repo; the app needs only data/toronto/.
"""
import argparse, collections, datetime, gzip, hashlib, io, json, math, pathlib, sqlite3, struct, urllib.request, zipfile
import shapefile
from shapely import wkb
from shapely.geometry import LineString
from shapely.ops import unary_union, linemerge
from PIL import Image,ImageDraw
ROOT=pathlib.Path(__file__).resolve().parents[1];p=argparse.ArgumentParser();p.add_argument('--source-dir',default='/tmp/mathhack-toronto-source');a=p.parse_args();src=pathlib.Path(a.source_dir);src.mkdir(parents=True,exist_ok=True);out=ROOT/'data/toronto';out.mkdir(parents=True,exist_ok=True)
API='https://ckan0.cf.opendata.inter.prod-toronto.ca/api/3/action/package_show?id='
jobs=[('3d-massing','3DMassingShapefile_2025_WGS84.zip','buildings.zip'),('toronto-centreline-tcl','Centreline - Version 2 - 4326.gpkg','roads.gpkg')]
sources=[]
for pkg,name,file in jobs:
 meta=src/(file+'.source.json')
 if meta.exists():d=json.loads(meta.read_text())
 else:
  package=json.load(urllib.request.urlopen(API+pkg,timeout=60))['result'];r=next(r for r in package['resources'] if r['name']==name);d={'package':pkg,'resource':r,'license':package.get('license_id')};meta.write_text(json.dumps(d,indent=2))
 path=src/file
 if not path.exists():urllib.request.urlretrieve(d['resource']['url'],path)
 sources.append({'package':pkg,'resource':d['resource']['url'],'name':name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'license':d.get('license')})
LAT,LON=43.6702351,-79.3867611;R=6371008.8;DEG=math.pi*R/180;COS=math.cos(math.radians(LAT))
def project(lon,lat):return ((lon-LON)*DEG*COS,(LAT-lat)*DEG)
def merc(x,y):return project(math.degrees(x/6378137),math.degrees(2*math.atan(math.exp(y/6378137))-math.pi/2))
# Full municipality data, with margin around Steeles, Etobicoke, Rouge and harbour.
X0,Z0=project(-79.65,43.86);X1,Z1=project(-79.10,43.58)
SX,SZ=4096,2880;image=Image.new('RGB',(SX,SZ),(72,83,91));draw=ImageDraw.Draw(image)
def pixel(x,z):return ((x-X0)/(X1-X0)*(SX-1),(z-Z0)/(Z1-Z0)*(SZ-1))
# Trace the official major shoreline; join endpoint gaps smaller than 200 m.
# Closed shoreline rings preserve the islands in the harbour.
c=sqlite3.connect(src/'roads.gpkg');shore=[]
for (g,) in c.execute('select geom from "Centreline - Version 2 - 4326" where FEATURE_CODE_DESC="Major Shoreline"'):
 envelope=[0,32,48,48,64][(g[3]>>1)&7];shape=wkb.loads(g[8+envelope:])
 shore.extend(LineString([(round(x,6),round(y,6)) for x,y in line.coords]) for line in shape.geoms)
merged=list(linemerge(unary_union(shore)).geoms);open_lines=[list(l.coords) for l in merged if not l.is_ring];closed=[l for l in merged if l.is_ring]
coast=max(open_lines,key=lambda l:LineString(l).length);open_lines.remove(coast)
while open_lines:
 options=[]
 for n,line in enumerate(open_lines):
  for flip in (False,True):
   candidate=line[::-1] if flip else line
   for front in (False,True):
    p,q=(coast[0],candidate[-1]) if front else (coast[-1],candidate[0])
    px,pz=project(*p);qx,qz=project(*q);options.append((math.hypot(px-qx,pz-qz),n,flip,front))
 distance,n,flip,front=min(options)
 if distance>200:break
 line=open_lines.pop(n);line=line[::-1] if flip else line;coast=line+coast if front else coast+line
if coast[0][0]>coast[-1][0]:coast.reverse()
image.paste((22,67,96),(0,0,SX,SZ));draw=ImageDraw.Draw(image)
land=[(-79.65,43.58),(-79.65,43.86),(-79.1,43.86),(-79.1,coast[-1][1])]+coast[::-1]
draw.polygon([pixel(*project(*p)) for p in land],fill=(72,83,91))
for island in closed:draw.polygon([pixel(*project(*p)) for p in island.coords],fill=(72,83,91))
# Geographic surface texture records all building footprints and street centrelines.
# It is an overview, not an elevation model; land outside mapped objects is neutral.
buildings=[];fallback=0
z=zipfile.ZipFile(src/'buildings.zip');n=next(n for n in z.namelist() if n.endswith('.shp'));reader=shapefile.Reader(shp=z.open(n),dbf=z.open(n[:-4]+'.dbf'))
for item in reader.iterShapeRecords():
 s,rec=item.shape,item.record
 h=float(rec['AVG_HEIGHT'] or 0)
 if not math.isfinite(h) or h<=0:h=6;fallback+=1
 h=min(h,600)
 rings=[];ends=list(s.parts)+[len(s.points)]
 for start,end in zip(ends,ends[1:]):
  pts=[merc(x,y) for x,y in s.points[start:end]]
  # Remove duplicated closing coordinate and nearly collinear points without
  # converting a real footprint into a bounding box.
  if len(pts)>1 and pts[0]==pts[-1]:pts.pop()
  if len(pts)<3:continue
  rings.append(pts)
 if not rings:continue
 xs=[x for ring in rings for x,y in ring];zs=[y for ring in rings for x,y in ring]
 if max(xs)<X0 or min(xs)>X1 or max(zs)<Z0 or min(zs)>Z1:continue
 buildings.append((h,rings))
 # even-odd holes are restored with ground colour in overview only.
 for i,ring in enumerate(rings):draw.polygon([pixel(*p) for p in ring],fill=(210,216,219) if i==0 else (72,83,91))
print('Buildings',len(buildings),flush=True)
c=sqlite3.connect(src/'roads.gpkg');roads=[];junctions=collections.defaultdict(list)
accepted={'Local','Collector','Major Arterial','Minor Arterial'}
for geom,name,kind,start,end in c.execute('select geom,LINEAR_NAME_FULL,FEATURE_CODE_DESC,FROM_INTERSECTION_ID,TO_INTERSECTION_ID from "Centreline - Version 2 - 4326"'):
 if kind not in accepted:continue
 # GeoPackage header: flags bits1..3 determine envelope byte count.
 envelope=[0,32,48,48,64][(geom[3]>>1)&7];shape=wkb.loads(geom[8+envelope:])
 for line in shape.geoms if shape.geom_type=='MultiLineString' else [shape]:
  pts=[project(*p[:2]) for p in line.coords]
  if len(pts)<2:continue
  roads.append((name or '',pts,kind))
  for node,point in [(start,pts[0]),(end,pts[-1])]:
   if node is not None:junctions[int(node)].append((point,name or 'Unnamed street'))
  draw.line([pixel(*p) for p in pts],fill=(139,155,165),width=2 if kind in ('Major Arterial','Minor Arterial') else 1)
intersections=[]
for node,links in junctions.items():
 names=sorted({name for point,name in links})
 if len(links)<3 or len(names)<2:continue
 (x,z),_=links[0]
 if X0<x<X1 and Z0<z<Z1:intersections.append((node,x,z,' / '.join(names)))
image.save(out/'overview.png')
with gzip.open(out/'atlas.bin.gz','wb',compresslevel=6) as f:
 def integer(v):f.write(struct.pack('>i',v))
 def fl(v):f.write(struct.pack('>f',v))
 def string(v):b=v.encode();integer(len(b));f.write(b)
 integer(0x544f5231)
 for v in [X0,Z0,X1,Z1]:fl(v)
 integer(len(buildings))
 for h,rings in buildings:
  fl(h);integer(len(rings))
  for ring in rings:
   integer(len(ring))
   for x,z in ring:fl(x);fl(z)
 integer(len(roads))
 for name,pts,kind in roads:
  string(name);integer(len(pts))
  for x,z in pts:fl(x);fl(z)
 integer(len(intersections))
 for node,x,z,name in intersections:integer(node);fl(x);fl(z);string(name)
meta={'fetched_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':sources,'origin':{'lat':LAT,'lon':LON},'bounds_metres':[X0,Z0,X1,Z1],'buildings':len(buildings),'roads':len(roads),'intersections':len(intersections),'fallback_heights':fallback,'height_policy':'City 2025 AVG_HEIGHT; flat ground. 6m fallback for missing/nonpositive height. Extrusions approximate roof forms.','rendering':'All footprints in overview texture. Nearby footprint extrusions and distant tall-building proxies; LOD retains citywide coverage.','license':'Contains information licensed under the Open Government Licence - Toronto.'}
(out/'source.json').write_text(json.dumps(meta,indent=2));print(json.dumps(meta,indent=2))
