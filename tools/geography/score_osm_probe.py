"""Count observed OSM feature intersections in the bounded, frozen atoll probe."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from shapely.geometry import Point,Polygon,LineString
from gshhg_geometry import circle_equal_area,equal_area_transformers
from run_coverage import write_csv,projected_perimeters,RADII

def score(directory,snapshot_path):
    metadata=json.loads((directory/'candidate-cube-orientation.json').read_text(encoding='utf-8'))
    source=json.loads(snapshot_path.read_text(encoding='utf-8'))
    contexts=[]
    for vertex in metadata['vertices']:
        forward,inverse=equal_area_transformers(vertex['fitted_latitude_deg'],vertex['fitted_longitude_deg'])
        contexts.append({'forward':forward,'inverse':inverse})
    regions,_=projected_perimeters(directory/'quarantine-boundaries-32.geojson',metadata,contexts)
    structure=[]
    for (cap,zone,index),region in sorted(regions.items()):
        polygons=[region] if region.geom_type=='Polygon' else list(region.geoms)
        structure.append({'cap_radius_km':cap,'zone':zone,'vertex':index+1,
            'connected_polygon_components':len(polygons),
            'interior_holes':sum(len(p.interiors) for p in polygons)})
    write_csv(directory/'quarantine-boundary-structure.csv',list(structure[0]),structure)
    context=contexts[7];features=[]
    for item in source['elements']:
        q=np.array(item['coordinates'])
        if item['type']=='node':
            x,y=context['forward'].transform(*q)
            geometry=Point(x,y)
            if item['tags']['place'] not in ('village','hamlet','town','city','isolated_dwelling'): continue
            category='settlement'
        else:
            x,y=context['forward'].transform(q[:,0],q[:,1])
            projected=np.stack((x,y),axis=1)
            if 'building' in item['tags']:
                if not np.array_equal(q[0],q[-1]): raise ValueError('observed building way is not closed')
                geometry=Polygon(projected);category='building'
                if not geometry.is_valid: raise ValueError('observed building footprint is invalid')
            else: geometry=LineString(projected);category='highway'
        features.append((item,category,geometry))
    rows=[];objects=[]
    for cap in RADII:
        for zone in ('excluded','quarantined'):
            for kind in ('ideal','mesh_128'):
                region=circle_equal_area((cap+(50 if zone=='quarantined' else 0))*1000) if kind=='ideal' else regions[(cap,zone,7)]
                counts={'settlement':0,'building':0,'highway':0};length=0.;area=0.
                for item,category,geometry in features:
                    if not region.intersects(geometry): continue
                    intersection=region.intersection(geometry)
                    counts[category]+=1
                    if category=='highway': length+=intersection.length
                    if category=='building': area+=intersection.area
                    objects.append({'cap_radius_km':cap,'zone':zone,'geometry':kind,'category':category,'osm_type':item['type'],'osm_id':item['id'],'osm_version':item['version'],'name':item['tags'].get('name','')})
                rows.append(dict(cap_radius_km=cap,transition_width_km=50,zone=zone,geometry=kind,probe_vertex=8,
                    observed_settlement_nodes=counts['settlement'],observed_building_ways=counts['building'],
                    observed_highway_ways=counts['highway'],clipped_highway_m=length,clipped_building_area_m2=area,
                    population='unknown'))
    write_csv(directory/'osm-probe-loss.csv',list(rows[0]),rows)
    write_csv(directory/'osm-probe-objects.csv',['cap_radius_km','zone','geometry','category','osm_type','osm_id','osm_version','name'],objects)
    (directory/'osm-probe-passport.json').write_text(json.dumps({key:value for key,value in source.items() if key!='elements'}|
        {'snapshot_sha256':hashlib.sha256(snapshot_path.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode('utf-8')).hexdigest(),
         'snapshot_hash_convention':'UTF-8 text with LF newlines and no BOM','selected_elements':len(source['elements']),
         'used_settlement_nodes':sum(category=='settlement' for _,category,_ in features),
         'used_building_ways':sum(category=='building' for _,category,_ in features),
         'used_highway_ways':sum(category=='highway' for _,category,_ in features),
         'metric':'spherical LAEA feature intersections in the bounded probe; lengths in projected metres',
         'unmeasured':'global feature totals, relation buildings, completeness, population, shipping and air routes'},indent=2)+'\n',encoding='utf-8')
    coverage_path=directory/'coverage-passport.json'
    if coverage_path.exists():
        coverage=json.loads(coverage_path.read_text(encoding='utf-8'))
        coverage.update({'roads':'bounded observed OSM probe only; global loss not measured',
                         'buildings':'bounded observed OSM probe only; global loss not measured',
                         'osm_probe_passport':'osm-probe-passport.json'})
        coverage_path.write_text(json.dumps(coverage,indent=2)+'\n',encoding='utf-8')
    print('Frozen OSM probe scored',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=Path('build/geography/results'))
    parser.add_argument('--snapshot',type=Path,default=Path('tools/geography/osm-nukumanu-snapshot.json'))
    args=parser.parse_args();score(args.output,args.snapshot)
