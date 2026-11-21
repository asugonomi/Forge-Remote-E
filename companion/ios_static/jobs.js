import {button, element, notify, readImage, row, section} from "./ui.js";
import {parseGenerationInfo, showFullscreen} from "./gallery.js";

const labels = {queued:"待機中",running:"生成中",done:"完了",empty:"結果画像なし",failed:"失敗",unknown:"要確認",interrupted:"中断",skipped:"スキップ",done_with_skip:"一部スキップ"};
function endpoint(job) { return "/api/jobs/"+encodeURIComponent(job.id); }

export class Jobs {
  constructor(ctx) {
    this.ctx=ctx; this.snapshot={jobs:[],paused:false}; this.imageKey=""; this.refreshing=false;
    this.selected=null; this.index=0; this.dragging=false;
    this.frame=document.querySelector("#result-stage");
  }
  async refresh() {
    if (this.refreshing || document.hidden) return;
    this.refreshing=true;
    try {
      this.snapshot = await this.ctx.json("/api/queue");
      this.ctx.online(true);
      this.paintList();
      await this.paintPreview();
    } catch (e) { this.ctx.online(false); }
    finally { this.refreshing=false; }
  }
  start() {
    const tick=async () => { await this.refresh(); this.timer=setTimeout(tick,this.snapshot.jobs.some(j=>j.state==="running")?1000:3300); };
    tick();
    this.onVisible=()=>{ if(!document.hidden)this.refresh(); };
    document.addEventListener("visibilitychange",this.onVisible);
    window.addEventListener("online",this.onVisible);
  }
  async change(job, body) {
    await this.ctx.json(endpoint(job)+"/action",body);
    await this.refresh();
  }
  attach(panel) {
    const title=element("div",null,"actions");
    this.toggle=button("",async()=>{ await this.ctx.json("/api/queue",{action:this.snapshot.paused?"resume":"pause"}); await this.refresh(); });
    this.count=element("span",null,"quiet"); title.append(this.toggle,this.count);
    panel.append(title); this.list=element("div"); panel.append(this.list);
    this.signature=null; this.paintList();
    return {destroy:()=>{this.list=null;this.dragging=false;}};
  }
  pick(job,index=0) { this.selected=job.id;this.index=index;this.paintPreview().catch(e=>notify(e.message)); }
  paintList() {
    if(!this.list || this.dragging) return;
    const {jobs,paused}=this.snapshot;
    this.toggle.textContent=paused?"キュー再開":"次の送信を停止";
    this.count.textContent="待機 "+jobs.filter(j=>j.state==="queued").length+"件";
    const signature=JSON.stringify(jobs.map(j=>[j.id,j.state,j.result_count,j.error,j.control?.epoch,j.control?.unit,j.control?.status]));
    if(signature===this.signature)return;
    this.signature=signature; this.list.replaceChildren();
    if(!jobs.length)this.list.append(section(null,element("p","キューに生成はありません。","quiet")));
    for(const job of jobs){
      const tile=element("article",null,"job-row");tile.dataset.job=job.id;
      tile.append(element("div",job.mode+" · "+(labels[job.state]||job.state),"job-title"),element("p",job.prompt||"（プロンプトなし）"));
      if(job.error)tile.append(element("p",job.error,"error"));
      const controls=row();
      if(job.state==="queued"){
        const handle=button("順序",()=>{},"grip");
        handle.setAttribute("aria-label","指でキューの順序を変更");
        this.drag(handle,tile,job);
        controls.append(handle,button("上へ",()=>this.change(job,{action:"up"})),button("下へ",()=>this.change(job,{action:"down"})),button("取り消す",()=>this.change(job,{action:"delete"})));
      }else if(job.state==="running"){
        controls.append(button("進捗を見る",()=>this.pick(job)));
        for(const [act,label] of [["skip","スキップ"],["interrupt","中断"]]){
          const ctrl=button(label,async()=>{
            if(!window.confirm(label+"しますか？"))return;
            await this.change(job,{action:act,epoch:job.control.epoch,unit:job.control.unit,request_id:crypto.randomUUID()});
            notify(label+"をPCに送信しました");
          });
          ctrl.disabled=!job.control || ["queued","applied"].includes(job.control.status);controls.append(ctrl);
        }
        if(!job.control)tile.append(element("small","ジョブ操作はPC側拡張が処理を確認すると有効になります。"));
      }else{
        if(job.result_count) controls.append(button("結果を見る ("+job.result_count+")",()=>{this.pick(job);document.querySelector("#preview").scrollIntoView({block:"start"});}));
        controls.append(button("履歴を削除",async()=>{if(confirm("この生成の履歴とキュー内の画像を削除しますか？")) await this.change(job,{action:"delete"});}));
      }
      tile.append(controls);this.list.append(tile);
    }
  }
  drag(handle,tile,job) {
    let target,after;
    const clear=()=>{this.dragging=false;tile.classList.remove("dragging");target?.classList.remove("over");target=null;};
    handle.addEventListener("pointerdown",e=>{handle.setPointerCapture(e.pointerId);this.dragging=true;tile.classList.add("dragging");});
    handle.addEventListener("pointermove",e=>{
      if(!this.dragging||!handle.hasPointerCapture(e.pointerId))return;
      const hit=document.elementFromPoint(e.clientX,e.clientY)?.closest(".job-row");
      if(!hit || !this.snapshot.jobs.some(j=>j.id===hit.dataset.job && j.state==="queued"))return;
      target?.classList.remove("over");target=hit;target.classList.add("over");
      const rect=target.getBoundingClientRect();after=e.clientY>rect.top+rect.height/2;
    });
    handle.addEventListener("pointercancel",clear);
    handle.addEventListener("pointerup",async()=>{
      const id=target?.dataset.job;clear();
      if(!id || id===job.id)return;
      const list=this.snapshot.jobs.filter(j=>j.state==="queued"&&j.id!==job.id);
      const before=after?list[list.findIndex(j=>j.id===id)+1]?.id||null:id;
      try{await this.change(job,{action:"move",before});}catch(e){notify(e.message);}
    });
  }
  async paintPreview() {
    const jobs=this.snapshot.jobs;
    const pinned=jobs.find(j=>j.id===this.selected);
    const job=pinned || jobs.find(j=>j.state==="running") || jobs.find(j=>j.result_count);
    const live=job?.state==="running";
    document.querySelector("#result-state").textContent=job?(labels[job.state]||""):"";
    document.querySelector("#progress-area").hidden=!live;
    if(live){const percent=Math.round(job.progress*100);document.querySelector("#meter-fill").style.width=percent+"%";document.querySelector("#progress-label").textContent=percent+"%";}
    const index=Math.max(0,Math.min(this.index,(job?.result_count||1)-1));
    const key=job?[job.id,job.state,job.preview_version,index,job.result_count].join("/"):"empty";
    if(key===this.imageKey)return;
    this.imageKey=key;
    const path=live && job.preview_version ? endpoint(job)+"/preview" : job?.result_count ? endpoint(job)+"/results/"+index : null;
    let blob=null;
    if(path){
      try { const result=await this.ctx.request(path); if(result.status!==204)blob=await result.blob(); }
      catch(e){if(this.imageKey===key)this.imageKey="";throw e;}
    }
    if(this.imageKey!==key)return;
    if(this.objectURL)URL.revokeObjectURL(this.objectURL);
    this.frame.replaceChildren();
    if(!blob){this.frame.append(element("p",live?"PCで生成中です…":"生成した画像がここに表示されます。","quiet"));return;}
    this.objectURL=URL.createObjectURL(blob);
    const img=element("img");img.src=this.objectURL;img.alt=live?"生成途中の画像":"生成結果";img.onclick=()=>showFullscreen(img.src,img.alt);
    const actions=row(button("全画面",()=>showFullscreen(img.src,img.alt)));
    if(!live){
      if(job.result_count>1){
        const prev=button("前へ",()=>this.pick(job,index-1)),next=button("次へ",()=>this.pick(job,index+1));
        prev.disabled=index===0;next.disabled=index===job.result_count-1;
        actions.append(prev,element("small",(index+1)+" / "+job.result_count),next);
      }
      actions.append(button("img2imgに使う",async()=>this.ctx.useImage(await readImage(blob))),button("設定を復元",async()=>{
        const response=await this.ctx.json(endpoint(job)+"/info");
        let info=response.info;
        try { const obj=JSON.parse(info); info=obj.infotexts?.[index] || obj.infotexts?.[0] || info; }catch{}
        const patch=parseGenerationInfo(info); if(!patch)throw Error("生成情報を取得できません");this.ctx.restore(patch,info);
      }));
    }
    this.frame.append(img,actions);
  }
}
