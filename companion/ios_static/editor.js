const base = '/forge-remote/v1/';
const loraTag = /^<lora:/i;
const numberPart = '-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)';
const attention = new RegExp('^\\(([\\s\\S]+):(' + numberPart + ')\\)$');
const loraWeight = new RegExp('^(<lora:[^<>]+:)(' + numberPart + ')>$', 'i');
const savedKey = 'forge-e.ios.saved-tags.v1';
const faces = new Set('0_0,(o)_(o),+_+,+_-,._.,<o>_<o>,<|>_<|>,=_=,>_<,3_3,6_9,>_o,@_@,^_^,o_o,u_u,x_x,|_|,||_||'.split(','));

// Text offsets are UTF-16 positions, as used by textarea.selectionStart.
export function promptLayout(text) {
  const slots = [], stack = [], lineStarts = [0];
  let from = 0, escaped = false, quoted = false;
  function finish(end) {
    let start = from;
    while (start < end && /\s/.test(text[start])) start++;
    while (end > start && /\s/.test(text[end - 1])) end--;
    if (end > start) slots.push({start, end, text: text.slice(start, end)});
  }
  const pairs = {'(': ')', '[': ']', '{': '}', '<': '>'};
  for (let index = 0; index < text.length; index++) {
    const c = text[index];
    if (c === '\n' || (c === '\r' && text[index + 1] !== '\n')) lineStarts.push(index + 1);
    if (escaped) { escaped = false; continue; }
    if (c === '\\') { escaped = true; continue; }
    if (c === '"') { quoted = !quoted; continue; }
    if (quoted) continue;
    if (pairs[c]) stack.push(pairs[c]);
    else if (stack.length && c === stack[stack.length - 1]) stack.pop();
    else if (!stack.length && /[,\r\n]/.test(c)) { finish(index); from = index + 1; }
  }
  finish(text.length);
  const lines = lineStarts.map((_, i) => ({number: i + 1, positions: []}));
  let line = 0;
  slots.forEach((slot, id) => {
    while (line + 1 < lines.length && lineStarts[line + 1] <= slot.start) line++;
    lines[line].positions.push(id);
    while (line + 1 < lines.length && lineStarts[line + 1] < slot.end) lines[++line].covered = true;
  });
  return {text, slots, lines: lines.filter(l => !l.covered || l.positions.length)};
}

export function applyOrder(layout, order, selected) {
  if (order.length !== layout.slots.length || new Set(order).size !== order.length ||
      !order.every(v => Number.isInteger(v) && layout.slots[v])) throw new Error('並び順を確認できません');
  let text = '', last = 0, start = 0, end = 0;
  order.forEach((id, i) => {
    const slot = layout.slots[i], value = layout.slots[id].text;
    text += layout.text.slice(last, slot.start);
    if (id === selected) { start = text.length; end = start + value.length; }
    text += value;
    last = slot.end;
  });
  return {text: text + layout.text.slice(last), start, end};
}

function phrase(text, start, end) {
  if (start === end) {
    const match = promptLayout(text).slots.find(v => start >= v.start && start <= v.end);
    if (match) return [match.start, match.end];
  }
  while (start < end && /\s/.test(text[start])) start++;
  while (end > start && /\s/.test(text[end - 1])) end--;
  return [start, end];
}
const endsComma = s => /(?:^|[^\\])(?:\\\\)*,$/.test(s);
export function insertPrompt(text, start, end, tag, completion = false) {
  if (completion) [start, end] = phrase(text, start, end);
  else if (start === end && start > 0 && start < text.length &&
           !/[\s,]/.test(text[start - 1]) && !/[\s,]/.test(text[end])) start = end = phrase(text, start, end)[1];
  const before = text.slice(0, start), after = text.slice(end);
  let inserted, suffix = after;
  if (loraTag.test(tag)) {
    const left = !before.trim() || before.endsWith('\n') ? '' : /,\s*$/.test(before) ? (/\s$/.test(before) ? '' : ' ') : ', ';
    const right = !after.trim() || /^\n|^\s*,/.test(after) ? '' : ', ';
    inserted = before + left + tag + right;
  } else {
    const norm = s => s.trim().replace(/_/g, ' ').toLowerCase();
    const existing = promptLayout(text).slots.find(s => norm(s.text) === norm(tag) && (!completion || s.end <= start || s.start >= end));
    if (existing) return {text, start: existing.start, end: existing.end};
    const left = before.replace(/[ \t]+$/, '');
    const prefix = !left || /[\r\n]$/.test(left) ? before : left + (endsComma(left) ? ' ' : ', ');
    let body = tag.replace(/[ \t]+$/, '');
    if (endsComma(body)) body = body.slice(0, -1);
    inserted = prefix + body + ', ';
    suffix = after.replace(/^[ \t]*(?:,[ \t]*)?/, '');
  }
  return {text: inserted + suffix, start: inserted.length, end: inserted.length};
}

