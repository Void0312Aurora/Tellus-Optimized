"""Independent geometry and binary-parser checks; no network or real data needed."""
import math
from pathlib import Path
import tempfile
import unittest
import numpy as np
from shapely.geometry import Polygon,Point
from fit_orientation import fit_cube,unit,angular_distance,RADIUS_M
from gshhg_geometry import CoastPolygon,HEADER,read_polygons,nearest_on_segments,unwrapped_land_polygon,equal_area_transformers,circle_equal_area
from fetch_osm_probe import extract

class GeographyTests(unittest.TestCase):
    def test_fit_exact_rotated_cube_with_shuffled_labels(self):
        canonical=np.array([[x,y,z] for x in (-1,1) for y in (-1,1) for z in (-1,1)])/np.sqrt(3)
        axis=np.array([1,2,3.])/np.sqrt(14);angle=.72
        cross=np.array([[0,-axis[2],axis[1]],[axis[2],0,-axis[0]],[-axis[1],axis[0],0]])
        rotation=np.eye(3)*np.cos(angle)+(1-np.cos(angle))*np.outer(axis,axis)+np.sin(angle)*cross
        points=(canonical@rotation.T)[[7,0,5,3,2,6,1,4]]
        fitted_rotation,_,fitted,_,_=fit_cube(points)
        self.assertLess(float(angular_distance(points,fitted).max()),1e-13)
        self.assertAlmostEqual(np.linalg.det(fitted_rotation),1,places=12)

    def test_fit_rejects_wrong_shape_nonfinite_and_nonunit(self):
        for points in (np.zeros((7,3)),np.full((8,3),float('nan')),np.zeros((8,3))):
            with self.assertRaises(ValueError): fit_cube(points)

    def test_dateline_minor_arc_and_foot_not_false_antipode(self):
        a=unit(np.array([0.]),np.array([170.]));b=unit(np.array([0.]),np.array([-170.]))
        points=unit(np.array([10.,0.,0.]),np.array([180.,-179.,0.]))
        d,_,q=nearest_on_segments(points,a,b)
        self.assertAlmostEqual(d[0],np.deg2rad(10),places=13)
        self.assertLess(d[1],1e-14)
        self.assertAlmostEqual(d[2],np.deg2rad(170),places=13)
        self.assertTrue(np.allclose(np.linalg.norm(q,axis=1),1))

    def test_polar_arc_endpoint_and_zero_length(self):
        a=unit(np.array([80.]),np.array([0.]));b=unit(np.array([80.]),np.array([180.]))
        d,_,_=nearest_on_segments(unit(np.array([90.]),np.array([30.])),a,b)
        self.assertLess(d[0],1e-14)
        a=unit(np.array([0.]),np.array([0.]));b=unit(np.array([0.]),np.array([10.]))
        d,_,_=nearest_on_segments(unit(np.array([0.]),np.array([80.])),a,b)
        self.assertAlmostEqual(d[0],np.deg2rad(70),places=13)
        d,_,_=nearest_on_segments(unit(np.array([0.]),np.array([10.])),a,a)
        self.assertAlmostEqual(d[0],np.deg2rad(10),places=13)

    def test_analytic_nearest_agrees_with_independent_slerp_sampling(self):
        random=np.random.default_rng(1302026)
        for _ in range(35):
            vectors=random.normal(size=(3,3));vectors/=np.linalg.norm(vectors,axis=1)[:,None]
            p,a,b=vectors
            d,_,_=nearest_on_segments(p,a[None,:],b[None,:])
            angle=float(angular_distance(a,b));t=np.linspace(0,1,10001)
            samples=(np.sin((1-t)*angle)[:,None]*a+np.sin(t*angle)[:,None]*b)/np.sin(angle)
            sampled=float(angular_distance(samples,p).min())
            self.assertLessEqual(d[0],sampled+1e-12)
            self.assertLessEqual(sampled-d[0],angle/20000+1e-12)

    def test_dateline_polygon_is_unwrapped_without_false_long_edge(self):
        q=np.array([[179,-1],[-179,-1],[-179,1],[179,1],[179,-1]],dtype=float)
        polygon=Polygon(unwrapped_land_polygon(CoastPolygon(1,1,q)))
        self.assertTrue(polygon.covers(Point(180,0)))
        self.assertFalse(polygon.covers(Point(0,0)))
        self.assertAlmostEqual(polygon.area,4)

    def test_antarctic_pole_completion_and_separate_islands(self):
        q=np.array([[-180,-80],[-90,-80],[0,-80],[90,-80],[180,-80]],dtype=float)
        polygon=Polygon(unwrapped_land_polygon(CoastPolygon(4,5,q)))
        self.assertTrue(polygon.covers(Point(0,-85)))
        self.assertFalse(polygon.covers(Point(0,-75)))
        island=np.array([[1,-70],[2,-70],[2,-71],[1,-70]],dtype=float)
        self.assertTrue(np.allclose(unwrapped_land_polygon(CoastPolygon(5,6,island)),island))

    def test_equal_area_circle_and_dateline_projection(self):
        radius=300000
        expected=2*np.pi*RADIUS_M**2*(1-np.cos(radius/RADIUS_M))
        self.assertLess(abs(circle_equal_area(radius).area/expected-1),2e-6)
        forward,inverse=equal_area_transformers(-70.635566704862,-173.152396694393)
        x,y=forward.transform(179.,-75.)
        lon,lat=inverse.transform(x,y)
        self.assertAlmostEqual(lon,179.,places=10);self.assertAlmostEqual(lat,-75.,places=10)

    def test_binary_parser_closed_native_contour_and_truncation(self):
        q=np.array([[179000000,0],[180000000,0],[180000000,1000000],[179000000,0]],dtype='>i4')
        data=HEADER.pack(0,4,1,179000000,180000000,0,1000000,0,0,-1,-1)+q.tobytes()
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test.b';path.write_bytes(data)
            polygons=list(read_polygons(path,verify=False))
            self.assertEqual(len(polygons),1);self.assertEqual(polygons[0].id,0)
            self.assertTrue(np.allclose(polygons[0].coordinates,q.astype(float)/1e6))
            path.write_bytes(data[:-1])
            with self.assertRaises(ValueError): list(read_polygons(path,verify=False))
            path.write_bytes(data[:20])
            with self.assertRaises(ValueError): list(read_polygons(path,verify=False))

    def test_changed_dataset_fails_hash_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'gshhs_f.b';path.write_bytes(b'changed')
            with self.assertRaises(ValueError): list(read_polygons(path))

    def test_osm_probe_preserves_features_and_unknown_population(self):
        raw=b'<osm><node id="1" version="2" lat="0" lon="0" user="unused" uid="99"><tag k="place" v="village"/><tag k="name" v="Example"/></node><node id="2" version="1" lat="0" lon="1"/><node id="3" version="1" lat="1" lon="1"/><way id="4" version="3"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="1"/><tag k="building" v="yes"/></way></osm>'
        items=extract(raw)
        self.assertEqual(len(items),2)
        self.assertNotIn('population',items[0]['tags'])
        self.assertEqual(items[1]['id'],4)
        self.assertEqual(len(items[1]['coordinates']),4)
        self.assertNotIn('user',items[0]);self.assertNotIn('uid',items[0])

    def test_incomplete_osm_probe_fails_instead_of_zero_counts(self):
        for raw in (b'<osm><error>incomplete</error></osm>',b'<osm><way id="1" version="1"><tag k="highway" v="path"/><nd ref="99"/></way></osm>'):
            with self.assertRaises(ValueError): extract(raw)

if __name__=='__main__': unittest.main()
