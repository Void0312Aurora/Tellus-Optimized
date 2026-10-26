"""Offline GSHHG reader and spherical shoreline/local-area geometry.

Binary layout follows the README in the official 2.3.7 archive. Native files
preserve real coastlines across the dateline; nearest searches never add the
artificial edges used to close a planar Antarctic land mask.
"""
from __future__ import annotations
from dataclasses import dataclass
import hashlib
from pathlib import Path
import struct
import numpy as np
from pyproj import Transformer
from shapely import make_valid
from shapely.geometry import Polygon, box
from shapely.ops import transform, unary_union
from fit_orientation import RADIUS_M, unit, latlon

FILE_SHA256={
    'f':'af9215d58ebc525b2d09654a89959829f09e6edc457f3666759cded37be4ecf6',
    'h':'380f585415006f2b648a1a99383f50bf06518728d62991d98d48e7c2de48ec8f',
}
HEADER=struct.Struct('>11i')

@dataclass
class CoastPolygon:
    id: int
    level: int
    coordinates: np.ndarray  # longitude, latitude, original native contour

def read_polygons(path,verify=True):
    if verify:
        resolution=Path(path).stem.split('_')[-1]
        with Path(path).open('rb') as source:
            digest=hashlib.file_digest(source,'sha256').hexdigest()
        if digest!=FILE_SHA256[resolution]:
            raise ValueError('shoreline file differs from the recorded source snapshot')
    with Path(path).open('rb') as source:
        while header:=source.read(HEADER.size):
            if len(header)!=HEADER.size:
                raise ValueError('truncated polygon header')
            values=HEADER.unpack(header)
            identifier,count,flags=values[:3]
            level=flags&255
            if count<3 or count>20000000 or level not in range(1,7):
                raise ValueError('invalid GSHHG header')
            data=source.read(count*8)
            if len(data)!=count*8:
                raise ValueError('truncated polygon coordinates')
            if level not in (1,5,6):
                continue  # Conservative coastal footprint: inland lakes remain included.
            coordinates=np.frombuffer(data,dtype='>i4').reshape(-1,2).astype(float)/1e6
            if np.any(np.abs(coordinates[:,1])>90):
                raise ValueError('invalid shoreline latitude')
            yield CoastPolygon(identifier,level,coordinates)

def nearest_on_segments(points,a,b):
    """Exact minimum to minor great-circle arcs on the configured sphere.

    Returns radians, closest segment indices and closest Cartesian vectors.
    Degenerate segments use endpoint distance; the closest foot must lie on
    both oriented halfspaces delimiting the minor arc.
    """
    points=np.atleast_2d(points)
    normals=np.cross(a,b)
    norm=np.linalg.norm(normals,axis=1)
    good=norm>1e-14
    normals[good]/=norm[good,None]
    normals[~good]=0
    dot_normal=points@normals.T
    on_arc=(points@np.cross(normals,a).T>=-1e-14)&(points@np.cross(b,normals).T>=-1e-14)&good
    dot_a=points@a.T
    dot_b=points@b.T
    endpoints=np.arccos(np.clip(np.maximum(dot_a,dot_b),-1,1))
    distances=np.where(on_arc,np.arcsin(np.clip(np.abs(dot_normal),0,1)),endpoints)
    indices=np.argmin(distances,axis=1)
    result=distances[np.arange(len(points)),indices]
    closest=[]
    for i,k in enumerate(indices):
        if on_arc[i,k]:
            q=points[i]-dot_normal[i,k]*normals[k]
            q/=np.linalg.norm(q)
        else:
            q=a[k] if dot_a[i,k]>=dot_b[i,k] else b[k]
        closest.append(q)
    return result,indices,np.array(closest)

def shoreline_nearest(path,points,chunk_size=100000):
    points=np.atleast_2d(points)
    results={choice:[None]*len(points) for choice in ('ice_front','grounding_line')}
    arrays=[]; ids=[]; levels=[]; count=0; total=0
    def consume():
        nonlocal total
        vectors=np.concatenate(arrays)
        segment_ids=np.concatenate(ids)
        segment_levels=np.concatenate(levels)
        total+=len(segment_ids)
        for choice,antarctic_level in (('ice_front',5),('grounding_line',6)):
            include=(segment_levels==1)|(segment_levels==antarctic_level)
            if not include.any():
                continue
            segments=vectors[include]
            selected_ids=segment_ids[include]
            selected_levels=segment_levels[include]
            distances,indices,closest=nearest_on_segments(points,segments[:,0],segments[:,1])
            for i,distance in enumerate(distances):
                if results[choice][i] is None or distance<results[choice][i]['distance_rad']:
                    lat,lon=latlon(closest[i])
                    results[choice][i]={'distance_rad':float(distance),'distance_m':float(distance*RADIUS_M),
                        'polygon_id':int(selected_ids[indices[i]]),'level':int(selected_levels[indices[i]]),
                        'shore_latitude_deg':float(lat),'shore_longitude_deg':float(lon)}
    for polygon in read_polygons(path):
        q=polygon.coordinates
        vectors=unit(q[:,1],q[:,0])
        # The native contours are closed; allow equivalent +/-180 endpoints.
        if np.linalg.norm(vectors[0]-vectors[-1])>1e-12:
            raise ValueError(f'nonclosed shoreline {polygon.id}')
        for offset in range(0,len(vectors)-1,chunk_size):
            a=vectors[offset:offset+chunk_size]
            b=vectors[offset+1:offset+1+len(a)]
            a=a[:len(b)]
            arrays.append(np.stack((a,b),axis=1))
            ids.append(np.full(len(a),polygon.id,dtype=np.int32))
            levels.append(np.full(len(a),polygon.level,dtype=np.int8))
            count+=len(a)
            if count>=chunk_size:
                consume(); arrays.clear(); ids.clear(); levels.clear(); count=0
    if arrays: consume()
    return results,total

