// The cloud save, away from the page and the network: node web/test/cloud_test.js [worlds]   (ends "all passed")
//
//   1. same(): the same progress whatever order the database hands its keys back in.
//   2. merge(): what one device changed, applied on top of what another saved.
//   3. sync() and choose(), step by step, against a pretend server: first sign-in, a second device, the
//      "which progress do you keep?" question, two devices changing things at once, a player who keeps
//      playing while a save is on its way.
//   4. Many worlds of three devices doing random things in a random order, with every network answer arriving
//      late and out of step. At the end every device and the server must hold the same progress, and nothing
//      earned on any device may be lost or counted twice. (Start GP is high there, so nobody can spend the same
//      GP on two devices; that is possible in the game, and merge() then stops at zero.)
const C = require('../cloudsave.js');

let failed = 0;
const check = (name, ok, detail) => { console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); if (!ok) failed++; };
const clone = (x) => JSON.parse(JSON.stringify(x));
// what Postgres does to a jsonb document: keys come back shortest first, then in byte order
const jsonb = (x) => (Array.isArray(x) ? x.map(jsonb) : x && typeof x === 'object'
  ? Object.fromEntries(Object.keys(x).sort((a, b) => a.length - b.length || (a < b ? -1 : a > b ? 1 : 0)).map((k) => [k, jsonb(x[k])])) : x);
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

const START = () => ({ gp: 0, wins: 0, losses: 0, paid: null, coll: { 'Bronze Sword': 2, "Iban's Staff": 1 },
  decks: [{ id: 'st-melee', name: 'Melee', cards: { 'Bronze Sword': 2, 'Bronze Bar': 58 }, cape: 'Attack Cape', starter: true }] });

// ---------- a pretend server, and devices ----------
// Each network call waits in `queue` until the test lets it through, so answers can arrive in any order.
function world() {
  const w = { row: null, clock: 0, queue: [], hold: false, saves: 0, loads: 0 };
  const net = (fn) => (w.hold ? new Promise((res) => w.queue.push(() => res(fn()))) : Promise.resolve().then(fn));
  w.load = (since) => net(() => { w.loads++; return w.row && w.row.rev > (since || 0) ? clone(w.row) : null; });
  w.save = (data, rev) => net(() => {
    w.saves++;
    if (rev == null ? !w.row : w.row && w.row.rev === rev) {
      w.row = { rev: (rev || 0) + 1, at: ++w.clock, data: jsonb(clone(data)) };
      return { ok: true, rev: w.row.rev, at: w.row.at };
    }
    return w.row ? Object.assign({ ok: false }, clone(w.row)) : { ok: false, rev: null };
  });
  w.release = (i = 0) => { const [go] = w.queue.splice(i, 1); if (go) go(); };
  w.device = (profile, uid = 'acct') => {
    const d = { profile: clone(profile || START()), mark: null, uid, puts: 0 };
    d.dev = { uid, load: w.load, save: w.save,
      get: () => ({ data: clone(d.profile), mark: d.mark }),
      put: (data, mark) => { if (data) { d.profile = clone(data); d.puts++; } d.mark = mark; },
      untouched: (data) => C.same(data, START()) };
    d.sync = () => C.sync(d.dev);
    return d;
  };
  return w;
}
const tick = () => new Promise((r) => setImmediate(r));