export function adjustWeight(word, step) {
  const lora = word.match(loraWeight), m = lora || word.match(attention);
  const old = m ? m[2] : '1', places = Math.max(1, (old.split('.')[1] || '').length);
  const bits = old.replace(/^-/, '').split('.');
  const unit = 10n ** BigInt(places);
  const magnitude = BigInt(bits[0] || 0) * unit + BigInt((bits[1] || '').padEnd(places, '0'));
  const scaled = (old.startsWith('-') ? -magnitude : magnitude) + BigInt(step) * unit / 10n;
  const abs = scaled < 0n ? -scaled : scaled;
  const fraction = String(abs % unit).padStart(places, '0').replace(/0+$/, '');
  const value = (scaled < 0n ? '-' : '') + String(abs / unit) + (fraction ? '.' + fraction : '');
  if (lora) return lora[1] + value + '>';
  const name = m ? m[1] : word;
  return value === '1' ? name : '(' + name + ':' + value + ')';
}

function formatSaved(name, settings = {}) {
  if (/[,\n]/.test(name) || /^__|^<[^>]*:[^>]*>$|^\(.*\)$|^\[.*\]$|^\{.*\}$/.test(name)) return name;
  const exemptions = new Set(settings.underscore_exclusions || faces);
  let text = '', slashes = 0;
  for (const c of name) {
    if (settings.escape_brackets !== false && '()[]'.includes(c) && slashes % 2 === 0) text += '\\';
    text += c === '_' && settings.replace_underscores !== false && !exemptions.has(name) ? ' ' : c;
    slashes = c === '\\' ? slashes + 1 : 0;
  }
  return text;
}
export function rankedChoices(remote, saved = []) {
  const choices = new Map();
  for (const row of remote.concat(saved)) {
    const r = typeof row === 'string' ? {name: row, insert: formatSaved(row)} : row;
    if (r && typeof r.name === 'string' && typeof r.insert === 'string' && r.insert.trim() && !choices.has(r.name)) choices.set(r.name, r);
  }
  const count = r => Number.isSafeInteger(r.count) && r.count >= 0 ? r.count : -1;
  return [...choices.values()].sort((a, b) => count(b) - count(a)).slice(0, 32);
}
export function element(tag, cls = '', text = '') {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text) e.textContent = text;
  return e;
}
export function action(label, run, cls = '') {
  const b = element('button', cls, label);
  b.type = 'button'; b.addEventListener('click', run);
  return b;
}
export function labeled(label, control) {
  const wrap = element('label','fe-field'); wrap.append(element('span','',label), control); return wrap;
}
export function sheet(title, onClose = () => {}) {
  const el = element('dialog','fe-sheet'), head = element('header','fe-sheet-head');
  const h = element('h2','',title), body = element('div','fe-sheet-body');
  el.setAttribute('aria-label',title); head.append(h, action('閉じる', () => el.close())); el.append(head,body);
  document.body.append(el);
  const abort = new AbortController();
  el.addEventListener('close', () => { abort.abort(); el.remove(); onClose(); }, {once:true});
  el.showModal();
  return {el, body, head, signal:abort.signal, close: () => {if(el.open) el.close();}};
}
export async function readJson(request, path, init) {
  const r = await request(path, init);
  if (!r.ok) {const e = new Error('PCから取得できません（' + r.status + '）'); e.status = r.status; throw e;}
  return r.json();
}
function loadSaved() {
  try { const a = JSON.parse(localStorage.getItem(savedKey) || '[]'); return a.filter(s => typeof s === 'string'); }
  catch { return []; }
}

