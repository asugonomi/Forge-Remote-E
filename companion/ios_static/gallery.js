import {element,action,labeled,sheet,readJson} from './editor.js';

const gallery='/forge-remote/v1/gallery/', index='/forge-remote/v1/gallery-index/';
const keyOK=key=>typeof key==='string' && /^[0-9a-f]{32}$/.test(key);
const maxUpload=32*1024*1024, metadataLimit=1024*1024;

export function parseGenerationInfo(raw) {
  if(typeof raw!=='string')return null;
  const lines=raw.replace(/\r\n?/g,'\n').split('\n');
  let split=lines.length-1;
  while(split>=0 && !/^Steps:\s*\d+(?:\s*,|\s*$)/.test(lines[split]))split--;
  if(split<0)return null;
  const header=lines.slice(0,split).join('\n').trimEnd();
  const neg=/(?:^|\n)Negative prompt:\s?/.exec(header);
  const patch={prompt:neg?header.slice(0,neg.index):header,
    negative_prompt:neg?header.slice(neg.index+neg[0].length):''};
  const items=new Map();
  let begin=0, quoted=false, depth=0, escaped=false;
  const tail=lines.slice(split).join('\n');
  function take(end){
    const pair=tail.slice(begin,end).match(/^\s*([^:,\n]+):\s*([\s\S]*)$/);
    if(pair) items.set(pair[1].trim(),pair[2].trim());
    begin=end+1;
  }
  for(let i=0;i<tail.length;i++){
    const c=tail[i];
    if(escaped){escaped=false;continue;}if(c==='\\'){escaped=true;continue;}
    if(c==='"'){quoted=!quoted;continue;}if(quoted)continue;
    if('([{'.includes(c))depth++;
    else if(')]}'.includes(c))depth=Math.max(0,depth-1);
    else if(c===',' && !depth)take(i);
  }
  take(tail.length);
  const steps=Number(items.get('Steps'));
  if(!Number.isInteger(steps)||steps<1)return null;
  patch.steps=steps;
  for(const [label,field] of Object.entries({'Sampler':'sampler_name','Schedule type':'scheduler','Hires upscaler':'hr_upscaler','Hires sampler':'hr_sampler_name','Hires schedule type':'hr_scheduler'})){
    if(items.get(label))patch[field]=items.get(label);
  }
  for(const [label,field] of Object.entries({'CFG scale':'cfg_scale','Denoising strength':'denoising_strength','Hires upscale':'hr_scale','Hires steps':'hr_second_pass_steps','Hires CFG Scale':'hr_cfg','Distilled CFG Scale':'distilled_cfg_scale','Shift':'distilled_cfg_scale'})){
    const v=items.get(label);if(v && Number.isFinite(Number(v)) && !(field in patch))patch[field]=Number(v);
  }
  const seed=items.get('Seed');
  if(/^-?\d+$/.test(seed||''))patch.seed=Number.isSafeInteger(Number(seed))?Number(seed):seed;
  for(const [label,x,y] of [['Size','width','height'],['Hires resize','hr_resize_x','hr_resize_y']]){
    const m=/^(\d+)\s*x\s*(\d+)$/i.exec(items.get(label)||'');
    if(m){patch[x]=Number(m[1]);patch[y]=Number(m[2]);}
  }
  patch.enable_hr=Array.from(items.keys()).some(k=>k.startsWith('Hires '));
  return patch;
}

async function inflate(data) {
  if(!globalThis.DecompressionStream)return null;
  const reader=new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate')).getReader();
  let size=0;const chunks=[];
  try {
    while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;
      if(size>metadataLimit){await reader.cancel();return null;}chunks.push(value);}
  }finally{reader.releaseLock();}
  const out=new Uint8Array(size);let p=0;for(const c of chunks){out.set(c,p);p+=c.length;}return out;
}
export async function localPngInfo(blob) {
  const header=new Uint8Array(await blob.slice(0,8).arrayBuffer());
  if(header.join(',')!=='137,80,78,71,13,10,26,10')return '';
  let offset=8;
  for(let n=0;offset+12<=blob.size && n<8192;n++){
    const h=new Uint8Array(await blob.slice(offset,offset+8).arrayBuffer());
    const size=new DataView(h.buffer).getUint32(0),kind=String.fromCharCode(...h.subarray(4));
    if(size>blob.size-offset-12)return '';
    if(['tEXt','iTXt','zTXt'].includes(kind) && size>11 && size<=metadataLimit){
      const d=new Uint8Array(await blob.slice(offset+8,offset+8+size).arrayBuffer());
      if(d.indexOf(0)===10 && String.fromCharCode(...d.subarray(0,10))==='parameters'){
        let start=11,compressed=false;
        if(kind==='iTXt'){
          if(d.length<15 || d[11]>1 || d[12]!==0)return '';
          compressed=d[11]===1;start=13;
          for(let k=0;k<2;k++){const z=d.indexOf(0,start);if(z<0)return '';start=z+1;}
        }else if(kind==='zTXt'){if(d[start++]!==0)return '';compressed=true;}
        let content=d.subarray(start);
        if(compressed){try{content=await inflate(content);}catch{return '';}if(!content)return '';}
        if(kind==='iTXt')return new TextDecoder('utf-8').decode(content);
        let s='';for(let i=0;i<content.length;i+=4096)s+=String.fromCharCode(...content.subarray(i,i+4096));
        return s;
      }
    }
    if(kind==='IEND')break;
    offset+=size+12;
  }
  return '';
}

