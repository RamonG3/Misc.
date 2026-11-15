// Menus end to end: profile, decks, packs, a duel and its result.
// Run from anywhere: node web/test/ui/flow.js   (screenshots go to web/test/ui/shots/flow/, which is not part of the test)
const path=require('path');const SHOTS=path.join(__dirname,'shots','flow');require('fs').mkdirSync(SHOTS,{recursive:true});const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();const page=await b.newPage({viewport:{width:1920,height:1080}});const errs=[];
page.on('pageerror',e=>errs.push(e.message));page.on('console',m=>{if(m.type()==='error')errs.push(m.text())});
await page.addInitScript(()=>{ if(!sessionStorage.getItem('init')){localStorage.clear();sessionStorage.setItem('init','1');} localStorage.setItem('rstcg-speed','"fast"'); });
await page.goto('file://'+path.join(__dirname,'..','..','dist','arena.html'));await page.waitForFunction(()=>window.__arena);await page.waitForTimeout(200);if(await page.locator('[data-do="tutskip"]').count())await page.click('[data-do="tutskip"]');await page.waitForTimeout(300);
const shot=(n)=>page.screenshot({path:SHOTS+'/'+n+'.png'});const A=(f)=>page.evaluate(f);
const waitChoose=()=>page.waitForFunction(()=>{const u=window.__arena.ui;return u.pending&&u.pending.type==='choose'},null,{timeout:60000});
await page.click('[data-do="how"]');await page.waitForTimeout(200);await shot('how');await page.mouse.click(1880,60);await page.waitForTimeout(150);
console.log('modal closed by backdrop:',await A(()=>!window.__arena.ui.modal));
await page.click('[data-do="settings"]');await page.click('[data-do="reset"]');await shot('settings');await page.keyboard.press('Escape');
await page.click('[data-do="play"]');await page.click('[data-do="opp"]');await shot('opp');await page.click('[data-do="pickbot"][data-v="Ranged"]');
await page.click('[data-do="start"]');await page.waitForTimeout(600);await page.click('[data-do="skipintro"]');await waitChoose();
await page.click('[data-do="duelmenu"]');await shot('duelmenu');await page.click('[data-do="leave"]');await page.waitForTimeout(300);await shot('menu_resume');
await page.waitForTimeout(1500);console.log('left duel; errors so far',errs.length,'screen',await A(()=>window.__arena.ui.screen));
await page.click('[data-do="resume"]');await waitChoose();console.log('resumed ok');
// force a win: the bot is down to its last card with nothing to react with
await A(()=>{const u=window.__arena.ui,g=u.g,bt=g.players[1-u.you];bt.hand.length=0;bt.deck.length=0;bt.shield=null;bt.armor=null;});
console.log('legal',JSON.stringify(await A(()=>window.__arena.ui.pending.legal.map(a=>a[0]))));
await A(()=>{const u=window.__arena.ui;const k=u.pending.legal.findIndex(a=>a[0]==='pass');u.pending.resolve(u.pending.legal[k]);});
await page.waitForTimeout(3000);
console.log('after',JSON.stringify(await A(()=>{const u=window.__arena.ui,g=u.g;return {w:g&&g.winner,you:u.you,lp:g&&g.players.map(p=>p.lp),scr:u.screen,log:g&&g.log.slice(-3).map(e=>e.text)}})));
await page.waitForFunction(()=>window.__arena.ui.screen==='result',null,{timeout:20000});await page.waitForTimeout(800);await shot('victory_mid');await page.waitForTimeout(1800);await shot('victory');
console.log('win:',JSON.stringify(await A(()=>{const r=window.__arena.ui.result;return [r.won,r.before,r.pay,r.after]})));
await page.click('[data-do="seeboard"]');await page.waitForTimeout(300);await shot('finalboard');await page.click('[data-do="results"]');
await page.click('[data-do="play"]');await page.click('[data-do="start"]');await page.waitForTimeout(600);await page.click('[data-do="skipintro"]');await waitChoose();
await page.click('[data-do="duelmenu"]');await page.click('[data-do="concede"]');await page.waitForTimeout(400);await shot('conceded');
console.log('concede:',JSON.stringify(await A(()=>{const r=window.__arena.ui.result;return [r.won,r.conceded,r.before,r.pay,r.after]})));
await page.waitForTimeout(2500);console.log('after concede still on results?',await A(()=>window.__arena.ui.screen), 'board leftovers', await A(()=>document.querySelector('#fx').children.length+document.querySelector('#reveal').children.length));
await page.click('[data-do="menu"]');await page.waitForTimeout(200);await shot('menu_after');
console.log('profile',await A(()=>localStorage.getItem('rstcg-profile-v1')),'saved duel?',await A(()=>!!localStorage.getItem('rstcg-arena-duel-v1')));
console.log(errs.length?errs:'no errors');await b.close();})();
