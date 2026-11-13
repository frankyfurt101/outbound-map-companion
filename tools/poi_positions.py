#!/usr/bin/env python3
"""Find world positions of the points of interest (POIs) in the base game's level files.

Usage:
    python3 poi_positions.py <assets-dir> <out.json> [level ...]

<assets-dir> is the folder holding the base game's Unity files `sharedassets1.assets` and the level
files (extract them from your own dump with tools/extract_romfs.py; the default levels are
level4 level5 level6 level7 = startingLevel, pacificCoast, canyons, mountains). For every
MonoBehaviour that points at a POI data object it records {level, poi id, title, game object name,
world position (x, y, z)} into <out.json>.

This documents how core/src/main/resources/pois.json was produced: its entries {id, region, name,
x, y, z} come from this raw output (level -> region, title -> name). The conversion step itself is
not included here. The script hard-codes Unity object ids (e.g. path id 55403) that are only valid for
the BASE game build (v0); on other builds it will find nothing or the wrong things.
Needs: pip install UnityPy
"""
import argparse
import json
import os
import re
import struct
import sys
from collections import Counter

DEFAULT_LEVELS = ["level4", "level5", "level6", "level7"]


def run(root, out_path, levels):
    import UnityPy
    def strings(raw):
        out=[];i=0
        while i+4<=len(raw):
            n=struct.unpack_from("<i",raw,i)[0]
            if 2<=n<=120 and i+4+n<=len(raw) and all(32<=c<127 for c in raw[i+4:i+4+n]):
                out.append((i,raw[i+4:i+4+n].decode())); i+=4+((n+3)&~3)
            else: i+=4
        return out
    sa=UnityPy.load(os.path.join(root,"sharedassets1.assets"))
    objs={o.path_id:o for o in sa.objects}
    ref=objs[55403].get_raw_data()
    script=ref[16:28]   # m_Script PPtr (fileID,pathID) after m_GameObject(12)+m_Enabled(4)
    pois={}
    for pid,o in objs.items():
        if o.type.name!="MonoBehaviour": continue
        raw=o.get_raw_data()
        if raw[16:28]!=script: continue
        ss=strings(raw)
        ids=[s for _,s in ss if re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*",s)]
        pois[pid]=(ids[0] if ids else None, ss[0][1] if ss else None)
    print("poi data objects:",len(pois), file=sys.stderr)
    def q_rot(q,v):
        x,y,z,w=q; vx,vy,vz=v
        tx=2*(y*vz-z*vy); ty=2*(z*vx-x*vz); tz=2*(x*vy-y*vx)
        return (vx+w*tx+(y*tz-z*ty), vy+w*ty+(z*tx-x*tz), vz+w*tz+(x*ty-y*tx))
    res=[]
    for lv in levels:
        env=UnityPy.load(os.path.join(root,lv))
        f=list(env.files.values())[0]
        ext=[e.path for e in f.externals] if hasattr(f,"externals") else []
        sa_idx=[i+1 for i,p in enumerate(ext) if "sharedassets1.assets" in str(p).lower()]
        print(lv,"externals",len(ext),"sharedassets1 fileID",sa_idx, file=sys.stderr)
        lo={o.path_id:o for o in env.objects}
        tcache={}
        def world(tpid):
            chain=[];p=tpid
            while p and p in lo:
                t=lo[p].read(); chain.append(t); p=t.m_Father.path_id
            pos=(0.0,0.0,0.0)
            # apply from leaf up: pos = parent.pos + parent.rot*(parent.scale*pos)
            first=True
            for t in chain:
                lp=(t.m_LocalPosition.x,t.m_LocalPosition.y,t.m_LocalPosition.z)
                if first: pos=lp; first=False; prev=t; continue
                s=(t.m_LocalScale.x,t.m_LocalScale.y,t.m_LocalScale.z)
                r=(t.m_LocalRotation.x,t.m_LocalRotation.y,t.m_LocalRotation.z,t.m_LocalRotation.w)
                v=q_rot(r,(pos[0]*s[0],pos[1]*s[1],pos[2]*s[2])); pos=(v[0]+lp[0],v[1]+lp[1],v[2]+lp[2])
            return pos
        for pid,o in lo.items():
            if o.type.name!="MonoBehaviour": continue
            raw=o.get_raw_data()
            hit=None
            for i in range(28,len(raw)-11,4):
                fid=struct.unpack_from("<i",raw,i)[0]
                if fid in sa_idx:
                    p=struct.unpack_from("<q",raw,i+4)[0]
                    if p in pois: hit=(i,p); break
            if not hit: continue
            go=struct.unpack_from("<q",raw,4)[0]
            if go not in lo: continue
            g=lo[go].read()
            tp=None
            for c in g.m_Component:
                cp=c.component if hasattr(c,"component") else c
                if cp.path_id in lo and lo[cp.path_id].type.name in ("Transform","RectTransform"): tp=cp.path_id; break
            pos=world(tp) if tp else None
            res.append({"level":lv,"script":raw[16:28].hex(),"off":hit[0],"poi":pois[hit[1]][0],"title":pois[hit[1]][1],"go":g.m_Name,"pos":[round(x,2) for x in pos] if pos else None})
    with open(out_path,"w") as fh:
        json.dump(res,fh,indent=0)
    from collections import Counter
    print(Counter((r["level"],r["script"],r["off"]) for r in res).most_common(12), file=sys.stderr)
    print("wrote",len(res),"entries to",out_path,file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("assets_dir", help="folder with sharedassets1.assets and the level files")
    ap.add_argument("out", help="output JSON (raw POI positions)")
    ap.add_argument("levels", nargs="*", default=DEFAULT_LEVELS, help="level files (default: %s)" % " ".join(DEFAULT_LEVELS))
    a = ap.parse_args()
    try:
        import UnityPy  # noqa: F401
    except ImportError:
        sys.exit("error: missing dependency 'UnityPy' (pip install -r tools/requirements.txt)")
    if not os.path.isfile(os.path.join(a.assets_dir, "sharedassets1.assets")):
        sys.exit("error: sharedassets1.assets not found in " + a.assets_dir)
    run(a.assets_dir, a.out, a.levels)


if __name__ == "__main__":
    main()