function editOrder(field, commit, notify, closeHook) {
  const layout = promptLayout(field.value), order = layout.slots.map((_, i) => i);
  const popup = sheet((field.name === 'negative_prompt' ? 'ネガティブ' : 'プロンプト') + 'の並べ替え',closeHook);
  const list = element('div','fe-order-list'), controls = element('div','fe-toolbar');
  let selected = layout.slots.findIndex(s => field.selectionStart >= s.start && field.selectionStart <= s.end);
  let drag = null, raf = 0;
  const previous = action('前へ', () => {const i = order.indexOf(selected); move(i,i - 1);});
  const next = action('次へ', () => {const i = order.indexOf(selected); move(i,i + 1);});
  const apply = action('適用', () => {
    if (field.value !== layout.text) {notify('プロンプトが更新されました。もう一度開いてください。');popup.close();return;}
    popup.close();commit(applyOrder(layout, order, selected));
  });
  function move(i, j) {
    if (i < 0 || j < 0 || j >= order.length || i === j) return;
    order.splice(j,0,order.splice(i,1)[0]);paint();
  }
  function selectedState() {
    for (const chip of list.querySelectorAll('[data-pos]')) chip.setAttribute('aria-pressed',String(order[Number(chip.dataset.pos)] === selected));
    const i = order.indexOf(selected); previous.disabled = i < 1; next.disabled = i < 0 || i + 1 >= order.length;
  }
  function finish(e, drop) {
    if (!drag || (e && e.pointerId !== drag.id)) return;
    const d = drag;drag = null; cancelAnimationFrame(raf);
    d.ghost?.remove();d.hover?.classList.remove('fe-hover');list.classList.remove('fe-dragging');
    if (list.hasPointerCapture(d.id)) list.releasePointerCapture(d.id);
    if (drop && d.moved && d.hover) move(order.indexOf(selected), Number(d.hover.dataset.pos));
  }
  function frame() {
    if (!drag?.moved) return;
    const bounds = list.getBoundingClientRect();
    const delta = drag.y < bounds.top + 42 ? -9 : drag.y > bounds.bottom - 42 ? 9 : 0;
    if (delta) list.scrollTop += delta;
    let best = null, distance = Infinity;
    for (const chip of list.querySelectorAll('[data-pos]')) {
      const r = chip.getBoundingClientRect();
      if (r.bottom < bounds.top || r.top > bounds.bottom) continue;
      const dx = Math.max(r.left - drag.x,0,drag.x-r.right), dy = Math.max(r.top-drag.y,0,drag.y-r.bottom);
      if (dx*dx + dy*dy < distance) {best=chip;distance=dx*dx+dy*dy;}
    }
    if (best !== drag.hover) {drag.hover?.classList.remove('fe-hover');best?.classList.add('fe-hover');drag.hover=best;}
    raf = requestAnimationFrame(frame);
  }
  function paint() {
    list.replaceChildren();
    for (const line of layout.lines) {
      const row = element('div','fe-order-row'); row.append(element('span','fe-line-num',String(line.number)));
      row.setAttribute('aria-label','行 ' + line.number);
      const chips = element('div','fe-chips');
      for (const pos of line.positions) {
        const chip = action(layout.slots[order[pos]].text, () => {selected = order[pos];selectedState();},'fe-chip');
        chip.dataset.pos = String(pos);chips.append(chip);
      }
      if (!line.positions.length) chips.append(element('span','fe-blank','空行'));
      row.append(chips);list.append(row);
    }
    selectedState();
  }
  list.addEventListener('pointerdown', e => {
    const c = e.target.closest('[data-pos]');
    if (!c || drag || e.button !== 0 || !e.isPrimary) return;
    // A first tap selects a chip and leaves vertical scrolling available.
    if (order[Number(c.dataset.pos)] !== selected) return;
    drag = {id:e.pointerId, x:e.clientX, y:e.clientY, sx:e.clientX, sy:e.clientY};
    list.setPointerCapture(e.pointerId);
  });
  list.addEventListener('pointermove', e => {
    if (!drag || drag.id !== e.pointerId) return;
    drag.x=e.clientX;drag.y=e.clientY;
    if (!drag.moved && Math.hypot(drag.x-drag.sx,drag.y-drag.sy)<4) return;
    if (!drag.moved) {
      drag.moved=true; drag.ghost=element('div','fe-ghost',layout.slots[selected].text);
      drag.ghost.setAttribute('aria-label','移動中の語句 · '+layout.slots[selected].text);
      popup.el.append(drag.ghost);list.classList.add('fe-dragging');frame();
    }
    const r=drag.ghost.getBoundingClientRect();
    drag.ghost.style.left=Math.max(4,Math.min(innerWidth-r.width-4,drag.x-r.width/2))+'px';
    drag.ghost.style.top=Math.max(4,drag.y-r.height-18)+'px';
  });
  list.addEventListener('pointerup', e=>finish(e,true));
  list.addEventListener('pointercancel', e=>finish(e,false));
  list.addEventListener('lostpointercapture', e=>finish(e,false));
  popup.signal.addEventListener('abort',()=>finish(null,false));
  controls.append(previous,next,apply);popup.body.append(element('p','fe-hint','語句を選んでドラッグするか、「前へ」「次へ」で移動します。'),list,controls);paint();
  return popup;
}

