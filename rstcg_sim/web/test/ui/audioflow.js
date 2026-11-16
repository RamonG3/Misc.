// Music and sound effects start, switch and stop with the right screens (no audio errors), and the music
// doesn't drop notes while the page is busy.
// Run from anywhere: node web/test/ui/audioflow.js   (screenshots go to web/test/ui/shots/audioflow/, which is not part of the test)
const path=require('path');const SHOTS=path.join(__dirname,'shots','audioflow');require('fs').mkdirSync(SHOTS,{recursive:true});const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});const page=await b.newPage({viewport:{width:1920,height:1080}});const errs=[];
page.on('pageerror',e=>errs.push(e.message));page.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
await page.addInitScript(()=>{localStorage.clear();localStorage.setItem('rstcg-speed','"fast"');});
await page.goto('file://'+path.join(__dirname,'..','..','dist','arena.html'));await page.waitForFunction(()=>window.__arena);await page.waitForTimeout(200);if(await page.locator('[data-do="tutskip"]').count())await page.click('[data-do="tutskip"]');await page.waitForTimeout(300);
const st=()=>page.evaluate(()=>({ready:RSAudio.ready,playing:RSAudio.playing,layers:RSAudio.layers}));
console.log('before input',JSON.stringify(await st()));
await page.mouse.click(1500,600);await page.waitForTimeout(500);console.log('menu',JSON.stringify(await st()));
for (const n of ['click','move','sweep','lift','back','draw','flip','card','reveal','swing','arrow','magic','punch','hit','soak','shatter','mill','heal','block','reverse','turnYou','turnBot','coins','coin','tear','shake','uncommon','rare','add','remove','save','nope']) await page.evaluate((n)=>RSAudio.sfx(n,{style:'melee',big:true,n:4}),n);
await page.click('[data-do="play"]');await page.click('[data-do="start"]');await page.waitForTimeout(700);await page.click('[data-do="skipintro"]');
await page.waitForFunction(()=>{const u=window.__arena.ui;return u.pending&&u.pending.type==='choose'});await page.waitForTimeout(500);
console.log('duel',JSON.stringify(await st()));
// a busy page (a bot thinking, a big redraw) mustn't make the music drop or delay notes
for(let k=0;k<4;k++){await page.evaluate(()=>{const t=performance.now();while(performance.now()-t<600){}});await page.waitForTimeout(700);}
const hl=await page.evaluate(()=>RSAudio.health());console.log('busy page',JSON.stringify(hl));if(!hl||hl.dropped>0||hl.late>0)errs.push('music dropped or delayed notes while the page was busy: '+JSON.stringify(hl));
await page.evaluate(()=>{const u=window.__arena.ui;u.g.players[1-u.you].deck.length=9;window.__arena.render();});await page.waitForTimeout(3000);
console.log('deck at 9',JSON.stringify(await st()));
await page.evaluate(()=>{const u=window.__arena.ui,bt=u.g.players[1-u.you];bt.deck.length=0;bt.hand.length=0;const k=u.pending.legal.findIndex(a=>a[0]==='pass');u.pending.resolve(u.pending.legal[k]);});
await page.waitForFunction(()=>window.__arena.ui.screen==='result',null,{timeout:20000});await page.waitForTimeout(400);
console.log('result',JSON.stringify(await st()));
await page.click('[data-do="menu"]');await page.waitForTimeout(800);console.log('back to menu',JSON.stringify(await st()));
console.log(errs.length?errs:'no errors');await b.close();})();
