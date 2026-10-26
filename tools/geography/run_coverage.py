"""Measure fitted vertices and cap/whole-cell land loss against pinned GSHHG.

Run only after GeographicQuarantineStudy has produced the two boundary files.
This evaluates represented coastal land envelopes; it does not infer road,
building, population, shipping-route, or modern coastline completeness.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
from pathlib import Path
import platform
import numpy as np
import pyproj
import shapely
from shapely.geometry import Point,Polygon,mapping
from shapely.ops import transform
from fit_orientation import RADIUS_M,unit
from gshhg_geometry import FILE_SHA256,build_local_masks,circle_equal_area,shoreline_nearest

RADII=(50,100,150,175,200,250,300,360)

def write_csv(path,fields,rows):
    with path.open('w',encoding='utf-8',newline='') as out:
        writer=csv.DictWriter(out,fieldnames=fields,lineterminator='\n')
        writer.writeheader()
        for row in rows:
            writer.writerow({key:format(value,'.9f') if isinstance(value,float) else value for key,value in row.items()})

def projected_perimeters(path,metadata,contexts):
    assignments={tuple(v['canonical_signs']):i for i,v in enumerate(metadata['vertices'])}
    regions={};loops={}
    for feature in json.loads(path.read_text(encoding='utf-8'))['features']:
        p=feature['properties']
        index=assignments[tuple(p['canonical_signs'])]
        q=np.array(feature['geometry']['coordinates'])
        x,y=contexts[index]['forward'].transform(q[:,0],q[:,1])
        polygon=Polygon(np.stack((x,y),axis=1))
        if not polygon.is_valid:
            raise ValueError('exported boundary loop is invalid; do not silently repair the quarantine geometry')
        key=(int(p['cap_radius_km']),p['zone'],index)
        # Separate loops can describe holes and point-contact components.
        # Even/odd filling preserves both without guessing ring orientation.
        regions[key]=regions.get(key,Polygon()).symmetric_difference(polygon)
        loops[key]=loops.get(key,0)+1
    expected={(cap,zone,i) for cap in RADII for zone in ('excluded','quarantined') for i in range(8)}
    if regions.keys()!=expected:
        raise ValueError('boundary export is incomplete')
    for region in regions.values():
        if not region.is_valid: raise ValueError('invalid assembled quarantine region')
    return regions,loops

def run(cache,directory):
    metadata=json.loads((directory/'candidate-cube-orientation.json').read_text(encoding='utf-8'))
    vertices=metadata['vertices']
    fitted=unit(np.array([v['fitted_latitude_deg'] for v in vertices]),np.array([v['fitted_longitude_deg'] for v in vertices]))
    supplied=unit(np.array([v['input_latitude_deg'] for v in vertices]),np.array([v['input_longitude_deg'] for v in vertices]))
    points=np.concatenate((fitted,supplied))
    near_rows=[];ideal_rows=[];mesh_rows=[];summary_rows=[];repair_records={};overlap_features=[]
    for resolution in ('f','h'):
        source=cache/f'gshhs_{resolution}.b'
        nearest,segments=shoreline_nearest(source,points)
        print(f'GSHHG {resolution}: nearest shoreline scan completed ({segments} source segments)',flush=True)
        contexts,selected=build_local_masks(source,vertices)
        repair_records[resolution]={'selected_polygons':selected,'repaired_polygon_ids_per_vertex':[sorted(c['repairs']) for c in contexts],
                                    'source_segments_scanned':segments}
        if any(context['ice_front'].covers(Point(0,0)) for context in contexts):
            raise ValueError('a fitted center is on represented land; shoreline distance is not distance to land')
        regions_by_density={};loop_counts={}
        for density in (8,32):
            regions_by_density[density],loop_counts[density]=projected_perimeters(directory/f'quarantine-boundaries-{density}.geojson',metadata,contexts)
        for coastline in ('ice_front','grounding_line'):
            for i,vertex in enumerate(vertices):
                for variant,offset in (('fitted',0),('supplied',8)):
                    n=nearest[coastline][i+offset]
                    near_rows.append(dict(resolution=resolution,antarctica=coastline,vertex=i+1,coordinate_variant=variant,
                        supplied_nearest_land_km=vertex['supplied_nearest_land_km'],measured_nearest_shore_km=n['distance_m']/1000,
                        difference_from_supplied_km=n['distance_m']/1000-vertex['supplied_nearest_land_km'],
                        shore_latitude_deg=n['shore_latitude_deg'],shore_longitude_deg=n['shore_longitude_deg'],
                        gshhg_polygon_id=n['polygon_id'],gshhg_level=n['level']))
            for cap in RADII:
                totals={zone:0. for zone in ('excluded','quarantined')}
                area_totals={zone:0. for zone in totals}
                coarse_totals={zone:0. for zone in totals}
                for zone in ('excluded','quarantined'):
                    radius=cap*1000+(50000 if zone=='quarantined' else 0)
                    circle=circle_equal_area(radius)
                    for i,vertex in enumerate(vertices):
                        context=contexts[i];land=context[coastline]
                        distance=nearest[coastline][i]['distance_m']
                        ideal_overlap=land.intersection(circle)
                        ideal_rows.append(dict(resolution=resolution,antarctica=coastline,vertex=i+1,cap_radius_km=cap,
                            transition_width_km=50,zone=zone,zone_radius_km=radius/1000,
                            shore_clearance_km=(distance-radius)/1000,land_envelope_overlap_km2=ideal_overlap.area/1e6,
                            sphere_area_fraction=(1-np.cos(radius/RADIUS_M))/2))
                        for density in (8,32):
                            region=regions_by_density[density][(cap,zone,i)]
                            intersection=land.intersection(region)
                            overlap=intersection.area/1e6
                            mesh_rows.append(dict(resolution=resolution,antarctica=coastline,vertex=i+1,cap_radius_km=cap,
                                transition_width_km=50,zone=zone,subdivision=128,samples_per_edge=density,
                                perimeter_loops=loop_counts[density][(cap,zone,i)],
                                mapped_surface_area_km2=region.area/1e6,land_envelope_overlap_km2=overlap,
                                region_land_fraction=intersection.area/region.area))
                            if density==32:
                                totals[zone]+=overlap;area_totals[zone]+=region.area
                                if resolution=='f' and coastline=='ice_front' and cap in (100,150,200,300,360) and not intersection.is_empty:
                                    geographic=transform(context['inverse'].transform,intersection)
                                    overlap_features.append({'type':'Feature','properties':{'vertex':i+1,'cap_radius_km':cap,'zone':zone,
                                        'land_overlap_km2':overlap,'resolution':'f','antarctica':'ice_front'},'geometry':mapping(geographic)})
                            else: coarse_totals[zone]+=overlap
                row=dict(resolution=resolution,antarctica=coastline,cap_radius_km=cap,transition_width_km=50,
                    subdivision=128,excluded_land_envelope_km2=totals['excluded'],quarantined_land_envelope_km2=totals['quarantined'],
                    transition_only_land_envelope_km2=totals['quarantined']-totals['excluded'],
                    quarantined_sphere_fraction=area_totals['quarantined']/(4*np.pi*RADIUS_M**2),
                    edge_8_to_32_land_delta_km2=totals['quarantined']-coarse_totals['quarantined'],
                    ideal_quarantined_sphere_fraction=4*(1-np.cos((cap+50)*1000/RADIUS_M)))
                summary_rows.append(row)
        print(f'GSHHG {resolution}: all cap and mapped-cell intersections completed; repairs={[len(c["repairs"]) for c in contexts]}',flush=True)
    for name,rows in [('nearest-shorelines.csv',near_rows),('ideal-cap-land.csv',ideal_rows),('quarantine-land.csv',mesh_rows),('coverage-scenarios.csv',summary_rows)]:
        write_csv(directory/name,list(rows[0]),rows)
    (directory/'land-overlap-diagnostics.geojson').write_text(json.dumps({'type':'FeatureCollection','features':overlap_features},separators=(',',':'))+'\n',encoding='utf-8')
    passport={'study_id':'eight-candidate-gshhg-coverage-v1','data_version':'GSHHG 2.3.7 (2017-06-15)',
        'official_page':'https://www.soest.hawaii.edu/pwessel/gshhg/',
        'source_file_sha256':FILE_SHA256,'orientation_id':metadata['orientation_id'],'earth_radius_m':RADIUS_M,
        'projection':'spherical Lambert azimuthal equal area centered on each fitted vertex',
        'land_definition':'level-1 coastal envelopes union selected Antarctic level 5/6; inland water not subtracted',
        'nearest_definition':'minimum to every native minor great-circle shoreline arc; no dateline splitting edges',
        'circle_vertices':2048,'boundary_edge_samples':[8,32],'subdivision':128,'local_mask_window_radius_km':1000,
        'geometry_repairs':repair_records,'population':'not measured','roads':'not measured','buildings':'not measured',
        'shipping_and_air_routes':'not measured','modern_coastline_completeness':'not certified',
        'runtime':{'python':platform.python_version(),'numpy':np.__version__,'shapely':shapely.__version__,'pyproj':pyproj.__version__},
        'input_hash_convention':'UTF-8 text with LF newlines and no BOM',
        'inputs_sha256':{name:hashlib.sha256((directory/name).read_text(encoding='utf-8-sig').replace('\r\n','\n').encode('utf-8')).hexdigest() for name in ('candidate-cube-orientation.json','quarantine-boundaries-8.geojson','quarantine-boundaries-32.geojson')}}
    (directory/'coverage-passport.json').write_text(json.dumps(passport,indent=2)+'\n',encoding='utf-8')
    print('Coverage tables saved',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--cache',type=Path,default=Path('build/geography/gshhg-2.3.7'))
    parser.add_argument('--output',type=Path,default=Path('build/geography/results'))
    args=parser.parse_args()
    run(args.cache,args.output)
