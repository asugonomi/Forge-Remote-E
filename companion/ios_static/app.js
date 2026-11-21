import {element,button,notify,section,row,group} from "./ui.js";
import {mountEditor} from "./editor.js";
import {mountGallery} from "./gallery.js";
import {mountExtensions} from "./extensions.js";
import {mountGeneration} from "./generation.js";
import {Jobs} from "./jobs.js";

const $ = s => document.querySelector(s);
const tabs = [
  ["gen","生成","M12 3v18 M3 12h18"],
  ["prompt","プロンプト","M4 16l-1 5 5-1L20 8l-4-4z M13 7l4 4"],
  ["gallery","画像","M3 4h18v16H3z M4 17l5-5 4 4 3-4 4 5 M8 9h.01"],
  ["queue","キュー","M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01"],
  ["extensions","拡張","M4 6h16 M4 12h16 M4 18h16 M8 4v4 M16 10v4 M10 16v4"],
];
const blank = {prompt:"",negative_prompt:"",width:1024,height:1024,steps:20,cfg_scale:1,seed:-1,
  distilled_cfg_scale:3,sampler_name:"Euler",scheduler:"Simple",batch_size:1,n_iter:1,
  denoising_strength:.45,enable_hr:false,hr_second_pass_steps:0,hr_scale:2,hr_upscaler:"Latent",hr_cfg:1};
let mode="txt2img",tab="gen",csrf="",editor=null,jobs=null,inputImage=null,form=null,saveTimer,savePromise,started=false;
let prefs={mode:"txt2img",drafts:{},defaults:{},changed:0};
const data={profiles:null,samplers:[],schedulers:[],scripts:[],sizes:null};
let validProfile=false,extensionLoad=null,screenRevision=0,savingFailed=false,deviceKey="";
let secret = new URLSearchParams(location.hash.slice(1)).get("pair");
if (location.hash) history.replaceState(null,"",location.pathname);
const wide = matchMedia("(min-width:680px)");
function online(value) {
  $("#connection").dataset.online=String(value);
  $("#connection span").textContent=value?"PCと接続済み":"再接続を待機";
}
function errorLabel(status,message) {
  return ({401:"PCのQRを読み取って、この端末を登録してください。",403:"登録情報またはTailscaleの接続先を確認してください。",
    409:"状態が変わりました。画面を更新して確認してください。",413:"画像または入力内容が大きすぎます。",429:"待機列が上限に達しています。",502:"PCのForgeに接続できません。起動状態を確認してください。",503:"PCが処理中です。少し待ってください。"}[status]) || message || ("PCの応答 "+status);
}
async function request(path,opts={}) {
  const target=new URL(path,location.origin);
  if(target.origin!==location.origin || !path.startsWith("/") || path.startsWith("//"))throw Error("接続先が一致しません");
  const headers=new Headers(opts.headers||{});
  if(opts.method && opts.method!=="GET" && opts.method!=="HEAD" && csrf)headers.set("X-CSRF-Token",csrf);
  const response=await fetch(path,{...opts,headers,credentials:"same-origin",cache:"no-store",redirect:"error"});
  if(!response.ok){
    let message;try{ const body=await response.json();message=body.detail || body.error; }catch{}
    const reason=Error(errorLabel(response.status,typeof message==="string"?message:""));reason.status=response.status;throw reason;
  }
  return response;
}
async function json(path,body) {
  let encoded=JSON.stringify(body);
  // JSON numbers in Forge can be 64-bit integers. Preserve a large seed without rounding it in JS.
  if (path==="/api/jobs" && typeof body?.payload?.seed==="string") {
    const {seed,...fields}=body.payload;
    if(!/^-?\d{1,19}$/.test(seed))throw Error("Seedを整数で指定してください");
    encoded='{"id":'+JSON.stringify(body.id)+',"mode":'+JSON.stringify(body.mode)+',"payload":{"seed":'+seed+','+JSON.stringify(fields).slice(1)+'}';
  }
  return (await request(path,body===undefined?{}:{method:"POST",headers:{"Content-Type":"application/json"},body:encoded})).json();
}
const localKey = part => "forgeRemoteIos."+deviceKey+"."+part;
function deviceLocal() { try{return JSON.parse(localStorage.getItem(localKey("drafts"))||"null");}catch{return null;} }
function sane(p) {return p && p.drafts && typeof p.drafts==="object" && p.defaults && typeof p.defaults==="object" && Number.isFinite(p.changed);}
function save() {
  prefs.changed=Math.max(Date.now(),prefs.changed+1);prefs.mode=mode;
  try{localStorage.setItem(localKey("drafts"),JSON.stringify(prefs));}catch{if(!savingFailed)notify("端末の保存容量を確認してください");}
  clearTimeout(saveTimer);saveTimer=setTimeout(sync,450);
}
async function sync() {
  if(savePromise || !started || !prefs.changed)return;
  const stamp=prefs.changed;
  savePromise=json("/api/preferences",prefs);
  try { await savePromise;savingFailed=false; }
  catch { if(!savingFailed)notify("下書きは端末に保存しました。PCへの同期は再接続後に行います。");savingFailed=true; }
  finally{savePromise=null;if(prefs.changed!==stamp)setTimeout(sync,100);}
}
function patch(change) { Object.assign(form,change);prefs.drafts[mode]={...form};save(); }
function selectForm() { form={...blank,...data.profiles?.[mode],...(prefs.drafts[mode]||prefs.defaults[mode]||{})}; }
function useImage(src) { inputImage=src;setMode("img2img",false);navigate("gen"); }
function restore(values,info) {patch({...values,infotext:info});navigate("gen");notify("生成設定を復元しました");}
function setMode(next,draw=true){mode=next;selectForm();save();if(draw)navigate(tab);}
async function loadMeta(){
  const paths=["/forge-remote/v1/generation-defaults","/sdapi/v1/samplers","/sdapi/v1/schedulers","/forge-remote/v1/size-presets"];
  const [profile,samplers,schedulers,sizes]=await Promise.allSettled(paths.map(p=>json(p)));
  validProfile=profile.status==="fulfilled" && Boolean(profile.value?.txt2img && profile.value?.img2img);
  if(validProfile)data.profiles=profile.value;
  data.samplers=(samplers.value||[]).map(s=>s.name);
  data.schedulers=(schedulers.value||[]).map(s=>s.label || s.name);
  data.sizes=sizes.value; selectForm();
}
async function loadExtensions(force=false){
  if(!extensionLoad || force)extensionLoad=(async()=>{
    data.scripts=await json("/sdapi/v1/script-info");
    if(data.scripts.some(s=>s.name.toLowerCase()==="adetailer")){
      const result=await Promise.allSettled(["/adetailer/v1/schema","/adetailer/v1/ad_model","/forge-remote/v1/ad-controls"].map(p=>json(p)));
      [data.adSchema,data.models,data.adControls]=result.map(v=>v.status==="fulfilled"?v.value:null);
    }
  })().catch(error=>{extensionLoad=null;throw error;});
  await extensionLoad;
}
async function enqueue(payload){
  if(!validProfile)throw Error("PCの初期値を読み直してください");
  const raw=JSON.stringify([mode,payload]);
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(raw))),n=>n.toString(16).padStart(2,"0")).join("");
  let previous;try{previous=JSON.parse(localStorage.getItem(localKey("pending"))||"null");}catch{}
  const id=previous?.hash===hash?previous.id:crypto.randomUUID();
  localStorage.setItem(localKey("pending"),JSON.stringify({id,hash}));
  await json("/api/jobs",{id,mode,payload});
  localStorage.removeItem(localKey("pending"));
  jobs.selected=null;jobs.index=0;
  notify("PCの生成キューに追加しました");
  if(!wide.matches)navigate("queue");
  await jobs.refresh();
}
function context(){return {draft:form,mode,data,patch,request,json,notify,restore,useImage,setMode,navigate,enqueue,image:inputImage,
  suggestionsHost:wide.matches?$("#suggestions-wide"):undefined,onChange:patch,onRestore:restore,onUseImage:useImage};}