function validImageSource(src) {
  if(typeof src!=='string')return false;
  if(/^data:image\/(?:png|jpeg|webp|gif|avif);base64,/i.test(src))return true;
  try{const u=new URL(src,location.href);return u.origin===location.origin && ['https:','http:','blob:'].includes(u.protocol);}
  catch{return false;}
}
export function showFullscreen(src,alt='画像') {
  if(!validImageSource(src))throw new Error('画像を表示できません');
  const p=sheet('画像を拡大'), viewport=element('div','fe-zoom-view'), img=element('img');
  p.el.classList.add('fe-fullscreen');p.body.append(viewport);viewport.append(img);img.alt=alt;img.src=src;img.draggable=false;
  let scale=1,x=0,y=0,previous=null,lastTap=0, excursion=0,down=null;
  const pointers=new Map();
  function draw(){
    const maxX=Math.max(0,(img.clientWidth*scale-viewport.clientWidth)/2), maxY=Math.max(0,(img.clientHeight*scale-viewport.clientHeight)/2);
    x=Math.min(maxX,Math.max(-maxX,x));y=Math.min(maxY,Math.max(-maxY,y));
    img.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';
  }
  function setZoom(value, anchorX=0, anchorY=0){
    const next=Math.min(8,Math.max(1,value));
    x=anchorX-(anchorX-x)*next/scale;y=anchorY-(anchorY-y)*next/scale;scale=next;draw();
  }
  const metric=()=>{
    const [a,b]=[...pointers.values()];
    if(!a)return null;
    return b?{x:(a.x+b.x)/2,y:(a.y+b.y)/2,d:Math.hypot(a.x-b.x,a.y-b.y)}:{...a,d:0};
  };
  viewport.addEventListener('pointerdown',e=>{
    if(!pointers.size){down={x:e.clientX,y:e.clientY};excursion=0;}
    else excursion=999;
    viewport.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});previous=metric();
  });
  viewport.addEventListener('pointermove',e=>{
    if(!pointers.has(e.pointerId))return;
    pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});const now=metric();
    excursion=Math.max(excursion,Math.hypot(e.clientX-down.x,e.clientY-down.y));
    if(now.d && previous.d){const r=viewport.getBoundingClientRect();setZoom(scale*now.d/previous.d,previous.x-r.left-r.width/2,previous.y-r.top-r.height/2);}
    x+=now.x-previous.x;y+=now.y-previous.y;draw();previous=now;
  });
  function end(e){
    if(!pointers.delete(e.pointerId))return;previous=metric();
    if(e.type==='pointerup' && excursion<8 && !pointers.size){
      if(performance.now()-lastTap<320){setZoom(scale>1?1:2);lastTap=0;}else lastTap=performance.now();
    }
  }
  viewport.addEventListener('pointerup',end);viewport.addEventListener('pointercancel',end);
  const tools=element('div','fe-toolbar');tools.append(action('縮小',()=>setZoom(scale/1.5)),action('等倍に戻す',()=>setZoom(1)),action('拡大',()=>setZoom(scale*1.5)));
  p.body.append(tools);return p;
}

