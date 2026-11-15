// Xbox controller: focus ring and A/B/Y through menu, pick screen and a duel.
// Run from anywhere: node web/test/ui/padtest.js   (screenshots go to web/test/ui/shots/padtest/, which is not part of the test)
// Drives the game with a fake Xbox controller (navigator.getGamepads stubbed).
const path=require('path');const SHOTS=path.join(__dirname,'shots','padtest');require('fs').mkdirSync(SHOTS,{recursive:true});const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();const page=await b.newPage({viewport:{width:1920,height:1080}});const errs=[];
page.on('pageerror',e=>errs.push(e.message));page.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
await page.addInitScript(()=>{localStorage.clear();localStorage.setItem('rstcg-speed','"fast"');
  window.__pad={id:'Xbox Wireless Controller (STANDARD GAMEPAD)',index:0,connected:true,mapping:'standard',axes:[0,0,0,0],buttons:Array.from({length:17},()=>({pressed:false,value:0})),timestamp:0};
  navigator.getGamepads=()=>[window.__pad];});
await page.goto('file://'+path.join(__dirname,'..','..','dist','arena.html'));await page.waitForFunction(()=>window.__arena);await page.waitForTimeout(200);if(await page.locator('[data-do="tutskip"]').count())await page.click('[data-do="tutskip"]');await page.waitForTimeout(400);
const B={A:0,B:1,X:2,Y:3,LB:4,RB:5,MENU:9,VIEW:8,UP:12,DOWN:13,LEFT:14,RIGHT:15};
const press=async(n,hold=160)=>{await page.evaluate((i)=>{window.__pad.buttons[i]={pressed:true,value:1};},B[n]);await page.waitForTimeout(hold);await page.evaluate((i)=>{window.__pad.buttons[i]={pressed:false,value:0};},B[n]);await page.waitForTimeout(120);};
const focus=()=>page.evaluate(()=>{const f=document.querySelector('.gp-focus');return f?(f.dataset.do||'')+':'+(f.dataset.v||f.textContent.trim().slice(0,20)):null;});
const scr=()=>page.evaluate(()=>window.__arena.ui.screen);
await press('DOWN');console.log('menu focus after waking',await focus());
const goTo=async(want,dir)=>{for(let k=0;k<12;k++){const f=await focus();if(f&&f.startsWith(want))return true;await press(dir);}return false;};
await goTo('packs','DOWN');console.log('walked to ->',await focus());
await page.screenshot({path:SHOTS+'/menu.png'});
await press('A');await page.waitForTimeout(800);console.log('A ->',await scr(),await focus());
await press('B');await page.waitForTimeout(800);console.log('B ->',await scr(),await focus());
// to the deck pick and into a duel
await goTo('play','UP');console.log('top ->',await focus());
await press('A');await page.waitForTimeout(800);console.log('A ->',await scr(),await focus());
await page.screenshot({path:SHOTS+'/pick.png'});
await press('Y');await page.waitForTimeout(900);await press('A');   // Y starts the duel, A skips the deal
let turns=0,plays=0,acts=0,popRun=0,shotF=0;
const tEnd=Date.now()+240000;
for (let step=0; Date.now()<tEnd; step++) {
  const st=await page.evaluate(()=>{const u=window.__arena.ui;return {s:u.screen,type:u.pending&&u.pending.type,w:u.g&&u.g.winner,ok:document.querySelectorAll('#board .hc.ok').length,pop:!!document.querySelector('#board .pop .opt'),f:(document.querySelector('.gp-focus')||{}).className||''};});
  if (st.s==='result') break;
  if (!st.type){await page.waitForTimeout(80);continue;}
  if (!st.pop) popRun=0;
  acts++;
  if (st.type==='react'||st.type==='discard'){await press('A');continue;}
  if (st.pop){ popRun=(popRun||0)+1; if (popRun===3) console.log('pop state', JSON.stringify(await page.evaluate(()=>({f:(document.querySelector('.gp-focus')||{}).outerHTML?.slice(0,160), pops:[...document.querySelectorAll('#board .pop')].map(p=>p.innerText.replace(/\s+/g,' ').slice(0,120)), sel:window.__arena.ui.selected, menu:!!window.__arena.ui.menu})))); await press('A'); plays++; await page.waitForTimeout(150); continue; }
  if (st.ok && plays < turns*2+2 && Math.random()<0.8) {        // step through the hand to a playable card, open it
    for (let k=0;k<10;k++){ const f=await page.evaluate(()=>{const f=document.querySelector('.gp-focus');return f&&f.classList.contains('ok');}); if(f)break; await press('RB'); }
    if (!shotF) { shotF=1; await page.waitForTimeout(600); await page.screenshot({path:SHOTS+'/duel_focus.png'}); }
    await press('A'); await page.waitForTimeout(150);
    if (shotF===1) { shotF=2; await page.screenshot({path:SHOTS+'/duel_popover.png'}); }
    continue;
  }
  await press('Y'); turns++; await page.waitForTimeout(150);
}
if (await scr()!=='result') console.log('STUCK', JSON.stringify(await page.evaluate(()=>{const u=window.__arena.ui;return {f:(document.querySelector('.gp-focus')||{}).outerHTML?.slice(0,200),sel:u.selected,menu:!!u.menu,type:u.pending&&u.pending.type,legal:u.pending&&u.pending.legal&&u.pending.legal.map(a=>a.join(' ')),pops:[...document.querySelectorAll('#board .pop')].map(p=>p.innerText.replace(/\s+/g,' ').slice(0,140)),ok:[...document.querySelectorAll('#board .hc.ok')].map(e=>e.dataset.zoom)};})));
console.log('duel by controller: screen',await scr(),'turns ended',turns,'cards played',plays,'decisions',acts);
await page.waitForTimeout(2500);await page.screenshot({path:SHOTS+'/result.png'});
await press('B');await page.waitForTimeout(800);console.log('B on results ->',await scr());
// a dialog: Settings via Menu, move inside, B closes
await press('MENU');await page.waitForTimeout(200);console.log('Menu ->',await page.evaluate(()=>window.__arena.ui.modal),await focus());
await press('DOWN');console.log('inside dialog ->',await focus());await page.screenshot({path:SHOTS+'/settings.png'});
await press('B');console.log('B ->',await page.evaluate(()=>window.__arena.ui.modal));
// mouse movement hands control back
await page.mouse.move(400,400);await page.mouse.move(700,500);await page.waitForTimeout(100);console.log('after mouse: hint?',await page.evaluate(()=>!!document.getElementById('padhint')),'ring?',await focus());
console.log(errs.length?errs:'no errors');await b.close();})();