function settingPage(panel) {
  const install=section("ホーム画面に追加");
  install.append(element("p","Safariの共有ボタンから「ホーム画面に追加」を選びます。「Webアプリとして開く」が表示されたらオンにしてください。"));
  if(navigator.standalone || matchMedia("(display-mode: standalone)").matches)install.append(element("p","ホーム画面のアイコンから起動しています。","quiet"));
  panel.append(install,section("生成の初期値",element("p",mode==="txt2img"?"テキストから":"画像から","quiet"),row(
    button("現在の設定を初期値に保存",()=>{prefs.defaults[mode]=structuredClone(form);save();notify("初期値を保存しました");}),
    button("端末の初期値を適用",()=>{if(!prefs.defaults[mode])throw Error("端末の初期値は未登録です");patch(prefs.defaults[mode]);notify("端末の初期値を適用しました");}),
    button("PCの初期値に戻す",async()=>{if(!confirm("このモードの下書きと端末の初期値を解除しますか？"))return;await loadMeta();if(!validProfile)throw Error("PCの設定を取得できません");delete prefs.drafts[mode];delete prefs.defaults[mode];selectForm();save();navigate("gen");})
  )),section("接続",element("p","このPCのTailscale接続を使用しています。","quiet"),button("PC設定を再取得",async()=>{validProfile=false;await loadMeta();if(!validProfile)throw Error("PCの設定を取得できません");notify("PC設定を再取得しました");})));
}
async function navigate(next) {
  tab=next;const rev=++screenRevision;
  editor?.destroy?.();editor=null;
  const panel=$("#panel");panel.replaceChildren();$("#desk").dataset.tab=tab;
  $("#workspace").scrollTop=0; window.scrollTo(0,0);
  for(const el of $("#navigation").children) el.setAttribute("aria-current",el.dataset.tab===tab?"page":"false");
  const title=element("div",null,"page-title");title.append(element("h1",({gen:"画像を生成",prompt:"プロンプト",gallery:"画像",queue:"生成キュー",extensions:"拡張",settings:"設定"})[tab]));
  if(tab==="gen" && data.profiles?.active)title.append(element("span",data.profiles.active,"quiet"));
  panel.append(title);
  try{
    if(tab==="gen"){
      if(!validProfile){panel.append(section(null,element("p","PCの初期値を取得できません。Forgeが起動しているか確認してください。"),button("再取得",async()=>{await loadMeta();navigate("gen");})));return;}
      if(form._extensions && Object.keys(form._extensions).length)await loadExtensions();
      if(rev===screenRevision)editor=mountGeneration(panel,context());
    } else if(tab==="prompt")editor=mountEditor(panel,context());
    else if(tab==="gallery")editor=mountGallery(panel,context());
    else if(tab==="queue")editor=jobs.attach(panel);
    else if(tab==="extensions"){
      const pending=element("p","PCの拡張を読み込み中…","quiet");panel.append(pending);
      await loadExtensions();if(rev!==screenRevision)return;pending.remove();
      const switcher=row(button("テキストから",()=>setMode("txt2img")),button("画像から",()=>setMode("img2img")));
      switcher.children[mode==="img2img"?1:0].classList.add("primary");panel.append(switcher);
      mountExtensions(panel,{...context(),reload:async()=>{await loadExtensions(true);navigate("extensions");}});
    } else settingPage(panel);
  }catch(e){if(rev===screenRevision)panel.append(section(null,element("p",e.message,"error"),button("再試行",()=>navigate(tab))));}
}
function buildNavigation(){
  for(const [id,label,path] of tabs){
    const btn=button("",()=>navigate(id));btn.dataset.tab=id;
    const svg=document.createElementNS("http://www.w3.org/2000/svg","svg"),p=document.createElementNS(svg.namespaceURI,"path");
    svg.setAttribute("viewBox","0 0 24 24");svg.setAttribute("aria-hidden","true");p.setAttribute("d",path);svg.append(p);btn.append(svg,element("span",label));$("#navigation").append(btn);
  }
}
function pairingUI(message) {
  const area=$("#setup");area.hidden=false;area.replaceChildren(element("h1","このiPhoneを登録"));
  area.append(element("p",message || "PCで「Forge Remote iOS」を開き、表示されたQRコードをiPhoneのカメラで読み取ります。"));
  if(secret)area.append(button("登録を再試行",boot,"primary"));
  const input=element("input");input.type="password";input.autocomplete="off";input.placeholder="登録用リンク";input.setAttribute("aria-label","登録用リンク");
  area.append(group("QRを読み取れないとき",input,button("この端末を登録",async()=>{
    const url=new URL(input.value); if(url.origin!==location.origin)throw Error("このPCの登録用リンクを貼り付けてください");
    secret=new URLSearchParams(url.hash.slice(1)).get("pair");if(!secret)throw Error("登録コードがありません");
    input.value="";await boot();
  })));
}
async function boot(){
  try{
    let session;
    try{session=await json("/api/session");}catch(e){if(e.status!==401)throw e;}
    if(!session && secret){
      if(!/^[A-Za-z0-9_-]{43}$/.test(secret))throw Error("登録用QRをPCで再発行してください");
      session=await json("/api/pair",{secret,device_name:/iPad/.test(navigator.userAgent)?"iPad":"Safari"});
    }
    if(!session){pairingUI();return;}
    secret=null;csrf=session.csrf;online(true);
    const scope=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(csrf));
    deviceKey=Array.from(new Uint8Array(scope).slice(0,12),v=>v.toString(16).padStart(2,"0")).join("");
    const [remote]=await Promise.all([json("/api/preferences"),loadMeta()]);
    const local=deviceLocal();
    if(sane(remote))prefs=remote;
    if(sane(local) && local.changed>prefs.changed)prefs=local;
    mode=prefs.mode==="img2img"?"img2img":"txt2img";selectForm();
    $("#setup").hidden=true;$("#desk").hidden=false;$("#navigation").hidden=false;
    jobs=new Jobs({json,request,online,restore,useImage});started=true;buildNavigation();navigate("gen");jobs.start();
    if(sane(local) && local.changed>Number(remote.changed||0))sync();
    $("#connection").onclick=()=>navigate("settings");
    wide.addEventListener("change",()=>navigate(tab));
    window.addEventListener("online",()=>{if(savingFailed)sync();});
    window.addEventListener("pagehide",()=>{if(saveTimer)sync();});
    document.addEventListener("visibilitychange",()=>{if(document.hidden)sync();});
    if("serviceWorker" in navigator)navigator.serviceWorker.register("/sw.js").catch(()=>{});
  }catch(e){
    online(false);
    if(e.status===401 || e.status===403)pairingUI(e.status===403 && secret?"登録リンクが使えません。PCで新しいQRを表示して読み取ってください。":e.message);
    else {
      const area=$("#setup");area.hidden=false;
      area.replaceChildren(element("h1","PCに接続できません"),element("p","PCの起動状態とiPhoneのTailscaleを確認してください。"),
        button("再接続",boot,"primary"));
    }
  }
}
boot();
