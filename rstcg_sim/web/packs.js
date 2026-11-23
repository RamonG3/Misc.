/* Packs, the starting collection and what a duel pays, in one place: the Arena and the server's bank both use this.
 *
 *   const P = RSTCGPacks.make(CARDS, set1, decks)   // set1: { name: { rarity } } (arena_assets.js); decks: cards.json's
 *   P.POOL                  every card you can own (Set 1, no capes, nothing cut), by name
 *   P.BY_RARITY             { C, U, R, L }: the cards a pack can hold (never basic materials)
 *   P.roll(rnd)             one pack: [{ name, rar }]. rnd() -> [0, 1): Math.random on the page, crypto on the server
 *   P.starter()             { coll, decks }: the three starter decks, and the collection they make
 *   P.plausible(p)          could honest play have made this progress? -> { ok, why, gp, coll, packs }
 *                           (the numbers cleaned: only real cards, whole numbers, nothing below zero)
 *   PACK_COST, PAY, SLOTS, LEGEND_ODDS
 *   STORY_REWARDS          { 'ch1:A': card }: the card each Story Mode road gives, once (STORY_BRIEF.md). They're never
 *                          in packs; a collection may hold each once a profile's story.claimed says it was given
 *
 * A pack is 3 Commons, 2 Uncommons and a Rare that is a Legendary about one time in eight, with no card twice.
 * Basic materials (the common ones) are unlimited, so they are never in packs or in a collection.
 */
(function (root) {
  'use strict';
  const SLOTS = ['C', 'C', 'C', 'U', 'U', 'R'];
  const LEGEND_ODDS = 1 / 8;
  const PACK_COST = 200;
  const PAY = { win: 100, loss: 50 };
  const SLACK_GP = 500;                 // draws pay 50 and aren't counted anywhere; and a little room besides
  // chapter 3 is keyed by the card, not the road: Lord Daquarius's rack gives one per win, either road (story.js rewardBy)
  const STORY_REWARDS = { 'ch1:A': 'Claws of Guthix', 'ch1:B': 'Shield of Arrav', 'ch2:A': 'White Knight Armor', 'ch2:B': 'Cabbage Armor',
    'ch3:bow': 'Kinshra Shortbow', 'ch3:sword': 'Kinshra Sword', 'ch3:staff': 'Kinshra Battlestaff' };

  function make(CARDS, set1, decks) {
    const rarityOf = (n) => (set1[n] ? set1[n].rarity : 'C');
    const isBasic = (n) => !!CARDS[n] && CARDS[n].kind === 'material' && (!set1[n] || set1[n].rarity === 'C');
    const POOL = Object.keys(CARDS).filter((n) => set1[n] && CARDS[n].kind !== 'cape' && !/\(cut/.test(n)).sort();
    const inPool = new Set(POOL);
    const BY_RARITY = { C: [], U: [], R: [], L: [] };
    for (const n of POOL) if (!isBasic(n)) BY_RARITY[rarityOf(n)].push(n);

    function roll(rnd) {
      const picked = new Set();
      const draw = (r) => { const pool = BY_RARITY[r].filter((n) => !picked.has(n)); const n = pool[Math.floor(rnd() * pool.length)]; picked.add(n); return n; };
      return SLOTS.map((r) => {
        const rar = r === 'R' && rnd() < LEGEND_ODDS && BY_RARITY.L.length ? 'L' : r;
        return { name: draw(rar), rar };
      });
    }

    const STARTERS = (decks || []).filter((d) => d.group === 'Starter decks');
    function starter() {
      const coll = {};
      for (const d of STARTERS) for (const [n, k] of Object.entries(d.cards)) if (!isBasic(n)) coll[n] = (coll[n] || 0) + k;
      return { coll, decks: STARTERS.map((d) => ({ name: d.name, cards: { ...d.cards }, cape: d.capes[0] })) };
    }
    const START = starter().coll;

    const whole = (x) => (typeof x === 'number' && isFinite(x) && x > 0 ? Math.floor(x) : 0);
    function plausible(p) {
      p = p && typeof p === 'object' ? p : {};
      const coll = {};
      const given = {}, story = [];                 // Story Mode rewards this profile says it was given
      const claimed = p.story && p.story.claimed && typeof p.story.claimed === 'object' ? p.story.claimed : {};
      for (const [key, card] of Object.entries(claimed)) if (STORY_REWARDS[key] === card) { given[card] = (given[card] || 0) + 1; story.push(key); }
      for (const [n, k] of Object.entries(p.coll && typeof p.coll === 'object' ? p.coll : {})) {
        if (inPool.has(n) && !isBasic(n) && whole(k)) coll[n] = whole(k);
        else if (given[n] && whole(k)) coll[n] = Math.min(whole(k), given[n]);
      }
      const gp = whole(p.gp), packs = whole(p.packs), on = p.online && typeof p.online === 'object' ? p.online : {};
      const duels = whole(p.wins) + whole(p.losses) + whole(on.wins) + whole(on.losses);
      let extra = 0;
      for (const [n, k] of Object.entries(coll)) if (inPool.has(n)) extra += Math.max(0, k - (START[n] || 0));
      const why = [];
      if (extra > SLOTS.length * packs) why.push(`${extra} cards beyond the starter decks from ${packs} packs`);
      if (gp + PACK_COST * Math.max(0, packs - 1) > PAY.win * duels + SLACK_GP) why.push(`${gp} GP and ${packs} packs from ${duels} duels`);
      return { ok: !why.length, why: why.join('; '), gp, coll, packs, story: story.filter((k) => coll[STORY_REWARDS[k]]) };
    }
    return { POOL, BY_RARITY, roll, starter, plausible, rarityOf, isBasic, STORY_REWARDS };
  }

  const api = { make, SLOTS, LEGEND_ODDS, PACK_COST, PAY, STORY_REWARDS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGPacks = api;
})(typeof window !== 'undefined' ? window : globalThis);
