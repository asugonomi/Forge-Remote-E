import ast
import runpy
import sys
import tempfile
from fractions import Fraction
from math import sqrt
from pathlib import Path

from fastapi import FastAPI, HTTPException

api = runpy.run_path(str(Path(__file__).resolve().parent.parent / "forge_remote_api.py"),
                     run_name="remote_bridge_test")
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    ext = root / "extensions/--sd-webui-ar-plusplus"
    ext.mkdir(parents=True)
    (ext / "aspect_ratios.txt").write_text("# 比率\n16:9 # Wide\n3/2:1", encoding="utf-8")
    (ext / "resolutions.txt").write_text("縦, 900, 1600 # カスタム\n", encoding="utf-8")
    app = FastAPI()
    api["install"](app, root, lambda: {}, "http://127.0.0.1:7860")
    handlers = {r.path: r.endpoint for r in app.routes}
    listing = handlers["/forge-remote/v1/size-presets"]()
    assert listing["ratios"][0] == {"label":"16:9","reverse":"9:16","comment":"Wide"}
    assert listing["resolutions"] == [{"label":"縦","width":900,"height":1600,"comment":"カスタム"}]
    request = dict(ratio="16:9",width=1024,height=1024,average=1024,precision=64,reverse=False,one_dimension=False)
    ratio = handlers["/forge-remote/v1/size-presets/ratio"]
    r = ratio(request)
    assert r == {"average":1024,"width":1344,"height":768}
    assert ratio(dict(request, reverse=True))["width"] == 768
    for invalid, status in (({"ratio":"20:13"},409),({"precision":5},422)):
        try:
            ratio(dict(request,**invalid))
        except HTTPException as error:
            assert error.status_code == status
        else:
            raise AssertionError(invalid)
    try:
        api["ar_presets"](root,lambda:{"disabled_extensions":[ext.name]})
    except HTTPException as error:
        assert error.status_code == 404
    else:
        raise AssertionError("Disabled extension was accepted")
print("PASS custom presets, dimensions, reversed ratio, and invalid inputs")
