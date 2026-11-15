// Cape progress ticks animate and sound as capes are earned.
// Run from anywhere: node web/test/ui/capetick.js   (screenshots go to web/test/ui/shots/capetick/, which is not part of the test)
const path=require('path');const SHOTS=path.join(__dirname,'shots','capetick');require('fs').mkdirSync(SHOTS,{recursive:true});const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});const page=await b.newPage({viewport:{width:1920,height:1080}});const errs=[];
page.on('pageerror',e=>errs.push(e.message));page.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
await page.addInitScript(()=>{localStorage.clear();});
await page.goto('file://'+path.join(__dirname,'..','..','dist','arena.html'));await page.waitForFunction(()=>window.__arena);await page.waitForTimeout(200);if(await page.locator('[data-do="tutskip"]').count())await page.click('[data-do="tutskip"]');
await page.mouse.click(1500,600);await page.waitForTimeout(300);
await page.click('[data-do="play"]');await page.click('[data-do="start"]');await page.waitForTimeout(700);await page.click('[data-do="skipintro"]');
await page.evaluate(()=>{window.__ct={cape:0,capebot:0,stamp:[],sh:0};new MutationObserver(ms=>{for(const m of ms)for(const n of m.addedNodes){if(!n.classList)continue;
  if(n.classList.contains('float')&&(n.classList.contains('cape')||n.classList.contains('capebot'))){window.__ct[n.classList.contains('cape')?'cape':'capebot']++;window.__ct.last=n.textContent;window.__ct.pending=1;}
  if(n.classList.contains('stamp'))window.__ct.stamp.push(n.textContent);}}).observe(document.getElementById('fx'),{childList:true});});
const t0=Date.now();let shots=0;
while(Date.now()-t0<150000){
  const s=await page.evaluate(()=>{const u=window.__arena.ui;const g=u.g;
    if(u.pending&&u.pending.type==='react')u.pending.resolve(null);
    else if(u.pending&&u.pending.type==='discard')u.pending.resolve(g.players[u.you].hand[0]);
    else if(u.pending&&u.pending.legal){const L=u.pending.legal;const pref=L.filter(a=>['claim','play','quest'].includes(a[0]));const non=L.filter(a=>a[0]!=='pass');const pick=pref.length?pref[0]:non.length&&Math.random()<0.85?non[Math.floor(Math.random()*non.length)]:L[L.length-1];u.pending.resolve(pick);}
    const p=window.__ct.pending;window.__ct.pending=0;return {screen:u.screen,p,ct:window.__ct};});
  if(s.p&&shots<6){shots++;await page.waitForTimeout(60);await page.screenshot({path:`${SHOTS}/tick${shots}.png`,clip:{x:0,y:340,width:1920,height:250}});}
  if(s.screen==='result')break;
  await page.waitForTimeout(s.p?400:120);
}
console.log(JSON.stringify(await page.evaluate(()=>window.__ct)));
console.log(errs.length?errs:'no errors');await b.close();})();
