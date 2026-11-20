import base64, json, tempfile, runpy, sys
from pathlib import Path
import requests
from PIL import Image, PngImagePlugin
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request
api=runpy.run_path(str(Path(__file__).resolve().parent.parent/'forge_remote_api.py'),run_name='remote_bridge_test')
def request(host,headers):
 return Request(dict(type='http',scheme='http',path='/',server=('127.0.0.1',17872),client=(host,11111),headers=[(k.encode(),v.encode()) for k,v in headers]))
for host,headers,ok in [('127.0.0.1',[('host','localhost:17872')],True),('10.1.2.3',[('host','localhost')],False),('127.0.0.1',[('host','foobar.test')],False),('127.0.0.1',[('host','localhost'),('origin','https://outside.test')],False),('127.0.0.1',[('host','localhost'),('x-forwarded-for','9.9.9.9')],False)]:
 try: api['authorize'](request(host,headers)); actual=True
 except HTTPException as ex: actual=False; assert ex.status_code==403
 assert actual==ok
with tempfile.TemporaryDirectory() as td:
 p=Path(td); (p/'results').mkdir(); fn=p/'results/synthetic.png'
 text=PngImagePlugin.PngInfo();text.add_text('parameters','synthetic-native-test')
 Image.new('RGB',(520,255)).save(fn,pnginfo=text)
 folder=p/'extensions/sd-webui-tagcomplete-neo/tags';folder.mkdir(parents=True)
 (folder/'main.csv').write_text('apple,0,5,green_fruit\n',encoding='utf8')
 (folder/'extra.csv').write_text('added_custom,5,Quality tag,,\n',encoding='utf8')
 (folder/'jp.csv').write_text('apple,リンゴ\n',encoding='utf8')
 files=api['NativeFiles'](p,lambda:{'outdir_txt2img_samples':'results','tac_tagFile':'main.csv','tac_extra.extraFile':'extra.csv','tac_translation.translationFile':'jp.csv'})
 lora_folder=folder/'temp';lora_folder.mkdir()
 (lora_folder/'lora.txt').write_text('"examples/Test.safetensors","1",,"Insert Alias","flux|xl"\n"older/Plain.safetensors","2",\n',encoding='utf8')
 catalog=files.loras()
 assert catalog['items']==[
     {'name':'Test','alias':'Insert Alias','path':'examples/Test.safetensors','arch':['flux','xl']},
     {'name':'Plain','alias':'Plain','path':'older/Plain.safetensors','arch':[]}]
 filename=api['NativeFiles'](p,lambda:{'lora_preferred_name':'Filename','tac_useLoras':False})
 assert filename.loras()['items'][0]['alias']=='Test' and not filename.loras()['auto_complete']
 listing=files.gallery()
 assert len(listing)==1 and listing[0]['name']=='synthetic.png'
 assert files.path(listing[0]['id'])==fn
 assert files.suggestions('りんご')==['apple'] and files.suggestions('ﾘﾝｺﾞ')==['apple']
 assert files.suggestions('added')==['added_custom'] and files.suggestions('green')==['apple']
 try:files.path('../secrets');raise AssertionError()
 except HTTPException as e: assert e.status_code==404
print('PASS synthetic gallery, metadata source, tag+alias search, unknown ID, access guard')
# Gradio explicitly returns [] when the user selected no Hires module. Preserve it.
from unittest.mock import patch
for raw, wanted in [(None,["Use same choices"]),([],[]),(["test-vae.safetensors"],["test-vae.safetensors"])]:
    app=FastAPI()
    api["install"](app,Path(".").resolve(),lambda:{},"http://127.0.0.1:7860")
    endpoint=next(r.endpoint for r in app.routes if getattr(r,"path","").endswith("/generation-defaults"))
    fake={"components":[{"props":{"elem_id":"hr_vae_te","value":raw}}]}
    def local_read(base, route): return fake if route=="/config" else {}
    with patch.dict(api["install"].__globals__, {"local_json":local_read}):
        assert endpoint()["txt2img"]["hr_additional_modules"]==wanted
print("PASS Hires modules inheritance, explicit empty selection and explicit module")

