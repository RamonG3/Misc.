// The guided tutorial plays all its steps and grants the free pack. Arg: pc | phone.
// Run from anywhere: node web/test/ui/tuttest.js   (screenshots go to web/test/ui/shots/tuttest/, which is not part of the test)
const path=require('path');const SHOTS=path.join(__dirname,'shots','tuttest');require('fs').mkdirSync(SHOTS,{recursive:true});const {chromium}=require('playwright');
const MODE=process.argv[2]||'pc';
(async()=>{const b=await chromium.launch();
const ctx=await b.newContext(MODE==='phone'?{viewport:{width:891,height:411},deviceScaleFactor:3.5,isMobile:true,hasTouch:true}:{viewport:{width:1920,height:1080}});
const page=await ctx.newPage();const errs=[];page.on('pageerror',e=>errs.push(e.message));page.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
await page.addInitScript(()=>{if(!sessionStorage.getItem('x')){localStorage.clear();sessionStorage.setItem('x',1);}});
await page.goto('file://'+path.join(__dirname,'..','..','dist','arena.html'));await page.waitForFunction(()=>window.__arena);await page.waitForTimeout(600);
await page.screenshot({path:`${SHOTS}/${MODE}_00_welcome.png`});
await page.click('.modal [data-do="tutorial"]');
await page.waitForTimeout(800);await page.click('[data-do="skipintro"]').catch(()=>{});
const t0=Date.now();let shots=0,steps=[];
const center=async sel=>{const bb=await page.locator(sel).first().boundingBox();return bb&&{x:bb.x+bb.width/2,y:bb.y+bb.height/2}};
while(Date.now()-t0<180000){
  const st=await page.evaluate(()=>{const u=window.__arena.ui;const c=document.querySelector('.coach:not(.tip) b');return{screen:u.screen,title:c&&c.textContent,next:!!document.querySelector('.tut-next'),pend:u.pending&&u.pending.type,legal:u.pending&&u.pending.legal,anim:u.g?0:0,res:!!(u.result)}});
  if(st.screen==='result'){break;}
  if(st.title&&!steps.includes(st.title)){steps.push(st.title);if(shots<30){await page.waitForTimeout(250);await page.screenshot({path:`${SHOTS}/${MODE}_${String(++shots).padStart(2,'0')}.png`});}}
  if(st.next){await page.click('.coach .tut-next');await page.waitForTimeout(250);continue;}
  if(st.pend==='choose'&&st.legal&&st.legal.length){
    const a=st.legal[0];
    if(a[0]==='play'){const from=await center(`.hc[data-zoom="${a[1]}"]`);const to=await center('[data-at="'+(await page.evaluate(()=>window.__arena.ui.you))+':mat"]');
      if(MODE==='phone'){await page.evaluate(()=>{});}
      await page.mouse.move(from.x,from.y);await page.mouse.down();await page.mouse.move(from.x+10,from.y-10,{steps:2});await page.mouse.move(to.x,to.y-20,{steps:10});await page.mouse.up();}
    else if(a[0]==='pass'){await page.click('.endturn');}
    else {await page.click(`.ab[data-v="${a[0]}"]`);}
    await page.waitForTimeout(400);continue;}
  await page.waitForTimeout(200);
}
await page.waitForTimeout(1200);await page.screenshot({path:`${SHOTS}/${MODE}_90_result.png`});
console.log('steps seen',steps.length,steps.join(' | '));
const r=await page.evaluate(()=>({res:window.__arena.ui.result&&{t:window.__arena.ui.result.tutorial,won:window.__arena.ui.result.won},prof:{tut:window.__arena.profile.tutorial,gp:window.__arena.profile.gp,w:window.__arena.profile.wins}}));console.log(JSON.stringify(r));
if(await page.locator('[data-do="tutpack"]').count()){await page.click('[data-do="tutpack"]');await page.waitForTimeout(1500);await page.screenshot({path:`${SHOTS}/${MODE}_91_pack.png`});
  for(let k=0;k<40;k++){const n=await page.locator('[data-do="packsum"]').count();if(n){await page.click('[data-do="packsum"]');break;}const f=page.locator('.po:not(.up)');if(await f.count()){await f.first().click({force:true});}else{const any=page.locator('[data-do="tear"],[data-do="openpack"],.pk');if(await any.count())await any.first().click({force:true});}await page.waitForTimeout(500);}
  await page.waitForTimeout(800);await page.screenshot({path:`${SHOTS}/${MODE}_92_summary.png`});
  console.log('after pack',JSON.stringify(await page.evaluate(()=>({screen:window.__arena.ui.screen,packs:window.__arena.profile.packs,gift:window.__arena.profile.tutorialPack,coll:Object.values(window.__arena.profile.coll).reduce((a,b)=>a+b,0)}))));}
console.log(errs.length?errs:'no errors');await b.close();})();