function openLoras(request, use, closeHook) {
  const panel=sheet('LoRAを挿入',closeHook), search=element('input'), filter=element('select');
  search.type='search';search.placeholder='名前・別名・フォルダーを検索';
  const nav=element('div','fe-toolbar'), tools=element('div','fe-toolbar'), grid=element('div','fe-grid');
  const strength=element('input');strength.type='text';strength.inputMode='decimal';strength.placeholder='PC設定';
  const status=element('p','fe-hint');status.setAttribute('role','status');
  let catalog=null, folder='', page=0, folders=false, busy=false;
  const foldersButton=action('フォルダー別',()=>{folders=!folders;folder='';page=0;draw();});
  const back=action('上のフォルダー',()=>{folder=folder.split('/').slice(0,-1).join('/');page=0;draw();});
  function redrawSearch(){page=0;draw();}
  filter.addEventListener('change',redrawSearch);search.addEventListener('input',redrawSearch);
  tools.append(foldersButton,labeled('種類',filter),labeled('強さ',strength));
  panel.body.append(labeled('LoRAを検索',search),tools,nav,status,grid);
  async function pick(row) {
    if(busy || row.ambiguous)return;
    busy=true;
    let weight=strength.value.trim();
    if(weight && !new RegExp('^'+numberPart+'$').test(weight)){status.textContent='強さを数値で入力してください。';busy=false;return;}
    try {
      if(!weight && row.preferred_weight!=null && Number.isFinite(Number(row.preferred_weight))) weight=String(row.preferred_weight);
      if(!weight && !row.duplicate_name){
        try {
          const info=await readJson(request,'/tacapi/v1/lora-info/'+encodeURIComponent(row.name),{signal:panel.signal});
          const pref=info['preferred weight'];
          if(typeof pref==='number' && Number.isFinite(pref))weight=String(pref);
        } catch {/* Optional PC-provided per-model setting. */}
      }
      if(panel.signal.aborted || !panel.el.open)return;
      const baseWeight=Number(catalog.weight);
      panel.close();use('<lora:'+(row.alias||row.name)+':'+(weight || (Number.isFinite(baseWeight)?String(baseWeight):'1'))+'>');
    } finally {busy=false;}
  }
  function draw(){
    if(!catalog)return;
    const query=search.value.trim().toLowerCase(), type=filter.value;
    const rank=r=>!catalog.preset || !r.arch?.length ? 1 : r.arch.some(x=>x.toLowerCase()===catalog.preset.toLowerCase()) ? 0 : 2;
    let rows=catalog.items.filter(r=>{
      if(type==='pc' && catalog.filter==='Hide other architectures' && rank(r)===2)return false;
      if(type!=='pc' && type!=='all' && !r.arch?.includes(type))return false;
      return [r.name,r.alias,r.path].some(x=>typeof x==='string' && x.toLowerCase().includes(query));
    });
    if(type==='pc' && catalog.filter!=='Off')rows.sort((a,b)=>rank(a)-rank(b));
    nav.replaceChildren();grid.replaceChildren();foldersButton.setAttribute('aria-pressed',String(folders));
    if(folders){
      back.disabled=!folder;nav.append(back,element('span','fe-path',folder||'すべてのフォルダー'));
      const children=new Map(), direct=[], prefix=folder?folder+'/':'';
      for(const r of rows){
        const parts=String(r.path||r.name).split('/');parts.pop();
        const dir=parts.join('/');
        if(dir!==folder && !dir.startsWith(prefix))continue;
        if(dir===folder || query)direct.push(r);
        else{const child=dir.slice(prefix.length).split('/')[0];children.set(child,(children.get(child)||0)+1);}
      }
      for(const [child,count] of [...children].sort((a,b)=>a[0].localeCompare(b[0]))) {
        const b=action(child+' · '+count+'件',()=>{folder=prefix+child;page=0;draw();},'fe-folder');grid.append(b);
      }
      rows=direct;
    }
    const perPage=24;
    page=Math.max(0,Math.min(page,Math.ceil(rows.length/perPage)-1));
    for(const r of rows.slice(page*perPage,(page+1)*perPage)){
      const b=action('',()=>pick(r),'fe-card');b.disabled=Boolean(r.ambiguous);
      b.setAttribute('aria-label',r.name+' · '+(r.path||r.name)+(r.ambiguous?' · 挿入名が重複':''));
      const cover=element('span','fe-cover');cover.append(element('span','fe-placeholder','プレビューなし'));
      if(/^[0-9a-f]{32}$/.test(r.thumbnail_id)){
        const img=element('img');img.alt='';img.loading='lazy';img.decoding='async';
        img.addEventListener('load',()=>cover.firstChild?.classList.add('fe-hidden'),{once:true});
        img.addEventListener('error',()=>img.remove(),{once:true});
        img.src=base+'loras/'+r.thumbnail_id+'/thumb';cover.append(img);
      }
      b.append(cover,element('strong','',r.name),element('small','',r.path||r.name));
      if(r.ambiguous)b.append(element('small','fe-hint','挿入名が重複'));
      grid.append(b);
    }
    status.textContent=rows.length ? (page*perPage+1)+'–'+Math.min((page+1)*perPage,rows.length)+' / '+rows.length+'件' : grid.childElementCount ? '' : '該当するLoRAがありません。';
    if(rows.length>perPage){
      const prev=action('前のページ',()=>{page--;draw();}),next=action('次のページ',()=>{page++;draw();});
      prev.disabled=!page;next.disabled=(page+1)*perPage>=rows.length;nav.append(prev,next);
    }
  }
  async function load(){
    if(busy)return;busy=true;status.textContent='PCのLoRAを読み込み中…';grid.replaceChildren();
    try {
      catalog=await readJson(request,base+'loras',{signal:panel.signal});
      if(!Array.isArray(catalog.items))throw new Error('一覧を取得できません');
      function option(value,text){const o=element('option','',text);o.value=value;filter.append(o);}
      option('pc','PC設定');option('all','全件');
      [...new Set(catalog.items.flatMap(r=>r.arch||[]))].sort().forEach(a=>option(a,a));
      draw();
    } catch {if(!panel.signal.aborted){status.textContent='PCのLoRA一覧を取得できません。';grid.append(action('再試行',load));}}
    finally{busy=false;}
  }
  load();return panel;
}

