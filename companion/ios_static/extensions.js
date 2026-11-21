import {button, element, field, group, section} from "./ui.js";

const names = {ad_tab_enable:"このスロットを使う",ad_model:"検出モデル",ad_confidence:"検出の信頼度",ad_prompt:"追加プロンプト",ad_negative_prompt:"追加ネガティブ",ad_mask_blur:"マスクのぼかし",ad_denoising_strength:"変化の強さ",ad_dilate_erode:"マスクの縮小・拡張",ad_inpaint_only_masked:"マスク部分だけを処理",ad_inpaint_only_masked_padding:"マスクの余白"};
const adKey = "ADetailer";
function adSlot(props, controls, active, saved) {
  const model = {};
  for (const key of Object.keys(props).filter(k => k.startsWith("ad_"))) {
    const value = saved?.[key] ?? controls?.[key] ?? active?.[key] ?? props[key].default;
    if (value != null) model[key] = value;
  }
  return model;
}
export function mountExtensions(panel, ctx) {
  const {data, draft, mode, patch} = ctx;
  const all = data.scripts.filter(s => s.is_img2img === (mode === "img2img"));
  const chosen = structuredClone(draft._extensions || {});
  const save = () => patch({_extensions: chosen});
  const adScript = all.find(s => s.name.toLowerCase() === "adetailer");
  if (adScript) {
    if (!data.adSchema?.properties || !data.models || !data.adControls) {
      panel.append(section("ADetailer", element("p","PCのADetailer設定を取得できません。接続を確認して再取得してください。","quiet")));
    } else {
      const properties = data.adSchema.properties;
      const saved = chosen[adKey] || {};
      const slots = adScript.args.slice(2).map((arg,i) => adSlot(properties, data.adControls.profiles?.[mode]?.[i], arg.value, saved.slots?.[i]));
      const ad = {enabled: Boolean(saved.enabled), skip: saved.skip ?? Boolean(adScript.args[1]?.value), slots};
      const persist = () => { chosen[adKey] = ad; save(); };
      const box = section("ADetailer");
      box.append(field("生成時にADetailerを使用", ad.enabled, v => { ad.enabled = v; persist(); }, {type:"checkbox"}));
      if (mode === "img2img") box.append(field("最初のimg2imgをスキップ", ad.skip, v => { ad.skip = v; persist(); }, {type:"checkbox"}));
      slots.forEach((slot,index) => {
        const tab = group("スロット " + (index+1));
        const keys = [...new Set(["ad_tab_enable","ad_model","ad_prompt","ad_negative_prompt","ad_confidence",...Object.keys(properties)])];
        for (const key of keys.filter(k => k.startsWith("ad_") && properties[k])) {
          const spec = properties[key], ui = data.adControls.controls?.[key] || {};
          const choices = key === "ad_model" ? ["None",...data.models.ad_model] : ui.choices || spec.enum;
          const type = spec.type === "boolean" ? "checkbox" : ["number","integer"].includes(spec.type) ? "number" : "text";
          if (["array","object"].includes(spec.type)) continue;
          tab.append(field(names[key] || spec.title || key, slot[key], v => { slot[key] = v; persist(); }, {
            choices:choices?.length ? choices : undefined, type, long:key.endsWith("prompt"),
            min:ui.minimum ?? spec.minimum, max:ui.maximum ?? spec.maximum, step:ui.step ?? (spec.type === "integer" ? 1 : "any")
          }));
        }
        box.append(tab);
      });
      panel.append(box);
    }
  }
  const list = section("生成スクリプト");
  const filter = element("input");
  filter.type = "search"; filter.placeholder = "拡張名を検索"; filter.setAttribute("aria-label", "拡張名を検索");
  const items = element("div");
  function render() {
    items.replaceChildren();
    for (const script of all.filter(s => !["adetailer","sampler","seed"].includes(s.name.toLowerCase()) && s.name.toLowerCase().includes(filter.value.toLowerCase()))) {
      const supported = script.args?.every(a => ["number","string","boolean"].includes(typeof a.value) && (!a.choices || a.choices.every(c => typeof c === "string")));
      if (!supported || (script.is_alwayson && !script.args.length)) continue;
      const box = group(script.name), key = "script:" + script.name;
      const signature = JSON.stringify(script.args.map(a => [a.label, typeof a.value, a.choices]));
      const existing = chosen[key];
      const rec = existing?.signature === signature ? existing : {signature, always:script.is_alwayson, args:script.args.map(a => a.value)};
      box.append(field("次の生成で使う", Boolean(existing && existing.signature === signature), value => {
        if (value) {
          if (!rec.always) {
            for (const k of Object.keys(chosen)) if (k.startsWith("script:") && !chosen[k].always) delete chosen[k];
          }
          chosen[key] = rec;
        } else delete chosen[key];
        save(); render();
      }, {type:"checkbox"}));
      if (existing && existing.signature === signature) {
        box.open = true;
        script.args.forEach((a,i) => box.append(field(a.label || "項目 "+(i+1), rec.args[i], v => { rec.args[i] = v; save(); }, {
          choices:a.choices?.length ? a.choices : undefined, type:typeof a.value === "boolean" ? "checkbox" : typeof a.value === "number" ? "number" : "text",
          min:a.minimum, max:a.maximum, step:a.step || "any"
        })));
      }
      items.append(box);
    }
    if (!items.childElementCount) items.append(element("p","操作できる拡張が見つかりません。","quiet"));
  }
  filter.addEventListener("input",render); render(); list.append(filter,items); panel.append(list);
  panel.append(button("PCから再取得", ctx.reload));
}

export function applyExtensions(payload, draft, data, mode) {
  const saved = draft._extensions || {};
  const enabled = saved[adKey];
  if (enabled?.enabled) {
    const slots = enabled.slots;
    if (!data.adSchema || !slots.some(s => s.ad_tab_enable !== false && s.ad_model && s.ad_model !== "None")) throw Error("ADetailerのスロットと検出モデルを確認してください");
    for (const s of slots) {
      for (const [min,max] of [["ad_mask_min_ratio","ad_mask_max_ratio"],["ad_controlnet_guidance_start","ad_controlnet_guidance_end"]]) {
        if (s[min] != null && s[max] != null && Number(s[min]) > Number(s[max])) throw Error("ADetailerの範囲を確認してください");
      }
    }
    payload.alwayson_scripts[adKey] = {args:[true,mode === "img2img" && enabled.skip,...slots]};
  }
  for (const [key,record] of Object.entries(saved).filter(([k]) => k.startsWith("script:"))) {
    const name = key.slice(7);
    const script = data.scripts.find(s => s.name === name && s.is_img2img === (mode === "img2img"));
    if (!script || record.signature !== JSON.stringify(script.args.map(a => [a.label,typeof a.value,a.choices]))) throw Error(name + "の設定が変わりました。拡張を再取得してください");
    if (record.always) payload.alwayson_scripts[name] = {args:record.args};
    else { payload.script_name = name; payload.script_args = record.args; }
  }
}
