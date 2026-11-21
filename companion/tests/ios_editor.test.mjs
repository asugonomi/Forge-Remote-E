import test from 'node:test';
import assert from 'node:assert/strict';
import {deflateSync} from 'node:zlib';
import {adjustWeight,applyOrder,insertPrompt,promptLayout,rankedChoices} from '../ios_static/editor.js';
import {localPngInfo,parseGenerationInfo} from '../ios_static/gallery.js';

test('dictionary inserts exactly the formatted text and one comma and space',()=>{
  const literal='cape \\(blue\\)';
  const out=insertPrompt('sky, ca  ,\tnext',7,7,literal,true);
  assert.deepEqual(out,{text:'sky, '+literal+', next',start:5+literal.length+2,end:5+literal.length+2});
  const next=insertPrompt(out.text,out.start,out.end,'evening',false);
  assert.equal(next.text,'sky, '+literal+', evening, next');
  assert.equal(next.text.slice(next.start),'next');
  assert.deepEqual(insertPrompt('red fox, tail',13,13,'red_fox'),{text:'red fox, tail',start:0,end:7});
  assert.equal(insertPrompt('edge\\,',6,6,'blue').text,'edge\\,, blue, ');
  assert.equal(insertPrompt('alpha\n  a\n\nend',8,9,'red,').text,'alpha\n  red, \n\nend');
  assert.equal(insertPrompt('abc, def',1,1,'blue').text,'abc, blue, def');
  assert.equal(insertPrompt('sky',3,3,'<lora:some_tag:0.8>').text,'sky, <lora:some_tag:0.8>');
});
test('tag ranking retains PC aliases, stable ties and unknown count last',()=>{
  const rows=[{name:'a',insert:'exact_prompt',count:23},{name:'b',insert:'b',count:23},{name:'c',insert:'c',count:55}];
  assert.deepEqual(rankedChoices(rows,[{name:'a',insert:'different'},{name:'d',insert:'local'}]).map(r=>[r.name,r.insert]),[['c','c'],['a','exact_prompt'],['b','b'],['d','local']]);
  assert.equal(rankedChoices(Array.from({length:50},(_,i)=>({name:String(i),insert:String(i),count:i}))).length,32);
});
test('prompt slots and blank rows retain CRLF, escaped, grouped and quoted syntax',()=>{
  const s='  red\\,blue,\t(foo,\r\nbar:1.2)\r\n\r\n <lora:my,name:1>, "g,h"  ';
  const l=promptLayout(s);
  assert.deepEqual(l.slots.map(x=>x.text),['red\\,blue','(foo,\r\nbar:1.2)','<lora:my,name:1>','"g,h"']);
  assert.deepEqual(l.lines.map(i=>[i.number,i.positions]),[[1,[0,1]],[3,[]],[4,[2,3]]]);
  assert.equal(applyOrder(l,[2,0,1,3],2).text,'  <lora:my,name:1>,\tred\\,blue\r\n\r\n (foo,\r\nbar:1.2), "g,h"  ');
  assert.throws(()=>applyOrder(l,[0,0,2,3],0));
});
test('decimal weight edits do not accumulate floating errors',()=>{
  assert.equal(adjustWeight('bird',1),'(bird:1.1)');
  assert.equal(adjustWeight('(bird:1.1)',-1),'bird');
  assert.equal(adjustWeight('<lora:bird:.95>',1),'<lora:bird:1.05>');
  assert.equal(adjustWeight('(x:-0.05)',1),'(x:0.05)');
});
test('PNG Info restores prompts, actual values and hires; keeps a large seed precise',()=>{
  const raw='blue,\nwindow\nNegative prompt: blur,\nnoise\nSteps: 22, Sampler: Euler a, Schedule type: Karras, Seed: 91999999999999999, CFG scale: 5.5, Size: 512x768, Hires upscale: 1.5, Hires upscaler: Latent, Hires CFG Scale: 4, ADetailer prompt: "a, b"';
  const patch=parseGenerationInfo(raw);
  assert.equal(patch.prompt,'blue,\nwindow');assert.equal(patch.negative_prompt,'blur,\nnoise');
  assert.equal(patch.seed,'91999999999999999');assert.equal(patch.width,512);assert.equal(patch.hr_scale,1.5);assert.equal(patch.enable_hr,true);
  assert.equal(parseGenerationInfo('plain text'),null);
  assert.deepEqual(parseGenerationInfo('Negative prompt: fog\nSteps: 1, Seed: 12'),{prompt:'',negative_prompt:'fog',steps:1,seed:12,enable_hr:false});
});
test('local PNG supports plain, Unicode and compressed metadata without any upload',async()=>{
  const sig=Buffer.from([137,80,78,71,13,10,26,10]);
  function png(type,bytes){
    const header=Buffer.alloc(8);header.writeUInt32BE(bytes.length);header.write(type,4);
    return new Blob([sig,header,bytes,Buffer.alloc(4)]);
  }
  const text='窓と青空\nSteps: 12';
  assert.equal(await localPngInfo(png('tEXt',Buffer.from('parameters\0blue sky','latin1'))),'blue sky');
  assert.equal(await localPngInfo(png('iTXt',Buffer.concat([Buffer.from('parameters\0\0\0\0\0'),Buffer.from(text)]))),text);
  const internationalHeader=Buffer.concat([Buffer.from('parameters\0'),Buffer.from([1,0,0,0])]);
  assert.equal(await localPngInfo(png('iTXt',Buffer.concat([internationalHeader,deflateSync(Buffer.from(text))]))),text);
  assert.equal(await localPngInfo(png('zTXt',Buffer.concat([Buffer.from('parameters\0\0'),deflateSync(Buffer.from('blue sky'))]))),'blue sky');
  assert.equal(await localPngInfo(new Blob(['text'])), '');
});
