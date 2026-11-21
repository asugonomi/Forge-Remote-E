import {button, element, field, fields, group, notify, row, section, slider} from "./ui.js";
import {mountQuickPrompt} from "./editor.js";
import {forgeImageData, showFullscreen} from "./gallery.js";
import {applyExtensions} from "./extensions.js";

const bounds = {width:[64,4096],height:[64,4096],steps:[1,150],batch_size:[1,8],n_iter:[1,100],cfg_scale:[0,30],distilled_cfg_scale:[0,30]};
const names = {width:"幅",height:"高さ",steps:"ステップ数",batch_size:"バッチサイズ",n_iter:"バッチ回数",cfg_scale:"CFG",distilled_cfg_scale:"Shift / Distilled CFG"};
export function generationBody(draft, mode, data, image) {
  const body = {prompt:draft.prompt || "",negative_prompt:draft.negative_prompt || "",sampler_name:draft.sampler_name,
    scheduler:draft.scheduler,alwayson_scripts:{},send_images:true,save_images:true,override_settings_restore_afterwards:true};
  for (const [key,[low,high]] of Object.entries(bounds)) {
    const v = Number(draft[key]);
    const whole = !["cfg_scale","distilled_cfg_scale"].includes(key);
    if (draft[key] == null || draft[key] === "" || !Number.isFinite(v) || v<low || v>high || (whole && !Number.isInteger(v))) throw Error(names[key] + "の値を確認してください");
    body[key] = v;
  }
  const seedText = String(draft.seed).trim();
  if (!/^-?\d{1,19}$/.test(seedText)) throw Error("Seedを整数で指定してください");
  const seed = BigInt(seedText);
  if (seed < -(1n<<63n) || seed >= (1n<<63n)) throw Error("Seedは64ビット整数の範囲で指定してください");
  body.seed = Number.isSafeInteger(Number(seed)) ? Number(seed) : seed.toString();
  if (draft.infotext) body.infotext = draft.infotext;
  if (mode === "img2img") {
    if (!image) throw Error("入力画像を選んでください");
    body.init_images = [image]; body.denoising_strength = draft.denoising_strength;
  } else {
    body.enable_hr = Boolean(draft.enable_hr);
    if (body.enable_hr) {
      for (const key of ["hr_scale","hr_upscaler","hr_cfg","hr_second_pass_steps","hr_distilled_cfg","denoising_strength"]) {
        if (draft[key] != null) body[key] = draft[key];
      }
      body.hr_additional_modules = draft.hr_additional_modules ?? ["Use same choices"];
      const last = (draft.infotext || "").trimEnd().split("\n").pop();
      const modules = [...last.matchAll(/(?:^|,\s*)Hires Module (\d+):\s*("(?:\\.|[^"\\])*"|[^,]*)/g)]
        .sort((a,b) => Number(a[1])-Number(b[1])).map(m => m[2].startsWith('"') ? JSON.parse(m[2]) : m[2].trim());
      if (modules.length) {
        const base = s => s.split(/[/\\]/).pop();
        const available = draft.hr_additional_module_choices || [];
        body.hr_additional_modules = modules[0] === "Built-in" ? [] : modules.map(s => {
          if (s === "Use same choices") return s;
          const exact = available.filter(v => base(v) === base(s));
          const found = exact.length ? exact : available.filter(v => base(v).replace(/\.[^.]+$/,"") === base(s));
          if (found.length !== 1) throw Error("Hiresモジュールを特定できません: " + s);
          return found[0];
        });
      }
    }
  }
  applyExtensions(body,draft,data,mode);
  return body;
}
export function mountGeneration(panel, ctx) {
  const {draft, mode, data, patch} = ctx;
  const set = k => v => patch({[k]:v});
  const segments = element("div",null,"segmented");
  for (const [key,name] of [["txt2img","テキストから"],["img2img","画像から"]]) {
    const tab = button(name, () => ctx.setMode(key));
    tab.setAttribute("aria-pressed", key === mode); segments.append(tab);
  }
  panel.append(segments);
  if (mode === "img2img") {
    const box = section("入力画像");
    const input = element("input"); input.type = "file"; input.accept = "image/*"; input.setAttribute("aria-label","入力画像を選択");
    input.onchange = async () => {
      try { if (input.files[0]) ctx.useImage(await forgeImageData(input.files[0])); } catch(e) { notify(e.message); }
    };
    if (ctx.image) {
      const img = element("img",null,"input-preview"); img.src = ctx.image; img.alt = "入力画像";
      box.append(img,button("全画面表示", () => showFullscreen(ctx.image,"入力画像")));
    }
    box.append(input,slider("変化の強さ",draft.denoising_strength,0,1,.01,set("denoising_strength"))); panel.append(box);
  }
  const edit = section(); const editorHost = element("div"); edit.append(editorHost); panel.append(edit);
  const controller = mountQuickPrompt(editorHost,{...ctx,draft,onChange:patch,onFullEditor:() => ctx.navigate("prompt")});
  const submit = button("生成キューに追加", async () => {
    if (submit.disabled) return;
    submit.disabled = true;
    try { await ctx.enqueue(generationBody(draft,mode,data,ctx.image)); }
    finally { submit.disabled = false; }
  },"primary big full");
  panel.append(submit);
  const width = field("幅 px",draft.width,set("width"),{type:"number",min:64,max:4096});
  const height = field("高さ px",draft.height,set("height"),{type:"number",min:64,max:4096});
  function dimensions(w,h) {
    patch({width:w,height:h}); width.querySelector("input").value=w; height.querySelector("input").value=h;
  }
  const ar = group("サイズプリセット");
  const ratios = data.sizes?.ratios || [], sizes = data.sizes?.resolutions || [];
  const arOpts = draft._aspect || {reverse:false,one_dimension:false,locked:false,precision:64};
  const storeAspect = (key,v) => { arOpts[key]=v; patch({_aspect:arOpts}); };
  ar.append(row(field("比率を反転",arOpts.reverse,v=>storeAspect("reverse",v),{type:"checkbox"}),
    field("一辺基準",arOpts.one_dimension,v=>storeAspect("one_dimension",v),{type:"checkbox"}),
    field("基準サイズを固定",arOpts.locked,v=>{arOpts.average=Math.round((Number(draft.width)+Number(draft.height))/2);storeAspect("locked",v);},{type:"checkbox"})),
    field("丸め単位 px",arOpts.precision,v=>storeAspect("precision",v),{type:"number",min:4,max:128,step:4}));
  const ratioButtons = row(), sizeButtons = row();
  for (const ratio of ratios) ratioButtons.append(button(ratio.label,async () => {
    const average = arOpts.locked && arOpts.average || Math.round((Number(draft.width)+Number(draft.height))/2);
    const reply = await ctx.json("/forge-remote/v1/size-presets/ratio", {
      ratio:ratio.label,width:Number(draft.width),height:Number(draft.height),average,precision:arOpts.precision,
      reverse:arOpts.reverse,one_dimension:arOpts.one_dimension
    });
    dimensions(reply.width,reply.height);
  }));
  for (const s of sizes) sizeButtons.append(button(s.label,() => dimensions(s.width,s.height)));
  ar.append(ratioButtons,sizeButtons);
  if (!sizes.length && !ratios.length) ar.append(element("p","PCのサイズプリセットは未設定です。幅と高さを入力できます。","quiet"));
  const settings = section("生成設定", fields(width,height),row(button("縦横を交換",()=>dimensions(draft.height,draft.width))),ar,
    slider("ステップ数",draft.steps,1,150,1,set("steps")),slider("CFG",draft.cfg_scale,0,30,.1,set("cfg_scale")),
    fields(field("サンプラー",draft.sampler_name,set("sampler_name"),{choices:data.samplers}),field("スケジュール",draft.scheduler,set("scheduler"),{choices:data.schedulers})),
    field("Seed（ランダムは −1）",draft.seed,set("seed")));
  const extra = group("バッチ・詳細設定", fields(field("バッチサイズ",draft.batch_size,set("batch_size"),{type:"number",min:1,max:8}),field("バッチ回数",draft.n_iter,set("n_iter"),{type:"number",min:1,max:100})),
    slider("Shift / Distilled CFG",draft.distilled_cfg_scale,0,30,.1,set("distilled_cfg_scale")));
  if(mode === "txt2img") extra.append(field("Hires. fix",draft.enable_hr,set("enable_hr"),{type:"checkbox"}),
    fields(field("拡大倍率",draft.hr_scale,set("hr_scale"),{type:"number",min:1,max:4,step:.05}),field("アップスケーラー",draft.hr_upscaler,set("hr_upscaler"))),
    fields(field("Hires ステップ",draft.hr_second_pass_steps,set("hr_second_pass_steps"),{type:"number",min:0,max:150}),field("Hires CFG",draft.hr_cfg,set("hr_cfg"),{type:"number",min:0,max:30,step:.1})),
    slider("Hires 変化の強さ",draft.denoising_strength,0,1,.01,set("denoising_strength")));
  if(draft.infotext) extra.append(button("PNG Infoの追加設定を解除",()=>{patch({infotext:""});ctx.navigate("gen");}));
  settings.append(extra); panel.append(settings); return controller;
}
