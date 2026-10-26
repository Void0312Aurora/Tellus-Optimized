"""Fetch/sanitize one bounded OSM probe; the experiment itself uses a frozen snapshot.

OSM data: copyright OpenStreetMap contributors, ODbL. Editor names and account
identifiers are omitted because they are not inputs to geographic loss scoring.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

BBOX=[159.1,-4.7,159.8,-4.4]
URL='https://api.openstreetmap.org/api/0.6/map?bbox='+','.join(map(str,BBOX))
TAG_KEYS={'name','place','population','population:date','building','building:levels','highway','amenity'}

def extract(raw):
    root=ET.fromstring(raw)
    if root.find('error') is not None: raise ValueError('OSM returned an incomplete response')
    nodes={node.attrib['id']:node for node in root.findall('node')}
    selected=[]
    for node in nodes.values():
        tags={tag.attrib['k']:tag.attrib['v'] for tag in node.findall('tag') if tag.attrib['k'] in TAG_KEYS}
        if tags.get('place'):
            selected.append({'type':'node','id':int(node.attrib['id']),'version':int(node.attrib['version']),
                'tags':tags,'coordinates':[float(node.attrib['lon']),float(node.attrib['lat'])]})
    for way in root.findall('way'):
        tags={tag.attrib['k']:tag.attrib['v'] for tag in way.findall('tag') if tag.attrib['k'] in TAG_KEYS}
        if not ('building' in tags or 'highway' in tags): continue
        coordinates=[]
        for ref in way.findall('nd'):
            node=nodes.get(ref.attrib['ref'])
            if node is None: raise ValueError('selected OSM way has missing geometry')
            coordinates.append([float(node.attrib['lon']),float(node.attrib['lat'])])
        selected.append({'type':'way','id':int(way.attrib['id']),'version':int(way.attrib['version']),
            'tags':tags,'coordinates':coordinates})
    return sorted(selected,key=lambda item:(item['type'],item['id']))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--from-xml',type=Path)
    parser.add_argument('--output',type=Path,default=Path('tools/geography/osm-nukumanu-snapshot.json'))
    args=parser.parse_args()
    if args.from_xml:
        raw=args.from_xml.read_bytes()
        observed=datetime.datetime.fromtimestamp(args.from_xml.stat().st_mtime,datetime.timezone.utc).isoformat()
    else:
        request=urllib.request.Request(URL,headers={'User-Agent':'Tellus-RFC-geography-research/1.0','Accept':'application/xml'})
        with urllib.request.urlopen(request,timeout=40) as response: raw=response.read()
        observed=datetime.datetime.now(datetime.timezone.utc).isoformat()
    snapshot={'source_url':URL,'bbox_lon_lat':BBOX,'observed_at_utc':observed,'source_xml_sha256':hashlib.sha256(raw).hexdigest(),
        'attribution':'OpenStreetMap contributors','license':'ODbL 1.0','license_url':'https://www.openstreetmap.org/copyright',
        'scope':'bounded Nukumanu-area probe; tagged building/highway ways and place nodes only; no completeness claim',
        'population_status':'population tags retained when present; no missing population is converted to zero',
        'elements':extract(raw)}
    args.output.write_text(json.dumps(snapshot,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(f'Frozen {len(snapshot["elements"])} selected OSM elements',flush=True)