# Preserve raw spelling/search keys for legacy clients. Formatting only applies at insertion.
with tempfile.TemporaryDirectory() as td:
    import csv
    root=Path(td)
    directory=root/"extensions/sd-webui-tagcomplete-neo/tags"
    directory.mkdir(parents=True)
    main_tags=[
        ("red_hair_(test)","ruby"),
        ("artist_[square]","square"),
        ("(o)_(o)","emoticon"),
        (r"already_\(quoted\)","escaped"),
        (r"even_\\[test\\]","even"),
        (r"odd_\\\(test\\\)","odd"),
        ("Keep_Me","upper"),
        ("keep_me","lower"),
        ("duplicate_(source)","duplicate"),
    ]
    with (directory/"main.csv").open("w",encoding="utf8",newline="") as f:
        csv.writer(f).writerows([(tag,0,2,alias) for tag,alias in main_tags])
    with (directory/"extra.csv").open("w",encoding="utf8",newline="") as f:
        csv.writer(f).writerows([("custom_(blue)_[eye]",5,"quality","","青"),
                                 ("duplicate_(source)",5,"quality","","")])
    settings={"tac_tagFile":"main.csv","tac_extra.extraFile":"extra.csv"}
    app=FastAPI()
    api["install"](app,root,lambda:settings,"http://127.0.0.1:1")
    tags=next(r.endpoint for r in app.routes if getattr(r,"path","").endswith("/tags"))
    def check(query,name,insert,count=2):
        assert tags(query)==[name]
        assert tags(query,True)==[{"name":name,"insert":insert,"count":count}]
    check("ruby","red_hair_(test)",r"red hair \(test\)")
    check("square","artist_[square]",r"artist \[square\]")
    check("emoticon","(o)_(o)",r"\(o\)_\(o\)")
    check("escaped",r"already_\(quoted\)",r"already \(quoted\)")
    check("even",r"even_\\[test\\]",r"even \\\[test\\\]")
    check("odd",r"odd_\\\(test\\\)",r"odd \\\(test\\\)")
    check("青","custom_(blue)_[eye]","custom (blue) [eye]",None)
    check("duplicate","duplicate_(source)","duplicate (source)")
    settings["tac_extra.addMode"]="Insert after"
    check("duplicate","duplicate_(source)",r"duplicate \(source\)")
    settings["tac_undersocreReplacementExclusionList"]=" title, Keep_Me ,another "
    check("upper","Keep_Me","Keep_Me")
    check("lower","keep_me","keep me")
    settings["tac_replaceUnderscores"]=False
    check("ruby","red_hair_(test)",r"red_hair_\(test\)")
    settings["tac_escapeParentheses"]=False
    check("ruby","red_hair_(test)","red_hair_(test)")
    settings["tac_replaceUnderscores"]=True
    check("ruby","red_hair_(test)","red hair (test)")
print("PASS TAC opt-in insertion, unchanged legacy names, main/extra priority, exact exclusions, slash parity and live setting changes")

with tempfile.TemporaryDirectory() as td:
    root=Path(td)
    directory=root/"extensions/sd-webui-tagcomplete-neo/tags"
    directory.mkdir(parents=True)
    counts=[2]*40
    counts[5]="1,020"
    counts[-1]=800_000
    with (directory/"counts.csv").open("w",encoding="utf8",newline="") as f:
        writer=csv.writer(f)
        writer.writerows([(f"group_{i}",0,c,"") for i,c in enumerate(counts)])
        writer.writerows([("rare_zero",0,0,"edge"),("rare_(dual)",0,15,"edge"),
                          ("rare_malformed",0,"2,5","edge"),("rare_negative",0,-3,"edge"),
                          ("rare_name",0,"1k","edge"),("rare_tie",0,15,"edge")])
    with (directory/"extra.csv").open("w",encoding="utf8",newline="") as f:
        csv.writer(f).writerows([("rare_extra",5,"123","edge"),("rare_(dual)",5,"Custom tag","","")])
    (directory/"translate.csv").write_text("group_39,共通\nrare_(dual),共通\n",encoding="utf8")
    opts={"tac_tagFile":"counts.csv","tac_extra.extraFile":"extra.csv","tac_translation.translationFile":"translate.csv"}
    files=api["NativeFiles"](root,lambda:opts)
    assert files.suggestions("group")==[f"group_{i}" for i in range(32)]
    ranked=files.suggestions("group",True)
    assert len(ranked)==32 and [r["name"] for r in ranked[:5]]==["group_39","group_5","group_0","group_1","group_2"]
    assert [r["count"] for r in ranked[:4]]==[800_000,1020,2,2]
    assert files.suggestions("共通",True)==[
        {"name":"group_39","insert":"group 39","count":800_000},
        {"name":"rare_(dual)","insert":"rare (dual)","count":15},
    ]
    edge=files.suggestions("edge",True)
    assert [(r["name"],r["count"]) for r in edge]==[
        ("rare_(dual)",15),("rare_tie",15),("rare_zero",0),("rare_extra",None),
        ("rare_malformed",None),("rare_negative",None),("rare_name",None),
    ]
    assert [r for r in edge if r["name"]=="rare_(dual)"][0]["insert"]=="rare (dual)"
    assert "rare_(dual)" not in files.suggestions("edge")  # legacy extra-first search considered its own aliases only
print("PASS TAC usage counts: global top 32, stable equal counts, grouped count, zero vs unknown, alias/translation, main and extra collisions")