def unwrapped_land_polygon(polygon):
    q=polygon.coordinates.copy()
    q[:,0]=np.rad2deg(np.unwrap(np.deg2rad(q[:,0])))
    if polygon.level in (5,6):
        # Polar contours wind once in longitude. Complete toward the south
        # pole for the planar mask only; these edges are never coast segments.
        winding=abs(q[-1,0]-q[0,0])
        if winding<1e-4:
            return q  # Antarctic islands without a pole-enclosing contour.
        if abs(winding-360)>1e-4:
            raise ValueError('unexpected Antarctic contour winding')
        q=np.vstack((q,[q[-1,0],-90],[q[0,0],-90],q[0]))
    return q

def local_window(latitude,longitude,radius_m):
    alpha=radius_m/RADIUS_M
    half_lat=np.rad2deg(alpha)+.01
    if abs(latitude)+half_lat>=90:
        raise ValueError('study window reaches a pole; use a polar clipping implementation')
    half_lon=np.rad2deg(np.arcsin(np.sin(alpha)/np.cos(np.deg2rad(latitude))))+.01
    return box(longitude-half_lon,latitude-half_lat,longitude+half_lon,latitude+half_lat)

def equal_area_transformers(latitude,longitude):
    sphere=f'+proj=longlat +R={RADIUS_M} +type=crs'
    local=f'+proj=laea +lat_0={latitude} +lon_0={longitude} +R={RADIUS_M} +units=m +type=crs'
    return Transformer.from_crs(sphere,local,always_xy=True),Transformer.from_crs(local,sphere,always_xy=True)

def build_local_masks(path,vertices,radius_m=1000000):
    contexts=[]
    for vertex in vertices:
        lat=vertex['fitted_latitude_deg']; lon=vertex['fitted_longitude_deg']
        forward,inverse=equal_area_transformers(lat,lon)
        contexts.append({'window':local_window(lat,lon,radius_m),'forward':forward,'inverse':inverse,
                         'pieces':{1:[],5:[],6:[]},'repairs':set()})
    selected=0
    for polygon in read_polygons(path):
        q=unwrapped_land_polygon(polygon)
        low=q.min(axis=0); high=q.max(axis=0)
        geometry=None
        for context in contexts:
            xmin,ymin,xmax,ymax=context['window'].bounds
            if high[1]<ymin or low[1]>ymax: continue
            shifts=range(int(np.ceil((xmin-high[0])/360)),int(np.floor((xmax-low[0])/360))+1)
            for shift in shifts:
                if geometry is None:
                    geometry=Polygon(q)
                    selected+=1
                    if not geometry.is_valid: geometry=make_valid(geometry)
                shifted=q.copy(); shifted[:,0]+=360*shift
                piece=Polygon(shifted)
                if not piece.is_valid:
                    context['repairs'].add(polygon.id); piece=make_valid(piece)
                clipped=piece.intersection(context['window'])
                if not clipped.is_empty:
                    projected=transform(context['forward'].transform,clipped)
                    if not projected.is_valid:
                        context['repairs'].add(polygon.id)
                        projected=make_valid(projected)
                    context['pieces'][polygon.level].append(projected)
    for context in contexts:
        for choice,level in (('ice_front',5),('grounding_line',6)):
            context[choice]=unary_union(context['pieces'][1]+context['pieces'][level])
        del context['pieces']
    return contexts,selected

def circle_equal_area(radius_m,vertices=2048):
    projected_radius=2*RADIUS_M*np.sin(radius_m/RADIUS_M/2)
    theta=np.linspace(0,2*np.pi,vertices,endpoint=False)
    return Polygon(np.stack((projected_radius*np.cos(theta),projected_radius*np.sin(theta)),axis=1))
