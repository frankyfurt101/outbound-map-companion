#!/usr/bin/env python3
"""Extract campfire, signal tower and supply cache positions from the base game's level files.

Usage:
    python3 map_features.py <assets-dir> <out.json>

<assets-dir> is the folder holding the base game's level files level4 (startingLevel), level5
(pacificCoast), level6 (canyons) and level7 (mountains), extracted from your own dump with
tools/extract_romfs.py. Writes a JSON list of {id, kind, region, x, y, z} (kind = campfire | tower |
cache; id = "<region>-<object id>"). This is the format of core/src/main/resources/features.json,
which was produced this way. It looks the objects up by hard-coded Unity script ids (2932 campfire,
1550 tower, 3489 cache) that are only valid for the BASE game build (v0). Prints a summary of
counts per region and kind. Needs: pip install UnityPy
"""
import argparse
import json
import os
import re
import struct
import sys
from collections import Counter


def run(root, out_path):
    import UnityPy
    pat=re.compile(rb"[a-z][a-z0-9]*(-[a-z0-9]+)+")
    reg={"level4":"startingLevel","level5":"pacificCoast","level6":"canyons","level7":"mountains"}
    def q_rot(q,v):
        x,y,z,w=q; vx,vy,vz=v
        tx=2*(y*vz-z*vy); ty=2*(z*vx-x*vz); tz=2*(x*vy-y*vx)
        return (vx+w*tx+(y*tz-z*ty), vy+w*ty+(z*tx-x*tz), vz+w*tz+(x*ty-y*tx))
    out=[]; other=Counter()
    for lv in reg:
        env=UnityPy.load(os.path.join(root,lv)); lo={o.path_id:o for o in env.objects}
        def world(tpid):
            chain=[];p=tpid
            while p and p in lo:
                t=lo[p].read(); chain.append(t); p=t.m_Father.path_id
            pos=None
            for t in chain:
                lp=(t.m_LocalPosition.x,t.m_LocalPosition.y,t.m_LocalPosition.z)
                if pos is None: pos=lp; continue
                s=(t.m_LocalScale.x,t.m_LocalScale.y,t.m_LocalScale.z)
                r=(t.m_LocalRotation.x,t.m_LocalRotation.y,t.m_LocalRotation.z,t.m_LocalRotation.w)
                v=q_rot(r,(pos[0]*s[0],pos[1]*s[1],pos[2]*s[2])); pos=(v[0]+lp[0],v[1]+lp[1],v[2]+lp[2])
            return pos
        seen={}
        for pid,o in lo.items():
            if o.type.name!="MonoBehaviour": continue
            raw=o.get_raw_data()
            if len(raw)>6000: continue
            script=struct.unpack_from("<q",raw,20)[0]
            for m in pat.finditer(raw):
                s=m.start()
                if not (s>=4 and struct.unpack_from("<i",raw,s-4)[0]==len(m.group(0))): continue
                t=m.group(0).decode()
                if "fish" in t or "ferry" in t: other[(lv,script,t)]+=1
                kind=None
                if t.startswith("camp-fire-") and script==2932: kind="campfire"
                elif t.startswith("signal-tower-") and script==1550: kind="tower"
                elif t.startswith("supply-cache-") and script==3489: kind="cache"
                if not kind or (kind,t) in seen: continue
                go=struct.unpack_from("<q",raw,4)[0]
                if go not in lo: continue
                g=lo[go].read(); tp=None
                for c in g.m_Component:
                    cp=c.component if hasattr(c,"component") else c
                    if cp.path_id in lo and lo[cp.path_id].type.name in ("Transform","RectTransform"): tp=cp.path_id; break
                if not tp: continue
                pos=world(tp); seen[(kind,t)]=1
                out.append({"id":reg[lv]+"-"+t,"kind":kind,"region":reg[lv],"x":round(pos[0],1),"y":round(pos[1],1),"z":round(pos[2],1)})
    with open(out_path,"w") as fh:
        json.dump(out,fh,indent=0)
    print(Counter((o["region"],o["kind"]) for o in out))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("assets_dir", help="folder with level4 .. level7")
    ap.add_argument("out", help="output JSON")
    a = ap.parse_args()
    try:
        import UnityPy  # noqa: F401
    except ImportError:
        sys.exit("error: missing dependency 'UnityPy' (pip install -r tools/requirements.txt)")
    for lv in ("level4", "level5", "level6", "level7"):
        if not os.path.isfile(os.path.join(a.assets_dir, lv)):
            sys.exit("error: %s not found in %s" % (lv, a.assets_dir))
    run(a.assets_dir, a.out)


if __name__ == "__main__":
    main()