(async () => {
  // ---------- 1. same() ----------
  {
    const a = START();
    check('same: a save and its copy from the database', C.same(a, jsonb(clone(a))));
    check('same: keys in another order', C.same({ a: 1, b: { c: [1, { d: 2, e: 3 }] } }, { b: { c: [1, { e: 3, d: 2 }] }, a: 1 }));
    check('same: missing equals null', C.same({ a: 1, paid: null }, { a: 1 }) && C.same({ a: 1 }, { a: 1, paid: undefined }));
    check('same: a different count is different', !C.same({ coll: { X: 1 } }, { coll: { X: 2 } }));
    check('same: an extra card is different', !C.same({ coll: { X: 1 } }, { coll: { X: 1, Y: 1 } }));
    check('same: a list in another order is different', !C.same([1, 2], [2, 1]) && !C.same([1, 2], [1, 2, 3]));
    check('same: 0, false and "" are not "missing"', !C.same({ a: 0 }, {}) && !C.same({ a: false }, {}) && !C.same({ a: '' }, {}));
    check('same: a list is not an object', !C.same([], {}) && !C.same({ a: [] }, { a: {} }));
  }

  // ---------- 2. merge() ----------
  {
    const base = Object.assign(START(), { gp: 400, wins: 3, losses: 1, packs: 2, online: { wins: 1, losses: 0 }, tips: { a: true, b: true },
      onlinePaid: ['m1'], tutorial: 'skipped', lastPack: [{ name: 'X' }] });
    base.decks.push({ id: 'd1', name: 'One', cards: { A: 60 }, cape: 'Attack Cape' }, { id: 'd2', name: 'Two', cards: { B: 60 }, cape: 'Magic Cape' },
      { id: 'd3', name: 'Three', cards: { C: 60 }, cape: 'Magic Cape' }, { id: 'd4', name: 'Four', cards: { D: 60 }, cape: 'Magic Cape' });
    const L = clone(base), R = jsonb(clone(base));
    check('merge: nothing changed anywhere gives the same save', C.same(C.merge(base, L, R), base));

    // here: won a duel, opened a pack, edited d1, deleted d2 and d3, made d5, saw tip c, cleared tip a
    L.gp += 100 - 200; L.wins += 1; L.packs += 1; L.coll['Bronze Sword'] += 1; L.coll['Rune Scimitar'] = 2; L.lastPack = [{ name: 'Rune Scimitar' }];
    L.decks.find((d) => d.id === 'd1').name = 'One (mine)';
    L.decks = L.decks.filter((d) => d.id !== 'd2' && d.id !== 'd3');
    L.decks.push({ id: 'd5', name: 'Five', cards: { E: 60 }, cape: 'Attack Cape' });
    L.tips.c = true; delete L.tips.a;
    L.onlinePaid.push('m2'); L.online.wins += 1;
    check('merge: only this device changed gives this device\'s save', C.same(C.merge(base, L, jsonb(clone(base))), L));
    check('merge: only the other device changed gives theirs', C.same(C.merge(base, clone(base), jsonb(clone(L))), L));

    // there: lost two duels, opened a pack, edited d1 too, edited d3, deleted d4, made d6, saw tip d, finished the tutorial
    R.gp += 50 + 50 - 200; R.losses += 2; R.packs += 1; R.coll['Bronze Sword'] += 2; R.coll['Mithril Mace'] = 1; R.lastPack = [{ name: 'Mithril Mace' }];
    R.decks.find((d) => d.id === 'd1').name = 'One (theirs)';
    R.decks.find((d) => d.id === 'd3').cards = { C: 59, Z: 1 };
    R.decks = R.decks.filter((d) => d.id !== 'd4');
    R.decks.push({ id: 'd6', name: 'Six', cards: { F: 60 }, cape: 'Attack Cape' });
    R.tips.d = true; R.tutorial = 'done'; R.onlinePaid.push('m3'); R.online.losses += 1;

    const m = C.merge(base, L, R);
    const deck = (id) => m.decks.find((d) => d.id === id);
    check('merge: GP is theirs plus what was earned and spent here', m.gp === 400 - 100 - 100, `gp ${m.gp}`);
    check('merge: wins, losses and packs add up', m.wins === 4 && m.losses === 3 && m.packs === 4, `${m.wins}-${m.losses}, ${m.packs} packs`);
    check('merge: the online record adds up', m.online.wins === 2 && m.online.losses === 1);
    check('merge: cards from both packs', m.coll['Bronze Sword'] === 5 && m.coll['Rune Scimitar'] === 2 && m.coll['Mithril Mace'] === 1 && m.coll["Iban's Staff"] === 1, JSON.stringify(m.coll));
    check('merge: a deck changed on both keeps this device\'s', deck('d1').name === 'One (mine)');
    check('merge: a deck deleted here and untouched there stays deleted', !deck('d2'));
    check('merge: a deck deleted here but changed there comes back', deck('d3') && deck('d3').cards.Z === 1);
    check('merge: a deck deleted there and untouched here goes', !deck('d4'));
    check('merge: decks made on either device are both kept', !!deck('d5') && !!deck('d6'));
    check('merge: the starter deck is still there, once', m.decks.filter((d) => d.id === 'st-melee').length === 1 && m.decks.length === 5, m.decks.map((d) => d.id).join(' '));
    check('merge: tips from both, minus the one cleared here', !m.tips.a && m.tips.b && m.tips.c && m.tips.d, JSON.stringify(m.tips));
    check('merge: paid duels from both', ['m1', 'm2', 'm3'].every((x) => m.onlinePaid.includes(x)) && m.onlinePaid.length === 3);
    check('merge: a value changed only there is theirs', m.tutorial === 'done');
    check('merge: a value changed on both is this device\'s', m.lastPack[0].name === 'Rune Scimitar');
    check('merge: the result shares nothing with its inputs', m.decks.every((d) => !L.decks.includes(d) && !R.decks.includes(d)));

    const spent = C.merge({ gp: 200 }, { gp: 0 }, { gp: 0 });
    check('merge: GP spent twice stops at zero', spent.gp === 0);
    const none = C.merge({ decks: [{ id: 'x', n: 1 }, { id: 'y', n: 1 }] }, { decks: [{ id: 'x', n: 1 }] }, { decks: [{ id: 'y', n: 1 }] });
    check('merge: never leaves a player with no deck', none.decks.length === 1);
    const lots = C.merge({}, { onlinePaid: Array.from({ length: 30 }, (_, i) => 'a' + i) }, { onlinePaid: Array.from({ length: 30 }, (_, i) => 'b' + i) });
    check('merge: the paid list stays short', lots.onlinePaid.length === 40 && lots.onlinePaid.includes('a29'));
    // a starter deck added later is given once per player: the list of ones had only grows, from either side
    const g = C.merge({ gifts: [] }, { gifts: ['Necromancy'] }, { gifts: [] });
    const g2 = C.merge({}, { gp: 1 }, { gifts: ['Necromancy'] }), g3 = C.merge({ gifts: ['Necromancy'] }, { gifts: ['Necromancy', 'X'] }, { gifts: ['Necromancy', 'Y'] });
    check('merge: gifts had on either side stay had', JSON.stringify(g.gifts) === '["Necromancy"]' && JSON.stringify(g2.gifts) === '["Necromancy"]' && g3.gifts.length === 3);
    // Story Mode: rewards and finished roads add up from both; where you are goes by the usual rule
    const sb = { story: { main: { ch: 'ch1', node: 'swamp', i: 3 }, roads: { ch1: [] }, claimed: {} } };
    const sl = clone(sb); sl.story.main.i = 9; sl.story.roads.ch1 = ['A']; sl.story.claimed['ch1:A'] = 'Claws of Guthix';
    const sr = clone(sb); sr.story.roads.ch1 = ['B']; sr.story.claimed['ch1:B'] = 'Shield of Arrav'; sr.story.replay = { ch: 'ch1', node: 'b_road', i: 1 };
    const sm = C.merge(sb, sl, jsonb(sr)).story;
    check('merge: story rewards from both devices', sm.claimed['ch1:A'] && sm.claimed['ch1:B'], JSON.stringify(sm.claimed));
    check('merge: finished roads from both devices', sm.roads.ch1.join() === 'A,B', JSON.stringify(sm.roads));
    check('merge: where you are in the story is this device\'s when it moved here', sm.main.i === 9);
    check('merge: a replay started there is kept', sm.replay && sm.replay.node === 'b_road');
    check('merge: an old save with no story merges as it is', C.same(C.merge({ gp: 1 }, { gp: 1 }, { gp: 1 }), { gp: 1 }));
    const reset = C.merge(base, START(), jsonb(clone(base)));
    check('merge: a reset here resets the save', reset.gp === 0 && reset.wins === 0 && reset.losses === 0 && !reset.packs && !reset.online.wins && !reset.tutorial && !reset.lastPack &&
      !Object.keys(reset.tips).length && C.same(reset.coll, START().coll) && C.same(reset.decks, START().decks), JSON.stringify(reset).slice(0, 160));
  }

  // ---------- 3. sync() and choose(), step by step ----------
  {
    const w = world();
    const pc = w.device();
    pc.profile.gp = 300; pc.profile.wins = 3;
    let r = await pc.sync();
    check('first sign-in: this device\'s progress starts the account', r.did === 'saved' && w.row.rev === 1 && w.row.data.gp === 300 && pc.mark.rev === 1);
    r = await pc.sync();
    check('nothing new: nothing is saved again', r.did === 'same' && w.saves === 1);

    const phone = w.device();                                    // a brand-new game on the phone
    r = await phone.sync();
    check('a new device takes the account\'s progress without asking', r.did === 'loaded' && phone.profile.gp === 300 && phone.mark.rev === 1);

    phone.profile.gp += 100; phone.profile.wins += 1;            // play on the phone
    r = await phone.sync();
    check('progress made on one device is saved', r.did === 'saved' && w.row.rev === 2 && w.row.data.gp === 400);
    r = await pc.sync();
    check('and the other device picks it up', r.did === 'loaded' && pc.profile.gp === 400 && pc.profile.wins === 4 && pc.mark.rev === 2);

    pc.profile.gp += 50; pc.profile.losses += 1;                 // both play before either syncs
    phone.profile.gp += 100; phone.profile.wins += 1;
    await pc.sync();
    r = await phone.sync();
    check('both devices changed: the second one merges', r.did === 'merged' && phone.profile.gp === 550 && phone.profile.wins === 5 && phone.profile.losses === 1);
    r = await pc.sync();
    check('and they end up the same', r.did === 'loaded' && C.same(pc.profile, phone.profile) && C.same(pc.profile, w.row.data) && w.row.rev === 4);

    const old = w.device();                                      // a device with progress of its own signs in
    old.profile.gp = 75; old.profile.losses = 2;
    const puts = old.puts, saves = w.saves;
    r = await old.sync();
    check('a device with its own progress is asked which to keep', !!r.ask && r.ask.data.gp === 550 && r.ask.rev === 4);
    check('asking changes nothing on either side', old.puts === puts && w.saves === saves && old.mark === null && old.profile.gp === 75);
    r = await C.choose(old.dev, 'account', r.ask);
    check('"keep my account\'s": the device takes the account\'s progress', r.did === 'loaded' && old.profile.gp === 550 && old.mark.rev === 4 && w.saves === saves);

    const other = w.device();
    other.profile.gp = 990; other.profile.wins = 9;
    r = await other.sync();
    const asked = r.ask;
    pc.profile.gp += 100; await pc.sync();                       // the account moves on while they are choosing
    r = await C.choose(other.dev, 'device', asked);
    check('"keep this device\'s", but the account changed meanwhile: asked again', !!r.ask && r.ask.rev === 5 && r.ask.data.gp === 650 && w.row.data.gp === 650);
    r = await C.choose(other.dev, 'device', r.ask);
    check('"keep this device\'s": it replaces the account\'s progress', r.did === 'saved' && w.row.data.gp === 990 && w.row.rev === 6 && other.mark.rev === 6);
    r = await pc.sync();
    check('and the other devices follow', r.did === 'loaded' && pc.profile.gp === 990 && pc.profile.wins === 9);
  }
  {
    const w = world();                                           // the account was started by a brand-new game
    const fresh = w.device();
    await fresh.sync();
    const real = w.device();
    real.profile.gp = 500; real.profile.coll['Rune Scimitar'] = 1;
    const r = await real.sync();
    check('an account with nothing in it takes a device\'s real progress without asking', r.did === 'saved' && w.row.data.gp === 500 && w.row.rev === 2);
    const again = w.device(real.profile);                        // the same progress on another device (a restored backup, say)
    const r2 = await again.sync();
    check('a device that already matches the account just joins', r2.did === 'same' && again.mark.rev === 2 && w.saves === 2);
  }
  {
    const w = world();                                           // the player keeps playing while the network is slow
    const d = w.device();
    d.profile.gp = 100;
    await d.sync();
    w.hold = true;
    d.profile.gp += 100;
    const p = d.sync();
    await tick();
    d.profile.gp += 50;                                          // a duel ends while the save is on its way
    w.release(); await tick();
    let r = await p;
    check('a change made during a save is not lost', r.did === 'saved' && w.row.data.gp === 200 && d.profile.gp === 250);
    w.hold = false;
    r = await d.sync();
    check('and it goes up with the next save', r.did === 'saved' && w.row.data.gp === 250);

    const e = w.device(); await e.sync();                        // a second device, in step
    e.profile.gp += 1000; await e.sync();                        // it saves 1250
    w.hold = true;
    const q = d.sync();                                          // d has nothing new: it asks what's new
    await tick();
    d.profile.gp += 100; d.profile.wins = 1;                     // ...and wins a duel before the answer comes
    w.release(); await tick(); w.release(); await tick();
    r = await q;
    check('a change made while loading is merged, not overwritten', r.did === 'merged' && d.profile.gp === 1350 && d.profile.wins === 1 && w.row.data.gp === 1350, `gp ${d.profile.gp}`);
    w.hold = false;

    w.row = null;                                                // the account's save disappears (deleted in the dashboard)
    d.profile.gp += 5;
    r = await d.sync();
    check('a save that vanished from the server is started again', r.did === 'saved' && w.row && w.row.data.gp === 1355 && d.mark.rev === 1);
  }
  {
    const w = world();                                           // two devices sign in to a new account at the same moment
    const a = w.device(), b = w.device();
    w.hold = true;
    const pa = a.sync(), pb = b.sync();
    for (let i = 0; i < 12; i++) { await tick(); w.release(Math.max(0, w.queue.length - 1)); }
    const ra = await pa, rb = await pb;
    check('two devices starting an account at once both end up in step', w.row.rev === 1 && a.mark.rev === 1 && b.mark.rev === 1, `${ra.did}, ${rb.did}`);
  }

  // ---------- 4. many worlds ----------
  {
    const worlds = +process.argv[2] || 400;
    const CARDS = ['Bronze Sword', "Iban's Staff", 'Rune Scimitar', 'Mithril Mace', 'Yew Shortbow', "Green D'hide Body", 'Fire Rune'];
    let diverged = 0, lost = 0, deckFault = 0, steps = 0, merges = 0, thrown = 0, crowded = 0, worst = '';
    const why = [];
    for (let seed = 1; seed <= worlds; seed++) {
      const rnd = rng(seed), pick = (n) => Math.floor(rnd() * n);
      const w = world();
      const start = Object.assign(START(), { gp: 100000 });
      const devs = [0, 1, 2].map(() => w.device(start));
      for (const d of devs) d.dev.untouched = (data) => C.same(data, start);
      await devs[0].sync(); await devs[1].sync(); await devs[2].sync();
      w.hold = true;
      const want = { gp: start.gp, wins: 0, losses: 0, packs: 0, coll: clone(start.coll), online: { wins: 0, losses: 0 }, tips: new Set(), paid: new Set(),
        made: new Set(), deleted: new Set(), edits: new Map() };
      let ids = 0;
      const running = new Map();
      const act = (d, i) => {
        const p = d.profile;
        switch (pick(9)) {
          case 0: p.gp += 100; p.wins += 1; want.gp += 100; want.wins += 1; break;
          case 1: p.gp += 50; p.losses += 1; want.gp += 50; want.losses += 1; break;
          case 2: {
            p.gp -= 200; p.packs = (p.packs || 0) + 1; want.gp -= 200; want.packs += 1;
            p.lastPack = [];
            for (let k = 0; k < 6; k++) { const c = CARDS[pick(CARDS.length)]; p.coll[c] = (p.coll[c] || 0) + 1; want.coll[c] = (want.coll[c] || 0) + 1; p.lastPack.push({ name: c }); }
            break;
          }
          case 3: { const id = `d${i}-${++ids}`; p.decks.push({ id, name: 'Deck ' + ids, cards: { [CARDS[pick(CARDS.length)]]: 60 }, cape: 'Attack Cape' }); want.made.add(id); break; }
          case 4: {
            const mine = p.decks.filter((x) => !x.starter);
            if (!mine.length) break;
            const dk = mine[pick(mine.length)];
            dk.name = `edited ${i}.${++ids}`;
            want.edits.set(dk.id, (want.edits.get(dk.id) || new Set()).add(i)); want['last' + dk.id + i] = dk.name;
            break;
          }
          case 5: { const mine = p.decks.filter((x) => !x.starter); if (mine.length) { const dk = mine[pick(mine.length)]; p.decks = p.decks.filter((x) => x !== dk); want.deleted.add(dk.id); } break; }
          case 6: { const t = 'tip' + pick(8); p.tips = p.tips || {}; p.tips[t] = true; want.tips.add(t); break; }
          case 7: { const m = `m${i}-${++ids}`; p.gp += 100; p.online = p.online || { wins: 0, losses: 0 }; p.online.wins += 1; (p.onlinePaid = p.onlinePaid || []).push(m); want.gp += 100; want.online.wins += 1; want.paid.add(m); break; }
          default: p.tutorial = 'done'; want.tutorial = true;
        }
      };
      const visit = (d, i) => { if (running.has(i)) return; const p = d.sync().then((r) => { if (r.did === 'merged') merges++; if (r.ask) { thrown++; why.push('asked'); } },
        (e) => { if (/kept changing/.test(e.message)) crowded++; else { thrown++; why.push(e.message); } }).finally(() => running.delete(i)); running.set(i, p); };
      for (let t = 0; t < 120; t++) {
        const i = pick(3), x = rnd();
        if (x < 0.45) act(devs[i], i);
        else if (x < 0.7) visit(devs[i], i);
        else if (w.queue.length) w.release(pick(w.queue.length));
        await tick();
        steps++;
      }
      // let everything arrive, then visit until nobody has anything new
      w.hold = false;
      while (w.queue.length) { w.release(); await tick(); }
      await Promise.all(running.values());
      for (let round = 0, quiet = 0; round < 12 && quiet < 3; round++) {
        quiet = 0;
        for (const d of devs) { const r = await d.sync(); if (r.did === 'same') quiet++; }
      }
      const srv = w.row.data, bad = [];
      if (!devs.every((d) => C.same(d.profile, srv))) { diverged++; bad.push('devices differ'); }
      if (srv.gp !== want.gp) bad.push(`gp ${srv.gp} not ${want.gp}`);
      if (srv.wins !== want.wins || srv.losses !== want.losses || (srv.packs || 0) !== want.packs) bad.push('record or packs');
      if (((srv.online || {}).wins || 0) !== want.online.wins) bad.push('online record');
      if (!C.same(srv.coll, want.coll)) bad.push('cards');
      if (![...want.tips].every((t) => srv.tips && srv.tips[t]) || Object.keys(srv.tips || {}).length !== want.tips.size) bad.push('tips');
      if (![...want.paid].every((m) => (srv.onlinePaid || []).includes(m))) bad.push('paid duels');
      if (want.tutorial && srv.tutorial !== 'done') bad.push('tutorial');
      if (bad.length > (bad[0] === 'devices differ' ? 1 : 0)) lost++;
      const have = new Set(srv.decks.map((d) => d.id));
      const dk = [];
      if (!have.has('st-melee') || have.size !== srv.decks.length) dk.push('starter or duplicates');
      for (const id of want.made) if (!want.deleted.has(id) && !have.has(id)) dk.push(`${id} missing`);
      for (const id of have) if (id !== 'st-melee' && !want.made.has(id)) dk.push(`${id} from nowhere`);
      for (const [id, who] of want.edits) if (who.size === 1 && have.has(id) && !want.deleted.has(id)) {
        const i = [...who][0];
        if (srv.decks.find((d) => d.id === id).name !== want['last' + id + i]) dk.push(`${id} lost its edit`);
      }
      if (dk.length) { deckFault++; bad.push(...dk); }
      if (bad.length && !worst) worst = `world ${seed}: ${bad.join('; ')}`;
    }
    check(`${worlds} worlds: every device and the server end the same`, diverged === 0, `${steps} steps, ${merges} merges`);
    check(`${worlds} worlds: nothing earned is lost or counted twice`, lost === 0, worst);
    check(`${worlds} worlds: decks made stay, decks deleted go, a lone edit sticks`, deckFault === 0, worst);
    // a visit gives up after losing the race to save six times running (three devices saving at once, non-stop): it
    // leaves the device in step with what it last saw, and the next visit carries on. Rare, and counted, not failed.
    check(`${worlds} worlds: no visit failed or asked a question`, thrown === 0, thrown ? why.join(' | ') : `${crowded} gave up in a crowd and carried on later`);
  }

  console.log(failed ? `${failed} FAILED` : 'all passed');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
