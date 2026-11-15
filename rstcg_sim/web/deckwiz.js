/* Deck Wizard: builds a legal 60-card deck around up to two focus cards, from the cards a player owns.
 *
 * It doesn't invent decks from nothing. It starts from the tested Starter and Tournament lists that suit
 * the focus cards, works the focus cards in at full strength, swaps anything the player doesn't own for
 * the closest card they do own, makes sure every card's materials are in the deck in workable numbers,
 * and trims or fills to exactly 60. Then it plays each candidate against the three starter decks with the
 * greedy bot and keeps the one that did best.
 *
 *   const W = RSTCGWiz;
 *   const builds = W.candidates(focus, owned, opts)   // focus: [names]; owned(name) -> copies (Infinity for basics)
 *   const score = await W.evaluate(build.cards, { games, onProgress, yieldEvery })
 */
(function (root) {
  'use strict';
  const R = (typeof module !== 'undefined' && module.exports) ? require('./engine.js') : root.RSTCG;
  const B = (typeof module !== 'undefined' && module.exports) ? require('./bot.js') : root.RSTCGBot;
  const { CARDS, DECKS } = R;
  const MAX = 4;
  const STYLES = ['melee', 'ranged', 'magic'];
  const FAM_STYLE = { bar: 'melee', log: 'ranged', rune: 'magic' };
  const OFFENSE = new Set(['weapon', 'thrown', 'bow', 'staff', 'spell', 'ability', 'rapid', 'longrange', 'arrows', 'pouch', 'hammer', 'destroy']);
  const DEFENSE = new Set(['shield', 'armor', 'reversal', 'prayer', 'buff']);
  const role = (n) => { const k = CARDS[n].kind; return k === 'material' ? 'material' : OFFENSE.has(k) ? 'offense' : DEFENSE.has(k) ? 'defense' : 'utility'; };
  const total = (cards) => Object.values(cards).reduce((s, k) => s + k, 0);

  // What style a card pulls a deck toward (null: fits anywhere)
  function styleOf(n) {
    const c = CARDS[n];
    if (STYLES.includes(c.style) && c.kind !== 'armor') return c.style;
    if (c.kind === 'material') return FAM_STYLE[c.mat] || null;
    if (c.kind === 'pouch') return 'magic';
    if (c.kind === 'arrows') return 'ranged';
    return null;
  }
  // A rough sense of how much a card does, to rank stand-ins against the card they replace
  function power(n) {
    const c = CARDS[n];
    if (c.hit) return c.hit * (c.hits || 1) * Math.max(1, c.durability || 3);
    if (c.damage) return c.damage * 1.5;
    if (c.soak) return c.soak * 1.5;
    if (c.heal) return c.heal * 1.5;
    if (c.bonus) return c.bonus * 4;
    if (c.arrow_bonus) return c.arrow_bonus * 6;
    if (c.qp) return c.qp * 4;
    return 8;
  }
  const isLegend = (n, rarity) => rarity(n) === 'L';
  const deckStyles = (cards) => {
    const t = { melee: 0, ranged: 0, magic: 0 };
    for (const [n, k] of Object.entries(cards)) { const s = styleOf(n); if (s && CARDS[n].kind !== 'material') t[s] += k; }
    return STYLES.filter((s) => t[s] >= 6).sort((a, b) => t[b] - t[a]);
  };

  // ---------- one build from one template ----------
  function build(T, focus, owned, rarity) {
    const cards = { ...T.cards };
    const isFocus = (n) => focus.includes(n);
    const cap = (n) => {
      let c = Math.min(owned(n), owned(n) === Infinity ? 99 : MAX);
      if (isLegend(n, rarity)) {
        const other = Object.entries(cards).filter(([x, k]) => x !== n && k && isLegend(x, rarity)).reduce((s, [, k]) => s + k, 0);
        c = Math.min(c, Math.max(0, 1 - other));
      }
      return c;
    };
    const styles = new Set([...deckStyles(T.cards), ...focus.map(styleOf).filter(Boolean)]);
    const fits = (n) => { const s = styleOf(n); return !s || styles.has(s); };
    const add = (n, k = 1) => { cards[n] = (cards[n] || 0) + k; };
    const drop = (n, k = 1) => { cards[n] -= k; if (cards[n] <= 0) delete cards[n]; };
    const pool = () => Object.keys(CARDS).filter((n) => owned(n) > 0 && CARDS[n].kind !== 'cape');
    // the best owned stand-in for a card: same kind, then same role; close in strength; fits the deck's styles
    function standIn(gone, exclude = new Set()) {
      const g = CARDS[gone], gp = power(gone);
      const options = pool().filter((n) => n !== gone && !exclude.has(n) && CARDS[n].kind !== 'material' && fits(n) && (cards[n] || 0) < cap(n));
      const rank = (n) => (CARDS[n].kind === g.kind ? 0 : role(n) === role(gone) ? 40 : 90) + Math.abs(power(n) - gp) / Math.max(4, gp) * 10 + ((cards[n] || 0) ? -3 : 0);
      options.sort((a, b) => rank(a) - rank(b));
      return options[0] || null;
    }

    // 1. the focus cards, at full strength
    for (const f of focus) {
      const target = CARDS[f].kind === 'material' && owned(f) === Infinity ? Math.max(cards[f] || 0, 8) : cap(f) + (cards[f] || 0) - (cards[f] || 0);
      const want = Math.min(target, CARDS[f].kind === 'material' ? 99 : MAX);
      let need = want - (cards[f] || 0);
      if (need <= 0) continue;
      cards[f] = want;
      // make room: same kind first, then same role, then the rest (never another focus card, never materials)
      const order = Object.keys(cards).filter((n) => !isFocus(n) && CARDS[n].kind !== 'material')
        .sort((a, b) => ((CARDS[a].kind === CARDS[f].kind ? 0 : role(a) === role(f) ? 1 : 2) - (CARDS[b].kind === CARDS[f].kind ? 0 : role(b) === role(f) ? 1 : 2))
          || ((owned(a) >= cards[a] ? 1 : 0) - (owned(b) >= cards[b] ? 1 : 0)) || (power(a) - power(b)));
      for (const n of order) { while (need > 0 && cards[n]) { drop(n); need -= 1; } if (!need) break; }
    }

    // 2. only cards the player owns, and at most one Legendary
    for (let pass = 0; pass < 3; pass++) {
      for (const n of Object.keys(cards)) {
        const over = (cards[n] || 0) - cap(n);
        if (over <= 0 || isFocus(n) && CARDS[n].kind !== 'material' && over <= 0) continue;
        drop(n, Math.min(over, cards[n]));
        for (let k = 0; k < over; k++) {
          const s = CARDS[n].kind === 'material' ? null : standIn(n);
          if (s) add(s);
        }
      }
    }

    // 3. materials: every card's requirements present in workable numbers; nothing nobody needs
    for (let pass = 0; pass < 3; pass++) {
      const need = {}, famNeed = {};
      for (const [n, k] of Object.entries(cards)) {
        if (!k || CARDS[n].kind === 'material') continue;
        for (const [m, q] of CARDS[n].req || []) need[m] = Math.max(need[m] || 0, q);
        for (const [m, q] of CARDS[n].expend || []) need[m] = Math.max(need[m] || 0, q);
        for (const [f, q] of CARDS[n].fam_req || []) famNeed[f] = Math.max(famNeed[f] || 0, q);
      }
      for (const f of focus) if (CARDS[f].kind === 'material') need[f] = Math.max(need[f] || 0, 2);
      if (need['Elemental Rune']) {                 // "any elemental rune": use one the deck already leans on
        const q = need['Elemental Rune']; delete need['Elemental Rune'];
        const el = R.ELEMENTAL.find((r) => need[r]) || R.ELEMENTAL.find((r) => cards[r]) || R.ELEMENTAL.find((r) => owned(r) > 0);
        if (el) need[el] = Math.max(need[el] || 0, q); else need['Elemental Rune'] = q;
      }
      // a requirement no owned material can meet: lose those cards for stand-ins that can be played
      const unmeetable = Object.keys(need).filter((m) => !CARDS[m] || owned(m) <= 0);
      if (unmeetable.length) {
        for (const n of Object.keys(cards)) {
          if (CARDS[n].kind === 'material' || isFocus(n)) continue;
          if ((CARDS[n].req || []).some(([m]) => unmeetable.includes(m))) {
            const k = cards[n]; drop(n, k);
            const bad = new Set(Object.keys(CARDS).filter((x) => (CARDS[x].req || []).some(([m]) => unmeetable.includes(m))));
            for (let j = 0; j < k; j++) { const s = standIn(n, bad); if (s) add(s); }
          }
        }
        continue;
      }
      const matSlots = Math.max(18, Object.entries(T.cards).filter(([n]) => CARDS[n].kind === 'material').reduce((s, [, k]) => s + k, 0));
      const mats = {};
      for (const [m, q] of Object.entries(need)) mats[m] = Math.min(cap(m), 2 + 2 * q);
      for (const [f, q] of Object.entries(famNeed)) {
        const have = Object.entries(mats).filter(([m]) => CARDS[m].mat === f).reduce((s, [, k]) => s + k, 0);
        if (have < 2 + 2 * q) {
          const base = Object.keys(CARDS).filter((m) => CARDS[m].kind === 'material' && CARDS[m].mat === f && owned(m) === Infinity)[0];
          if (base) mats[base] = (mats[base] || 0) + (2 + 2 * q - have);
        }
      }
      // spread the rest of the material slots over what's needed, most-used first
      const weight = (m) => Object.entries(cards).filter(([n, k]) => k && (CARDS[n].req || []).some(([x]) => x === m)).reduce((s, [, k]) => s + k, 0) + 1;
      let left = matSlots - total(mats);
      const order = Object.keys(mats).sort((a, b) => weight(b) - weight(a));
      for (let guard = 0; left > 0 && order.length && guard < 200; guard++) {
        const m = order[guard % order.length];
        if (mats[m] < cap(m)) { mats[m] += 1; left -= 1; }
        else if (order.every((x) => mats[x] >= cap(x))) break;
      }
      for (const n of Object.keys(cards)) if (CARDS[n].kind === 'material') delete cards[n];
      Object.assign(cards, mats);
      break;
    }

    // 4. exactly 60
    let t = total(cards);
    const trimOrder = () => Object.keys(cards).filter((n) => !isFocus(n))
      .sort((a, b) => (role(a) === 'material' ? 1 : 0) - (role(b) === 'material' ? 1 : 0) || cards[b] - cards[a] || power(a) - power(b));
    for (let guard = 0; t > 60 && guard < 200; guard++) { const n = trimOrder()[0]; if (!n) break; drop(n); t -= 1; }
    for (let guard = 0; t < 60 && guard < 200; guard++) {
      const inDeck = Object.keys(cards).filter((n) => CARDS[n].kind !== 'material' && cards[n] < cap(n) && fits(n)).sort((a, b) => power(b) - power(a));
      const n = inDeck[0] || standIn(Object.keys(cards).find((x) => CARDS[x].kind !== 'material') || focus[0]) ||
        Object.keys(cards).find((m) => CARDS[m].kind === 'material' && owned(m) === Infinity);
      if (!n) break;
      add(n); t += 1;
    }

    // the cape: the template's, unless the focus pulls another way
    const main = deckStyles(cards)[0];
    const byStyle = { melee: 'Attack Cape', ranged: 'Ranged Cape', magic: 'Magic Cape' };
    const cape = (T.capes || []).find((c) => !main || c === byStyle[main]) || byStyle[main] || (T.capes || [])[0];
    return { cards, cape, template: T.name };
  }

  // ---------- the templates worth trying, best first ----------
  function templates(focus) {
    const pool = DECKS.filter((d) => d.group === 'Starter decks' || d.group === 'Tournament decks');
    const fs = focus.map(styleOf).filter(Boolean);
    const score = (d) => {
      const st = deckStyles(d.cards);
      let s = 0;
      for (const f of focus) {
        if (d.cards[f]) s += 6;
        if (Object.keys(d.cards).some((n) => CARDS[n].kind === CARDS[f].kind)) s += 3;
        const fst = styleOf(f);
        if (fst && st.includes(fst)) s += st[0] === fst ? 5 : 3;
      }
      if (new Set(fs).size >= 2 && fs.every((x) => st.includes(x))) s += 6;      // two styles: a hybrid list
      if (fs.length && new Set(fs).size === 1 && st.length === 1) s += 2;          // one style: a pure list
      return s;
    };
    const list = pool.slice();
    // two focus styles: also try a blend of the two starter decks, half of each (all cards a new player owns)
    if (new Set(fs).size >= 2) {
      const starters = pool.filter((d) => d.group === 'Starter decks');
      const [s1, s2] = [...new Set(fs)];
      const a = starters.find((d) => deckStyles(d.cards)[0] === s1), b = starters.find((d) => deckStyles(d.cards)[0] === s2);
      if (a && b) {
        const cards = {};
        for (const n of new Set([...Object.keys(a.cards), ...Object.keys(b.cards)])) {
          const k = Math.round(((a.cards[n] || 0) + (b.cards[n] || 0)) / 2);
          if (k) cards[n] = k;
        }
        list.push({ name: `${a.name} + ${b.name}`, group: 'Blend', cards, capes: [a.capes[0], b.capes[0]], blend: true });
      }
    }
    return list.map((d) => [score(d) + (d.blend ? 8 : 0), d]).sort((a, b) => b[0] - a[0]).map(([, d]) => d);
  }

  function legal(cards, owned, rarity) {
    const t = total(cards);
    const over = Object.entries(cards).some(([n, k]) => (k > MAX && owned(n) !== Infinity) || k > owned(n));
    const legends = Object.entries(cards).filter(([n]) => rarity(n) === 'L').reduce((s, [, k]) => s + k, 0);
    return t === 60 && !over && legends <= 1;
  }

  function candidates(focus, owned, opts = {}) {
    const rarity = opts.rarity || (() => 'C');
    const n = opts.count || 3;
    const out = [], seen = new Set();
    for (const T of templates(focus)) {
      const b = build(T, focus, owned, rarity);
      const key = JSON.stringify(Object.entries(b.cards).sort());
      if (seen.has(key) || !legal(b.cards, owned, rarity) || focus.some((f) => !b.cards[f])) continue;
      seen.add(key); out.push(b);
      if (out.length >= n) break;
    }
    return out;
  }

  // ---------- how well it plays: greedy bot vs greedy bot, against each starter deck, both seats ----------
  async function evaluate(cards, opts = {}) {
    const games = opts.games || 12;                        // per starter deck
    const starters = DECKS.filter((d) => d.group === 'Starter decks');
    let wins = 0, played = 0;
    const totalGames = games * starters.length;
    let seed = opts.seed || 1;
    for (const S of starters) {
      for (let k = 0; k < games; k++) {
        const s = seed++;
        const me = k % 2;                                  // alternate who goes first
        const decks = [], agents = [], capes = [];
        decks[me] = cards; decks[1 - me] = S.cards;
        agents[me] = new B.HeuristicBot(s * 3 + 1); agents[1 - me] = new B.HeuristicBot(s * 3 + 2);
        capes[me] = opts.cape || null; capes[1 - me] = S.capes[0];
        const g = new R.Game(decks, agents, s, { capes }, ['W', 'S']);
        while (g.winner === null && g.turn < 300) await g.play_turn();
        played += 1;
        if (g.winner === me) wins += 1;
        if (opts.onProgress) opts.onProgress(played / totalGames);
        if (opts.yieldEvery && played % opts.yieldEvery === 0) await new Promise((r) => setTimeout(r, 0));
      }
    }
    return { wins, played, rate: wins / played };
  }

  const api = { candidates, evaluate, templates, build, styleOf, power, legal };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGWiz = api;
})(typeof window !== 'undefined' ? window : globalThis);
