"""Standalone regression test for the dated output gallery, using synthetic local files."""
import io
import os
import runpy
import tempfile
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI, HTTPException
from PIL import Image, PngImagePlugin


def assert_status(status, call):
    try:
        call()
    except HTTPException as error:
        assert error.status_code == status, error.status_code
    else:
        raise AssertionError(f"Expected {status}")


bridge = runpy.run_path(str(Path(__file__).resolve().parent.parent/"forge_remote_api.py"),
                        run_name="remote_bridge_test")
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp).resolve()
    samples = root / "out"
    grids = samples / "grids"
    today = samples / "2025-03-08"
    archive = samples / "2020-04-10" / "collection"
    undated = samples / "2025-02-30"
    for folder in (grids, today, archive, undated):
        folder.mkdir(parents=True)
    png = io.BytesIO()
    tag = PngImagePlugin.PngInfo()
    tag.add_text("parameters","gallery-date-fixture, Steps: 4")
    Image.new("RGB",(1800,600),(30,100,140)).save(png,"PNG",pnginfo=tag)
    data = png.getvalue()

    def put(path, date):
        path.write_bytes(data)
        stamp = datetime.fromisoformat(date).timestamp()
        os.utime(path,(stamp,stamp))

    for n in range(105):
        put(today/f"image-{n:03}.png","2025-03-09T09:00:00")
    put(today/"duplicate.png","2025-03-10T00:00:00")
    put(archive/"duplicate.png","2019-01-01T18:00:00")
    put(grids/"grid.png","2021-01-01T16:00:00")
    put(undated/"midnight.png","2023-02-01T00:00:00")
    put(undated/"late.png","2023-01-31T23:59:59")
    (today/"ignore.txt").write_text("fixture")
    outside = root/"private.png"
    outside.write_bytes(data)
    try:
        (samples/"external.png").symlink_to(outside)
    except OSError:  # Windows accounts without symlink permission.
        pass
    opts={"outdir_txt2img_samples":"out","outdir_img2img_samples":"out","outdir_txt2img_grids":"out/grids"}
    app = FastAPI()
    bridge["install"](app,root,lambda:opts,"http://127.0.0.1:1")
    endpoints = {r.path.removeprefix("/forge-remote/v1"):r.endpoint for r in app.routes}
    dates = endpoints["/gallery-index/dates"]
    page = endpoints["/gallery-index/page"]
    previous = endpoints["/gallery"]
    info = endpoints["/gallery/{key}/info"]
    thumb = endpoints["/gallery/{key}/thumb"]
    display = endpoints["/gallery/{key}/display"]
    original = endpoints["/gallery/{key}"]
    catalog = dates(False)
    ver = catalog["version"]
    days = {r["day"]:r["count"] for r in catalog["days"]}
    assert days == {"2025-03-08":106,"2023-02-01":1,"2023-01-31":1,"2021-01-01":1,"2020-04-10":1}
    assert len(previous()) == 100 and set(previous()[0]) == {"name","id"}
    assert dates(True)["version"] == ver
    assert_status(422,lambda:page("2025-02-30",0,24,ver))
    newest = []
    for offset in range(0,catalog["total"],24):
        chunk = page(None,offset,24,ver)
        assert chunk["offset"] == offset and chunk["total"] == 110
        newest.extend(chunk["items"])
    ids = [r["id"] for r in newest]
    assert len(ids) == len(set(ids)) == 110
    assert newest[-1]["day"] == "2020-04-10" and newest[-1]["source"] == "txt2img / img2img"
    assert newest[-1]["day_source"] == "folder" and newest[-1]["id"] not in {r["id"] for r in previous()}
    assert page("2021-01-01",0,2,ver)["items"][0]["source"] == "txt2img grid"
    assert page("2023-01-31",0,2,ver)["items"][0]["modified"].startswith("2023-01-31T23:59:59")
    assert page("2023-02-01",0,2,ver)["items"][0]["day_source"] == "modified"
    assert [r["id"] for r in page(None,24,24,ver)["items"]] == ids[24:48]
    assert len({r["id"] for r in newest if r["name"]=="duplicate.png"})==2
    key = ids[-1]
    assert Path(original(key).path).read_bytes() == data and info(key)["parameters"].startswith("gallery-date-fixture")
    for endpoint,edge in ((display,1600),(thumb,256)):
        resp=endpoint(key)
        with Image.open(io.BytesIO(resp.body)) as im:
            assert im.format=="JPEG" and im.width==edge and "parameters" not in im.info
    with Image.open(io.BytesIO(display(key,True).body)) as enlarged:
        assert enlarged.size==(1800,600)
    assert_status(404,lambda:original("../private.png"))
    assert_status(404,lambda:original("0"*32))

    # Explicit refresh also detects in-place replacements even when directory mtime did not change.
    put(undated/"late.png","2024-06-03T01:00:00")
    (today/"image-001.png").unlink()
    (archive/"duplicate.png").unlink()
    put(today/"new.png","2025-09-01T04:00:00")
    fresh = dates(True)
    assert fresh["total"]==109 and ver!=fresh["version"]
    assert_status(409,lambda:page(None,24,24,ver))
    assert_status(404,lambda:original(key))
    assert page("2024-06-03",0,24,fresh["version"])["total"]==1
    assert page("2025-03-08",0,24,fresh["version"])["items"][0]["name"] == "new.png"
    # Removed output roots cannot be used through older IDs between library updates.
    opts.update(outdir_txt2img_samples="other",outdir_img2img_samples="other")
    assert_status(404,lambda:original(ids[0]))

print("PASS dated output gallery: 110 fixtures, 5 dates, same names, nested and shared roots, stable paging, legacy list, image access, refresh")