function dataUrl(blob){
  if(blob.size>maxUpload)throw new Error('32 MiB以下の画像を選んでください。');
  return new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=()=>reject(new Error('画像を読み込めません。'));r.readAsDataURL(blob);});
}
async function readOriginal(response) {
  if(!response.ok)throw new Error('PCの元画像を取得できません。');
  const tooLarge=()=>new Error('32 MiB以下の画像を選んでください。');
  if(Number(response.headers.get('Content-Length'))>maxUpload){await response.body?.cancel();throw tooLarge();}
  if(!response.body){const blob=await response.blob();if(blob.size>maxUpload)throw tooLarge();return blob;}
  const reader=response.body.getReader(),chunks=[];let count=0;
  try {
    while(true){
      const part=await reader.read();if(part.done)break;
      count+=part.value.byteLength;if(count>maxUpload){await reader.cancel();throw tooLarge();}
      chunks.push(part.value);
    }
  } finally {reader.releaseLock();}
  return new Blob(chunks,{type:response.headers.get('Content-Type')?.split(';')[0] || ''});
}
async function photoForForge(blob) {
  if(/^image\/(?:png|jpeg|webp|gif|avif)$/i.test(blob.type))return blob;
  // iOS may return the original HEIC from Photos or Files. Use Safari's own
  // decoder and convert only when the user actually chooses img2img.
  const url=URL.createObjectURL(blob),photo=new Image();
  try {
    photo.src=url;await photo.decode();
    if(!photo.naturalWidth || !photo.naturalHeight)throw new Error();
    const scale=Math.min(1,4096/Math.max(photo.naturalWidth,photo.naturalHeight));
    const canvas=document.createElement('canvas');
    canvas.width=Math.max(1,Math.round(photo.naturalWidth*scale));canvas.height=Math.max(1,Math.round(photo.naturalHeight*scale));
    const context=canvas.getContext('2d');if(!context)throw new Error();
    context.drawImage(photo,0,0,canvas.width,canvas.height);
    const png=await new Promise(resolve=>canvas.toBlob(resolve,'image/png'));
    if(!png)throw new Error();return png;
  } catch {throw new Error('この画像形式を読み込めません。写真をJPEGまたはPNGで選んでください。');}
  finally{URL.revokeObjectURL(url);}
}
export async function forgeImageData(blob) {
  if (blob.size > maxUpload) throw new Error('32 MiB以下の画像を選んでください。');
  return dataUrl(await photoForForge(blob));
}
function makeOption(text,value){const o=element('option','',text);o.value=value;return o;}
export function mountGallery(container,{request,onRestore=()=>{},onUseImage=()=>{},notify=()=>{}}) {
  const root=element('section','fe-gallery'), tabs=element('div','fe-toolbar'), controls=element('div','fe-toolbar'), cards=element('div','fe-grid');
  const label=element('p','fe-hint'), pages=element('div','fe-toolbar'), pageSize=24;
  let mode='pc',epoch=0,controller=null,days=null,day='',page=0,files=[],dead=false,panel=null,zoom=null,urls=[];
  label.setAttribute('role','status');
  const pc=action('PC',()=>switchTo('pc')),local=action('iPhoneの写真・ファイル',()=>switchTo('phone'));
  function release(){for(const url of urls)URL.revokeObjectURL(url);urls=[];}
  function clear(){release();cards.replaceChildren();pages.replaceChildren();label.textContent='';}
  function invalidate(){controller?.abort();controller=new AbortController();return ++epoch;}
  function switchTo(value){
    if(dead)return;
    mode=value;page=0;invalidate();clear();controls.replaceChildren();pc.setAttribute('aria-pressed',String(value==='pc'));local.setAttribute('aria-pressed',String(value==='phone'));
    if(value==='pc'){refresh(false);return;}
    const input=element('input');input.type='file';input.accept='image/*';input.multiple=true;
    input.addEventListener('change',()=>{files=Array.from(input.files||[]);page=0;phonePage();});
    controls.append(labeled('画像を選ぶ',input));phonePage();
  }
  function addTile(name,src,click){
    const b=action('',click,'fe-card'),wrap=element('span','fe-cover'),img=element('img');img.alt='';img.loading='lazy';img.decoding='async';img.src=src;
    img.addEventListener('error',()=>{wrap.replaceChildren(element('small','fe-placeholder','画像を表示できません'));},{once:true});
    wrap.append(img);b.append(wrap,element('span','',name));cards.append(b);
  }
  function pager(total,fn){
    pages.replaceChildren();if(!total)return;
    const start=page*pageSize+1,count=element('span','',(start)+'–'+Math.min(start+pageSize-1,total)+' / '+total);
    const back=action('前へ',()=>{page--;fn();}),next=action('次へ',()=>{page++;fn();});
    back.disabled=page===0;next.disabled=(page+1)*pageSize>=total;pages.append(back,count,next);
  }
  function phonePage(){
    clear();if(!files.length){label.textContent='写真またはファイルを選んでください。';return;}
    for(const f of files.slice(page*pageSize,(page+1)*pageSize)){
      const u=URL.createObjectURL(f);urls.push(u);addTile(f.name,u,()=>view({file:f,src:u,name:f.name}));
    }
    pager(files.length,phonePage);
  }
  function dateControls(){
    const select=element('select');select.append(makeOption('すべての日付（'+days.total+' 枚）',''));
    for(const d of days.days)select.append(makeOption(d.day+' · '+d.count+' 枚',d.day));
    select.value=day;select.addEventListener('change',()=>{day=select.value;page=0;loadPage();});
    controls.replaceChildren(labeled('日付',select),action('更新',()=>refresh(true)));
  }
  async function refresh(rescan){
    const rev=invalidate();clear();label.textContent='PCの画像を確認中…';
    try {
      const d=await readJson(request,index+'dates?refresh='+rescan,{signal:controller.signal});
      if(dead || rev!==epoch)return;
      if(!Array.isArray(d.days))throw new Error('画像の日付を取得できません。');
      days=d;if(day && !d.days.some(r=>r.day===day))day='';
      const total=day?d.days.find(r=>r.day===day)?.count:d.total;
      page=Math.max(0,Math.min(page,Math.ceil(total/pageSize)-1));
      dateControls();await loadPage(false);
    } catch(e){if(rev===epoch && !dead){label.textContent='PCの画像一覧を取得できません。';controls.replaceChildren(action('再試行',()=>refresh(false)));}}
  }
  async function loadPage(allowRefresh=true){
    const rev=invalidate();clear();label.textContent='画像を読み込み中…';
    try {
      const query=new URLSearchParams({offset:String(page*pageSize),limit:String(pageSize),version:String(days.version)});
      if(day)query.set('day',day);
      const data=await readJson(request,index+'page?'+query,{signal:controller.signal});
      if(dead || rev!==epoch)return;
      for(const r of data.items){
        if(!keyOK(r.id))continue;
        const src=gallery+r.id;addTile(r.name,src+'/thumb',()=>view({name:r.name,key:r.id,src:src+'/display',details:[r.source,r.day].filter(Boolean).join(' · ')}));
      }
      label.textContent=cards.childElementCount?'':'この日付の画像はありません。';pager(data.total,()=>loadPage());
    } catch(e){
      if(dead || rev!==epoch)return;
      if(e.status===409 && allowRefresh){await refresh(false);return;}
      label.textContent='画像一覧を取得できません。';pages.append(action('一覧を更新',()=>refresh(true)));
    }
  }
  function view(item){
    const p=sheet(item.name,()=>{panel=null;zoom?.close();});panel=p;
    const img=element('img','fe-detail-image');img.src=item.src;img.alt=item.name;
    const zoomNow=()=>{zoom=showFullscreen(item.key?gallery+item.key+'/display?large=true':item.src,item.name);};
    const open=action('',zoomNow,'fe-detail-open');open.setAttribute('aria-label','画像を全画面で表示');open.append(img);
    const buttons=element('div','fe-toolbar'),status=element('p','fe-hint'),infoBox=element('pre','fe-info');
    status.setAttribute('role','status');let info='',sourceBlob=null;
    const restore=action('生成設定を復元',()=>{const patch=parseGenerationInfo(info);if(patch){p.close();onRestore(patch,info);}});
    restore.disabled=true;
    async function imageData(forInput){
      if(!sourceBlob){
        let blob=item.file;
        if(!blob)blob=await readOriginal(await request(gallery+item.key,{signal:p.signal}));
        sourceBlob=blob;
      }
      if(forInput)return forgeImageData(sourceBlob);
      if(!/^image\/(?:png|jpeg|webp|gif|avif)$/i.test(sourceBlob.type))
        throw new Error('PCのPNG Info確認には、変換前のPNG、JPEG、WebPなどの画像を選んでください。');
      return dataUrl(sourceBlob);
    }
    async function run(button,fun){button.disabled=true;try{await fun();}catch(e){if(!p.signal.aborted)status.textContent=e.message||'画像を読み込めません。';}finally{button.disabled=false;}}
    const use=action('img2imgに使う',()=>run(use,async()=>{const data=await imageData(true);if(p.el.open){p.close();onUseImage(data);}}));
    const onPC=action('PCでPNG Infoを確認',()=>run(onPC,async()=>{
      const data=await imageData(false);
      const reply=await readJson(request,'/sdapi/v1/png-info',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({image:data}),signal:p.signal});
      if(p.el.open)setInfo(reply.info||'');
    }));
    function setInfo(raw){
      info=typeof raw==='string'?raw:'';infoBox.textContent=info;
      restore.disabled=!parseGenerationInfo(info);status.textContent=info?'PNG Info':'生成情報が見つかりません。';
    }
    buttons.append(action('全画面',zoomNow),use,restore);
    if(item.file)buttons.append(onPC);
    p.body.append(open);if(item.details)p.body.append(element('p','fe-hint',item.details));
    p.body.append(buttons,status,infoBox);status.textContent='生成情報を読み込み中…';
    Promise.resolve().then(()=>item.file?localPngInfo(item.file):readJson(request,gallery+item.key+'/info',{signal:p.signal}).then(j=>j.parameters))
      .then(raw=>{if(p.el.open)setInfo(raw);}).catch(()=>{if(p.el.open)status.textContent='生成情報を読み込めません。';});
  }
  tabs.append(pc,local);root.append(tabs,controls,label,cards,pages);container.append(root);switchTo('pc');
  return {destroy(){dead=true;invalidate();release();zoom?.close();panel?.close();root.remove();}};
}