export function mountEditor(container, options) {
  const {draft={},onChange=()=>{},request,quick=false,onFullEditor}=options;
  const root=element('div','fe-editor'), fields={}, composing=new Set(), lifetime=new AbortController();
  const candidates=element('section','fe-suggestions'), feedback=element('p','fe-hint');
  feedback.setAttribute('role','status');candidates.hidden=true;candidates.setAttribute('aria-label','タグ候補');
  const wide=window.matchMedia('(min-width:680px)');
  let active=null, currentPanel=null, timer, lookup=null, sequence=0, inactive=false;
  let stored=loadSaved(), formatting=options.tagFormat || {};
  function notify(text){feedback.textContent=text;options.notify?.(text);}
  function closeCandidates(){
    clearTimeout(timer);lookup?.abort();lookup=null;sequence++;
    candidates.replaceChildren();candidates.hidden=true;
    candidates.remove();
    options.suggestionsHost?.classList.remove('fe-show-candidates');
  }
  function positionCandidates() {
    if(candidates.hidden){candidates.remove();options.suggestionsHost?.classList.remove('fe-show-candidates');return;}
    const external=wide.matches && options.suggestionsHost;
    options.suggestionsHost?.classList.toggle('fe-show-candidates',Boolean(external));
    if(external)external.append(candidates);
    else {
      active.closest('label').after(candidates);
      // Safari keeps the layout viewport tall when its keyboard covers the
      // lower screen. Make room for the first row while retaining the editor.
      const viewport=window.visualViewport;
      if(viewport && viewport.height<window.innerHeight*.85) requestAnimationFrame(()=>{
        if(candidates.hidden || document.activeElement!==active || !candidates.isConnected)return;
        const covered=candidates.getBoundingClientRect().top+92-viewport.offsetTop-viewport.height;
        const room=active.getBoundingClientRect().top-viewport.offsetTop-8;
        if(covered>0 && room>0)window.scrollBy(0,Math.min(covered,room));
      });
    }
  }
  wide.addEventListener('change',positionCandidates);
  function panelClosed(){currentPanel=null;}
  function write(field,result,focus=true){
    if(inactive)return;
    closeCandidates();field.value=result.text;
    if(focus)field.focus({preventScroll:true});
    field.setSelectionRange(result.start,result.end);onChange({[field.name]:result.text});
  }
  function choiceButtons(target,choices,choose){
    const grid=element('div','fe-tag-grid');
    for(const r of choices){
      const b=action('',()=>choose(r),'fe-tag');
      b.append(element('span','',r.name));
      if(Number.isSafeInteger(r.count) && r.count>=0)b.append(element('small','',r.count.toLocaleString('ja-JP')+' 件'));
      // Keep the textarea selection and Japanese keyboard on a dictionary tap.
      b.addEventListener('pointerdown',e=>e.preventDefault());grid.append(b);
    }
    target.append(grid);
  }
  function savedChoices(q){const lower=q.toLowerCase();return stored.map(name=>({name,insert:formatSaved(name,formatting)})).filter(r=>[r.name,r.insert].some(x=>x.toLowerCase().includes(lower)));}
  function suggest(field){
    closeCandidates();
    if(composing.has(field) || active!==field)return;
    const text=field.value,start=field.selectionStart,end=field.selectionEnd;
    const [a,b]=phrase(text,start,end),frag=text.slice(a,b);
    if(!frag.trim() || frag.length>128 || loraTag.test(frag))return;
    const q=frag.replace(/\\([()\[\]])/g,'$1'), rev=sequence;
    const current=()=>rev===sequence && !inactive && field.value===text && field.selectionStart===start && field.selectionEnd===end && !composing.has(field);
    function show(rows){
      if(!current())return;
      candidates.replaceChildren();candidates.hidden=!rows.length;
      positionCandidates();
      if(!rows.length)return;
      const header=element('div','fe-tags-head');
      header.append(element('strong','','タグ候補'),element('small','',rows.length+' 件'),action('閉じる',closeCandidates));
      candidates.append(header);choiceButtons(candidates,rows,r=>{
        if(!current())return;
        write(field,insertPrompt(text,start,end,r.insert,true));
      });
    }
    timer=setTimeout(async()=>{
      if(!current())return;
      const local=savedChoices(q);if(local.length)show(rankedChoices([],local));
      const abort=new AbortController();lookup=abort;
      try {
        const rows=await readJson(request,base+'tags?formatted=true&q='+encodeURIComponent(q),{signal:abort.signal});
        if(Array.isArray(rows))show(rankedChoices(rows,local));
      } catch {/* Local saved tags remain usable when the PC is unavailable. */}
    },180);
  }
  const visibleKeys=quick?['prompt']:['prompt','negative_prompt'];
  for(const key of visibleKeys){
    const field=element('textarea');field.name=key;field.value=draft[key]||'';field.rows=key==='prompt'?4:3;
    field.spellcheck=false;field.autocapitalize='off';field.setAttribute('autocomplete','off');
    fields[key]=field;active??=field;
    field.addEventListener('focus',()=>{if(active!==field)closeCandidates();active=field;});
    field.addEventListener('compositionstart',()=>{composing.add(field);closeCandidates();});
    field.addEventListener('compositionend',()=>{composing.delete(field);onChange({[key]:field.value});suggest(field);});
    field.addEventListener('input',e=>{if(!e.isComposing && !composing.has(field)){onChange({[key]:field.value});suggest(field);}});
    field.addEventListener('keyup',e=>{if(e.key==='Escape')closeCandidates();else if(['ArrowLeft','ArrowRight','Home','End'].includes(e.key))closeCandidates();});
    field.addEventListener('click',()=>{if(!candidates.hidden)closeCandidates();});
    root.append(labeled(key==='prompt'?'プロンプト':'ネガティブプロンプト',field));
  }
  root.append(feedback);
  function savedPicker(saveOnly=false){
    const field=active;
    const p=sheet(saveOnly?'タグを登録':'端末のタグ',panelClosed);currentPanel=p;
    const input=element('textarea'), range=phrase(field.value,field.selectionStart,field.selectionEnd);
    input.rows=2;input.value=saveOnly?field.value.slice(...range):'';
    const list=element('div','fe-saved');
    function save(values){try {localStorage.setItem(savedKey,JSON.stringify(values));stored=values;return true;}catch{notify('端末にタグを保存できません。');return false;}}
    function draw(){
      list.replaceChildren();
      for(const name of stored) {
        const row=element('div','fe-saved-row');
        row.append(action(name,()=>{p.close();write(field,insertPrompt(field.value,field.selectionStart,field.selectionEnd,formatSaved(name,formatting)));}),
          action('削除',()=>{if(save(stored.filter(s=>s!==name)))draw();}));
        list.append(row);
      }
    }
    p.body.append(labeled('保存するタグ・定型文',input),action('端末に保存',()=>{
      const name=input.value.trim();if(!name)return;
      if(!save([...new Set([...stored,name])].sort((a,b)=>a.localeCompare(b))))return;
      input.value='';if(saveOnly){p.close();notify('タグを保存しました');}else draw();
    }),list);if(!saveOnly)draw();
  }
  function manualPicker(){
    const field=active,p=sheet('タグ検索',panelClosed);currentPanel=p;
    const input=element('input'), out=element('div'), status=element('p','fe-hint');
    let controller, timeout, revision=0;input.type='search';input.placeholder='PCの辞書を検索';
    function search(){
      clearTimeout(timeout);controller?.abort();const id=++revision,q=input.value.trim().slice(0,128);out.replaceChildren();
      if(!q){status.textContent='';return;}
      status.textContent='検索中…';controller=new AbortController();const signal=controller.signal;
      timeout=setTimeout(async()=>{
        let remote=[],ok=true;
        try {remote=await readJson(request,base+'tags?formatted=true&q='+encodeURIComponent(q.replace(/\\([()\[\]])/g,'$1')),{signal});}
        catch {ok=false;}
        if(signal.aborted || p.signal.aborted || revision!==id)return;
        const rows=rankedChoices(Array.isArray(remote)?remote:[],savedChoices(q));
        status.textContent=rows.length?rows.length+' 件':ok?'該当するタグがありません。':'PCの辞書に接続できません。';
        choiceButtons(out,rows,r=>{p.close();write(field,insertPrompt(field.value,field.selectionStart,field.selectionEnd,r.insert));});
      },160);
    }
    input.addEventListener('input',e=>{if(!e.isComposing)search();});input.addEventListener('compositionend',search);
    p.signal.addEventListener('abort',()=>{clearTimeout(timeout);controller?.abort();});
    p.body.append(labeled('検索語',input),status,out);input.focus();
  }
  function weight(step){
    const f=active,[a,b]=phrase(f.value,f.selectionStart,f.selectionEnd);if(a===b){notify('語句を選んでください。');return;}
    const s=adjustWeight(f.value.slice(a,b),step);write(f,{text:f.value.slice(0,a)+s+f.value.slice(b),start:a,end:a+s.length});
  }
  const bar=element('div','fe-toolbar');
  if(quick){if(onFullEditor)bar.append(action('タグ・強弱・LoRAを編集',onFullEditor));}
  else {
    bar.append(action('弱く −0.1',()=>weight(-1)),action('強く ＋0.1',()=>weight(1)),
      action('並べ替え',()=>{const f=active;closeCandidates();currentPanel=editOrder(f,result=>write(f,result),notify,panelClosed);}),
      action('LoRAを挿入',()=>{const f=active;closeCandidates();currentPanel=openLoras(request,tag=>write(f,insertPrompt(f.value,f.selectionStart,f.selectionEnd,tag)),panelClosed);}),
      action('タグ検索',()=>{closeCandidates();manualPicker();}),action('端末のタグ',()=>{closeCandidates();savedPicker();}),action('タグを登録',()=>{closeCandidates();savedPicker(true);}));
  }
  root.append(bar);container.append(root);
  if(!options.tagFormat)readJson(request,base+'capabilities',{signal:lifetime.signal}).then(c=>{formatting=c.tag_format||{};}).catch(()=>{});
  return {
    update(patch){for(const [k,f] of Object.entries(fields))if(typeof patch[k]==='string' && patch[k]!==f.value && !composing.has(f)){closeCandidates();f.value=patch[k];}},
    destroy(){inactive=true;closeCandidates();wide.removeEventListener('change',positionCandidates);lifetime.abort();currentPanel?.close();candidates.remove();root.remove();}
  };
}
export const mountQuickPrompt=(container,options)=>mountEditor(container,{...options,quick:true});
