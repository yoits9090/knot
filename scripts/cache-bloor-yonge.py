#!/usr/bin/env python3
"""Fetch one bounded OSM extract, retain scene geometry, and cache it for offline use."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://api.openstreetmap.org/api/0.6/map?bbox=-79.3902,43.6678,-79.3838,43.6725'
p = argparse.ArgumentParser()
p.add_argument('--input', type=Path, help='Use an already downloaded OSM XML response')
args = p.parse_args()
if args.input:
    raw = args.input.read_bytes()
else:
    request = urllib.request.Request(URL, headers={'User-Agent': 'MathhackathonWind/1.0 (local educational map extract)'})
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read()
source = ET.fromstring(raw)
nodes = {e.attrib['id']: e for e in source.findall('node')}
ways = {e.attrib['id']: e for e in source.findall('way')}
def tags(e):
    return {t.attrib['k']: t.attrib['v'] for t in e.findall('tag')}
def building(t):
    return any(t.get(k, 'no') != 'no' for k in ('building', 'building:part'))
relations = [e for e in source.findall('relation') if building(tags(e)) and tags(e).get('type') == 'multipolygon']
needed_ways = {e.attrib['id'] for e in ways.values() if building(tags(e)) or tags(e).get('highway') in
    {'primary', 'secondary', 'tertiary', 'residential', 'unclassified', 'living_street', 'service', 'pedestrian'}}
needed_ways.update(m.attrib['ref'] for e in relations for m in e.findall('member') if m.attrib['type'] == 'way')
missing = needed_ways - ways.keys()
if missing:
    raise RuntimeError(f'Incomplete relation members: {missing}')
needed_nodes = {n.attrib['ref'] for way_id in needed_ways for n in ways[way_id].findall('nd')}
needed_nodes.add('20964462') # Shared Bloor Street / Yonge Street intersection node.
if needed_nodes - nodes.keys():
    raise RuntimeError('Extract has missing geometry nodes')
output = ET.Element('osm', version='0.6', generator='mathhackshackathon cache',
    copyright='OpenStreetMap contributors', attribution='https://www.openstreetmap.org/copyright',
    license='https://opendatacommons.org/licenses/odbl/1-0/')
for node_id in sorted(needed_nodes, key=int):
    node = nodes[node_id]
    ET.SubElement(output, 'node', {k: node.attrib[k] for k in ('id', 'lat', 'lon')})
allowed = {'name','building','building:part','building:levels','building:min_level','height','min_height',
    'roof:height','roof:levels','highway','lanes','width','tunnel','location','layer','construction',
    'addr:housenumber','addr:street','type'}
for e in [ways[i] for i in sorted(needed_ways, key=int)] + relations:
    item = ET.SubElement(output, e.tag, {k: e.attrib[k] for k in ('id','version','timestamp') if k in e.attrib})
    for child in e:
        if child.tag in ('nd','member') or child.tag == 'tag' and child.attrib['k'] in allowed:
            ET.SubElement(item, child.tag, dict(child.attrib))
ET.indent(output)
path = ROOT / 'data/bloor-yonge.osm'
ET.ElementTree(output).write(path, encoding='utf-8', xml_declaration=True)
center = nodes['20964462'].attrib
manifest = {
    'title': 'Bloor–Yonge, Toronto', 'source_url': URL,
    'fetched_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'attribution': '© OpenStreetMap contributors',
    'copyright_url': 'https://www.openstreetmap.org/copyright', 'license': 'ODbL-1.0',
    'origin_node': 20964462, 'origin_lat': float(center['lat']), 'origin_lon': float(center['lon']),
    'domain_metres': 460, 'terrain': 'Flat plane; no real elevation data included',
    'height_policy': 'height tag; otherwise building:levels × 3 m plus tagged roof height; otherwise 12 m explicit fallback',
    'construction_policy': 'Include geometry and heights as mapped; construction state is not surveyed or verified',
    'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    'nodes': len(needed_nodes), 'ways': len(needed_ways), 'building_relations': len(relations)
}
(ROOT / 'data/bloor-yonge-source.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False)+'\n')
print(json.dumps(manifest, indent=2, ensure_ascii=False))
