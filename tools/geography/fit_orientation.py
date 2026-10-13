"""Fit the user's rounded eight candidates to a rigid cube, without assuming labels.

Coordinates use Tellus's (Greenwich, north, 90E) Cartesian axes. This script is
research tooling, outside the game source sets. No geographic observations are
invented: supplied distances are retained separately and never used in fitting.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import itertools
import json
from pathlib import Path
import numpy as np

RADIUS_M = 6371008.8
AXES = ['greenwich_equator', 'north_pole', '90E_equator']

def unit(latitude, longitude):
    lat, lon = np.deg2rad(latitude), np.deg2rad(longitude)
    return np.stack((np.cos(lat)*np.cos(lon), np.sin(lat), np.cos(lat)*np.sin(lon)), axis=-1)

def latlon(vectors):
    return np.rad2deg(np.arctan2(vectors[..., 1], np.hypot(vectors[..., 0], vectors[..., 2]))), np.rad2deg(np.arctan2(vectors[..., 2], vectors[..., 0]))

def angular_distance(a, b):
    return np.arctan2(np.linalg.norm(np.cross(a,b),axis=-1), np.sum(a*b,axis=-1))

def fit_cube(points):
    points=np.asarray(points,dtype=float)
    if points.shape != (8,3) or not np.isfinite(points).all():
        raise ValueError('eight finite unit vectors are required')
    if not np.allclose(np.linalg.norm(points,axis=1),1,rtol=0,atol=1e-12):
        raise ValueError('non-unit candidate vector')
    canonical=np.array(list(itertools.product((-1,1),repeat=3)),dtype=float)/np.sqrt(3)
    permutations=np.array(list(itertools.permutations(range(8))),dtype=np.int32)
    targets=points[permutations]
    covariances=np.einsum('ni,pnj->pij',canonical,targets)
    left, singular, right=np.linalg.svd(covariances)
    correction=np.tile(np.eye(3),(len(permutations),1,1))
    correction[:,2,2]=np.linalg.det(left@right)
    row_rotations=left@correction@right
    residual=np.einsum('ni,pij->pnj',canonical,row_rotations)-targets
    cost=np.sum(residual**2,axis=(1,2))
    best=float(cost.min())
    # Twenty-four proper cube symmetries yield equivalent physical vertices.
    # Enumeration is lexicographic; retain the first numerical tie explicitly.
    index=int(np.flatnonzero(cost<=best+1e-14)[0])
    rotation=row_rotations[index].T
    fitted=np.empty_like(points)
    fitted[permutations[index]]=canonical@rotation.T
    assignment=np.empty(8,dtype=np.int32)
    assignment[permutations[index]]=np.arange(8)
    return rotation,canonical,fitted,assignment,float(cost[index])

def fit_file(source: Path, directory: Path):
    rows=list(csv.DictReader(source.open(encoding='utf-8-sig',newline='')))
    if len(rows)!=8 or [int(row['vertex']) for row in rows]!=list(range(1,9)):
        raise ValueError('expected candidate IDs 1 through 8')
    points=unit(np.array([float(r['latitude_deg']) for r in rows]),np.array([float(r['longitude_deg']) for r in rows]))
    rotation,canonical,fitted,assignment,cost=fit_cube(points)
    residual=angular_distance(points,fitted)*RADIUS_M
    latitude,longitude=latlon(fitted)
    lat_errors=latitude-np.array([float(r['latitude_deg']) for r in rows])
    lon_errors=(longitude-np.array([float(r['longitude_deg']) for r in rows])+180)%360-180
    directory.mkdir(parents=True,exist_ok=True)
    metadata={
        'orientation_id':'candidate-eight-kabsch-sphere-v1',
        'earth_radius_m':RADIUS_M,'cartesian_axes':AXES,
        'rotation_convention':'column_world_vector = rotation @ column_unrotated_vector',
        'inverse_convention':'column_unrotated_vector = rotation.T @ column_world_vector',
        'rotation_matrix':rotation.tolist(),'determinant':float(np.linalg.det(rotation)),
        'max_orthogonality_error':float(np.max(np.abs(rotation.T@rotation-np.eye(3)))),
        'fit_method':'all 40320 correspondences; proper Kabsch least squares of unit vectors',
        'symmetry_tie_break':'first lexicographic correspondence within 1e-14 squared chord cost',
        'input_sha256':hashlib.sha256(source.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode('utf-8')).hexdigest(),
        'input_hash_convention':'UTF-8 text with LF newlines and no BOM',
        'source_precision_degrees':0.1,'max_residual_m':float(residual.max()),
        'rms_residual_m':float(np.sqrt(np.mean(residual**2))),
        'within_nearest_0_1_degree_rounding_cells':bool(np.all(np.abs(lat_errors)<=.05+1e-12) and np.all(np.abs(lon_errors)<=.05+1e-12)),
        'squared_chord_cost':cost,
        'vertices':[dict(vertex=int(r['vertex']),label=r['label'],
            input_latitude_deg=float(r['latitude_deg']),input_longitude_deg=float(r['longitude_deg']),
            supplied_nearest_land_km=float(r['supplied_nearest_land_km']),
            canonical_signs=np.rint(canonical[assignment[i]]*np.sqrt(3)).astype(int).tolist(),
            fitted_latitude_deg=float(latitude[i]),fitted_longitude_deg=float(longitude[i]),
            residual_m=float(residual[i])) for i,r in enumerate(rows)]}
    (directory/'candidate-cube-orientation.json').write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    (directory/'candidate-cube-rotation.csv').write_text('\n'.join(','.join(format(x,'.17g') for x in row) for row in rotation)+'\n',encoding='ascii')
    with (directory/'candidate-cube-fit.csv').open('w',encoding='utf-8',newline='') as out:
        writer=csv.writer(out,lineterminator='\n')
        writer.writerow(['vertex','label','input_latitude_deg','input_longitude_deg','fitted_latitude_deg','fitted_longitude_deg','residual_m','canonical_x','canonical_y','canonical_z'])
        for i,r in enumerate(rows):
            writer.writerow([r['vertex'],r['label'],r['latitude_deg'],r['longitude_deg'],f'{latitude[i]:.12f}',f'{longitude[i]:.12f}',f'{residual[i]:.6f}',*metadata['vertices'][i]['canonical_signs']])
    with (directory/'candidate-cube-pairwise.csv').open('w',encoding='utf-8',newline='') as out:
        writer=csv.writer(out,lineterminator='\n')
        writer.writerow(['vertex_a','vertex_b','expected_cube_dot','input_dot','input_angle_deg','expected_angle_deg','angle_error_deg'])
        for i,j in itertools.combinations(range(8),2):
            expected=float(canonical[assignment[i]]@canonical[assignment[j]])
            actual=float(points[i]@points[j])
            expected_angle=float(np.rad2deg(np.arccos(np.clip(expected,-1,1))))
            actual_angle=float(np.rad2deg(angular_distance(points[i],points[j])))
            writer.writerow([i+1,j+1,f'{expected:.12f}',f'{actual:.12f}',f'{actual_angle:.12f}',f'{expected_angle:.12f}',f'{actual_angle-expected_angle:.12f}'])
    print(f'Rigid fit RMS={metadata["rms_residual_m"]:.3f} m max={metadata["max_residual_m"]:.3f} m determinant={metadata["determinant"]:.12f}',flush=True)
    return metadata

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--candidates',type=Path,default=Path('tools/geography/candidate-vertices.csv'))
    parser.add_argument('--output',type=Path,default=Path('build/geography/results'))
    args=parser.parse_args()
    fit_file(args.candidates,args.output)
