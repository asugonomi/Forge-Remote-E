"""Read-only discovery and preset selection using simulated Forge installations."""
import runpy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI, HTTPException

API = runpy.run_path(str(Path(__file__).resolve().parents[1]/"forge_remote_api.py"))


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve()/"Forge"
        self.data=self.root.parent/"data elsewhere"
        self.root.mkdir()
        self.layout=API["ExtensionFolders"](self.root,self.data)
        self.opts={"tac_tagFile":"selected.csv","tac_extra.extraFile":"custom.csv"}

    def fork(self,folder,name):
        directory=folder/"extensions"/name
        (directory/"scripts").mkdir(parents=True)
        (directory/"scripts/tag_autocomplete_helper.py").write_text("raise RuntimeError('must never be imported')")
        tags=directory/"tags"
        tags.mkdir()
        (tags/"selected.csv").write_text("sample_tag,0,7,fixture_alias\n",encoding="utf8")
        (tags/"other.csv").write_text("unused_file,0,999\n",encoding="utf8")
        (tags/"custom.csv").write_text("custom_tag,0,,\n",encoding="utf8")
        return directory

    def test_custom_data_dir_renamed_fork_and_selected_dictionaries(self):
        ext=self.fork(self.data,"my-renamed-fork")
        self.fork(self.root,"wrong-data-dir")
        files=API["NativeFiles"](self.root,lambda:self.opts,extensions=self.layout)
        self.assertEqual(files.suggestions("fixture"),["sample_tag"])
        self.assertEqual(files.suggestions("unused"),[])
        self.opts["tac_tagFile"]="other.csv"
        self.assertEqual(files.suggestions("unused"),["unused_file"])
        self.assertEqual([p.name for _,p,_ in files.tag_inputs(self.opts)],["custom.csv","other.csv"])
        for disabled in ({"disabled_extensions":[ext.name.upper()]},{"disable_all_extensions":"extra"},{"_cli_disable_all":True}):
            self.assertEqual(self.layout.active("tags",self.opts|disabled),None)

    def test_multiple_forks_are_never_chosen_by_folder_order(self):
        a=self.fork(self.data,"a");b=self.fork(self.data,"b")
        with self.assertRaises(HTTPException) as exc:self.layout.active("tags",{})
        self.assertEqual(exc.exception.status_code,409)
        (self.root/"tmp").mkdir()
        marker=self.root/"tmp/tagAutocompletePath.txt"
        marker.write_text(str(b/"tags"),encoding="utf8")
        self.assertEqual(self.layout.active("tags",{}),b)
        self.assertEqual(self.layout.active("tags",{"disabled_extensions":["b"]}),a)
        self.assertEqual(API["ExtensionFolders"](self.root,self.data,tag_extension="a").active("tags",{}),a)
        marker.write_text(str(self.root.parent),encoding="utf8")
        with self.assertRaises(HTTPException):self.layout.active("tags",{})

    def test_model_roots_and_catalog_without_tag_extension(self):
        default_data,defaults=API["runtime_layout"](self.root)
        self.assertEqual((default_data,defaults),(self.root,(self.root/"models/Lora",)))
        data,roots=API["runtime_layout"](self.root,self.data,["separate/Lora","additional"])
        self.assertEqual(data,self.data)
        self.assertEqual(roots,(self.root/"separate/Lora",self.root/"additional"))
        custom=API["runtime_layout"](self.root,configured_loras=["chosen"])[1]
        self.assertEqual(custom,(self.root/"chosen",))
        for i,r in enumerate(roots):
            r.mkdir(parents=True)
            (r/f"model{i}.safetensors").write_bytes(b"")
        files=API["NativeFiles"](self.root,lambda:{},lambda:roots,self.layout)
        rows=files.loras()["items"]
        self.assertEqual([r["path"] for r in rows],["model0.safetensors","[2]/model1.safetensors"])

    def test_missing_api_extension_settings_fall_back_to_the_actual_data_config(self):
        folder=self.fork(self.data,"configured-fork")
        config=self.data/"config.json"
        settings={"tac_tagFile":"selected.csv","tac_extra.extraFile":"None","tac_replaceUnderscores":False,
                  "disabled_extensions":[],"unused_secret_fixture":"ignored"}
        config.write_text(json.dumps(settings),encoding="utf-8-sig")
        def load(live):
            runtime=API["RuntimeOptions"]("http://127.0.0.1:1",config)
            def read(_,route):
                self.assertEqual(route,"/sdapi/v1/options")
                return live
            with patch.dict(API["RuntimeOptions"].__call__.__globals__,{"local_json":read}):
                merged=runtime()
            self.assertNotIn("unused_secret_fixture",merged)
            return API["NativeFiles"](self.root,lambda:merged,extensions=self.layout)
        files=load({"tac_tagFile":None,"tac_replaceUnderscores":True})
        self.assertEqual(files.suggestions("sample_tag",True),[{"name":"sample_tag","insert":"sample tag","count":7}])
        self.assertEqual(load({"tac_tagFile":"other.csv"}).suggestions("unused"),["unused_file"])
        self.assertEqual(load({"tac_tagFile":{}}).suggestions("fixture"),["sample_tag"])
        config.write_text(json.dumps(settings|{"disabled_extensions":[folder.name]}),encoding="utf8")
        self.assertEqual(load({}).suggestions("sample"),[])

    def test_active_preset_follows_changes_and_gradio_fallback(self):
        app=FastAPI()
        API["install"](app,self.root,lambda:{}, "http://127.0.0.1:1")
        route=next(r.endpoint for r in app.routes if r.path.endswith("/generation-defaults"))
        def node(key,value):return {"props":{"elem_id":key,"value":value}}
        gr={"components":[node("txt2img_width",832),node("txt2img_hires_steps",9),node("img2img_height",768)]}
        opts={"sdxl_t2i_width":1216,"flux_t2i_width":0,"sdxl_t2i_hr_step":8,"flux_t2i_hr_step":0,
              "sdxl_i2i_height":1152,"sd_model_checkpoint":"fallback"}
        for active,width,hr,height in [("sdxl",1216,8,1152),("flux",832,9,768),("",832,9,768)]:
            opts["forge_preset"]=active
            with patch.dict(API["install"].__globals__,{"local_json":lambda b,p:gr if p=="/config" else opts}):
                data=route()
            self.assertEqual(data["active"],active)
            self.assertEqual(data["txt2img"]["width"],width);self.assertEqual(data["txt2img"]["hr_second_pass_steps"],hr)
            self.assertEqual(data["img2img"]["height"],height);self.assertEqual(data["checkpoint"],"fallback")
            self.assertEqual(data["txt2img"]["prompt"],"")


if __name__=="__main__":unittest.main()
