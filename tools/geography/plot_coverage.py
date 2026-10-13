"""Standalone scientific figure from recorded tables; no additional data queries."""
import argparse
import csv
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

def read(path):
    return list(csv.DictReader(path.open(encoding='utf-8-sig',newline='')))

def plot(directory):
    actual=[r for r in read(directory/'coverage-scenarios.csv') if r['resolution']=='f' and r['antarctica']=='ice_front']
    ideal=[r for r in read(directory/'ideal-cap-land.csv') if r['resolution']=='f' and r['antarctica']=='ice_front']
    mesh=read(directory/'quarantine-mesh-summary.csv')
    nearest=[r for r in read(directory/'nearest-shorelines.csv') if r['resolution']=='f' and r['antarctica']=='ice_front' and r['coordinate_variant']=='fitted']
    caps=np.array([float(r['cap_radius_km']) for r in actual])
    threshold=min(float(r['measured_nearest_shore_km']) for r in nearest)-50
    fig,axes=plt.subplots(1,2,figsize=(12.4,5.5))
    fig.subplots_adjust(left=.085,right=.985,bottom=.2,top=.85,wspace=.29)
    fig.suptitle('Eight fitted cube vertices: land loss and local scale',fontsize=16,fontweight='bold')
    for zone,label,color in [('excluded','Ideal excluded caps','#2f6a91'),('quarantined','Ideal caps + 50 km band','#bc7c25')]:
        areas=[sum(float(r['land_envelope_overlap_km2']) for r in ideal if float(r['cap_radius_km'])==cap and r['zone']==zone) for cap in caps]
        axes[0].plot(caps,areas,'o-',color=color,label=label,lw=1.8,ms=4)
    axes[0].plot(caps,[float(r['quarantined_land_envelope_km2']) for r in actual],'o-',color='#a64140',label='Whole-cell quarantine, n=128',lw=2.3,ms=4)
    axes[0].set_yscale('symlog',linthresh=1)
    axes[0].set_ylabel('Represented coastal land affected (km²; symlog)')
    axes[0].legend(frameon=False,fontsize=9,loc='upper left')
    axes[1].plot(caps,[float(r['min_sample_boundary_scale']) for r in mesh],'o-',color='#287d65',label='Sampled minimum at ideal ordinary boundary',lw=2.3,ms=4)
    axes[1].plot(caps,[float(r['min_ordinary_edge_scale']) for r in mesh],'o--',color='#6d69a6',label='Minimum ordinary coarse-mesh edge scale',lw=1.8,ms=4)
    axes[1].set_ylabel('Physical scale / nominal equiangular reference')
    axes[1].set_ylim(.39,.67)
    axes[1].legend(frameon=False,fontsize=8,loc='upper left')
    for ax in axes:
        ax.axvline(threshold,color='#777777',ls=':',lw=1.4)
        ax.set_xlabel('Excluded cap radius (km); transition width fixed at 50 km')
        ax.set_xticks(caps);ax.tick_params(labelsize=9)
        ax.grid(axis='y',alpha=.2)
        ax.spines[['top','right']].set_visible(False)
    axes[1].text(threshold+5,.408,f'Ideal band reaches represented shore\nat cap ≈ {threshold:.1f} km',fontsize=8,color='#555555')
    fig.text(.085,.045,'GSHHG 2.3.7 full resolution, Antarctic ice front; spherical Earth. Area estimates and sampled scales are not gameplay acceptance.',fontsize=8,color='#555555')
    fig.savefig(directory/'coverage-scale-tradeoff.png',dpi=180)
    fig.savefig(directory/'coverage-scale-tradeoff.svg',metadata={'Date':None})
    plt.close(fig)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=Path('build/geography/results'))
    plot(parser.parse_args().output)
