"""Read-only thumbnail catalog/lookup contract, against synthetic files."""
import base64
import csv
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
from fastapi import FastAPI, HTTPException
from PIL import Image, PngImagePlugin

api=runpy.run_path(str(Path(__file__).resolve().parent.parent/"forge_remote_api.py"),run_name="remote_bridge_test")


def model(path, image=None):
    path.parent.mkdir(parents=True,exist_ok=True)
    metadata={}
    if image:
        metadata["ssmd_cover_images"]=json.dumps([base64.b64encode(image).decode("ascii")])
    raw=json.dumps({"__metadata__":metadata}).encode()
    path.write_bytes(len(raw).to_bytes(8,"little")+raw)


def picture(rgb):
    f=io.BytesIO();meta=PngImagePlugin.PngInfo();meta.add_text("parameters","omit me")
    Image.new("RGB",(640,320),rgb).save(f,"PNG",pnginfo=meta)
    return f.getvalue()


def verify(files,key,rgb):
    data=files.lora_thumb(key)
    with Image.open(io.BytesIO(data)) as im:
        assert im.format=="JPEG" and im.size==(256,128) and "parameters" not in im.info
        assert all(abs(a-b)<5 for a,b in zip(im.getpixel((30,30)),rgb))
    assert files.lora_thumb(key)==data


def missing(files,key):
    try: files.lora_thumb(key)
    except HTTPException as ex: assert ex.status_code==404
    else: raise AssertionError("Unexpected thumbnail")


with tempfile.TemporaryDirectory() as temp:
    root=Path(temp).resolve();primary=root/"primary";extra=root/"second";external=root/"outside"
    assert api["standalone_lora_roots"](root,[]) == [root/"models/Lora"]
    assert api["standalone_lora_roots"](root,[Path("primary"),extra]) == [primary,extra]
    cache=root/"extensions/sd-webui-tagcomplete-neo/tags/temp/lora.txt"
    cache.parent.mkdir(parents=True)
    base=["a/twin.safetensors","b/twin.safetensors","b/embed.safetensors","missing.safetensors","unsafe.safetensors"]
    with cache.open("w",encoding="utf-8",newline="") as f:
        csv.writer(f).writerows([[name,"", "",alias,"flux"] for name,alias in zip(
            base+["../outside/stolen.safetensors","C:/secret.safetensors"],["first","second","embedded","none","unsafe","bad","bad"])])
    for rel in base: model(primary/rel)
    model(primary/base[2],picture((0,0,210)))
    (primary/base[0]).with_suffix(".preview.png").write_bytes(picture((210,0,0)))
    (primary/base[1]).with_suffix(".png").write_bytes(picture((0,210,0)))
    (primary/base[1]).with_suffix(".json").write_text('{"preferred weight":0.65}',encoding="utf-8")
    model(extra/"nested/z.safetensors")
    model(external/"stolen.safetensors",picture((245,245,245)))
    try:
        (primary/"escape").symlink_to(external,target_is_directory=True)
        (primary/"unsafe.png").symlink_to((primary/base[0]).with_suffix(".preview.png"))
    except OSError:
        pass  # Windows systems without local symlink permission.
    files=api["NativeFiles"](root,lambda:{},lambda:[primary,extra])
    rows=files.loras()["items"];by={r["path"]:r for r in rows}
    assert len(rows)==6 and "[2]/nested/z.safetensors" in by
    assert all("/outside/" not in p and ":" not in p for p in by)
    a,b=by[base[0]],by[base[1]]
    assert a["duplicate_name"] and b["duplicate_name"] and not a.get("ambiguous")
    assert b["preferred_weight"]==0.65
    k=a["thumbnail_id"]; assert len(k)==32 and k!=b["thumbnail_id"]
    verify(files,k,(210,0,0));verify(files,b["thumbnail_id"],(0,210,0));verify(files,by[base[2]]["thumbnail_id"],(0,0,210))
    for invalid in ("../outside","f"*32,by[base[3]]["thumbnail_id"],by[base[4]]["thumbnail_id"]):
        missing(files,invalid)
    renamed=api["NativeFiles"](root,lambda:{"lora_preferred_name":"Filename"},lambda:[primary,extra])
    assert sum(r.get("ambiguous",False) for r in renamed.loras()["items"])==2
    p=(primary/base[0]).with_suffix(".preview.png");now=p.stat().st_mtime_ns
    p.write_bytes(picture((210,0,210)));os.utime(p,ns=(now+1000000000,now+1000000000))
    missing(files,k);new=next(i for i in files.loras()["items"] if i["path"]==base[0])["thumbnail_id"]
    assert new!=k;verify(files,new,(210,0,210));missing(files,k)
    p.unlink();rows=files.loras()["items"];missing(files,next(i for i in rows if i["path"]==base[0])["thumbnail_id"])
    app=FastAPI();api["install"](app,root,lambda:{},"http://127.0.0.1:7860",lambda:[primary,extra])
    route=next(r for r in app.routes if r.path.endswith("/loras/{key}/thumb"))
    assert api["authorize"] in [d.call for d in route.dependant.dependencies]
print("PASS static and embedded image, size/metadata stripping, external roots, scoped IDs, duplicate stems/aliases, file refresh, missing/invalid paths and route guard")
