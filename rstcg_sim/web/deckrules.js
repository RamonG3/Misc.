/* The deck rules, in one place: the Arena's deck screens and the online referee both use this.
 *
 *   const D = RSTCGDeckRules.make(CARDS, set1)    // set1: { name: { rarity } }, as in arena_assets.js
 *   D.check(cards, owned)   // cards: { name: copies }; owned(name) -> copies you own, or leave it out
 *                           //   -> { total, sixty, copies, legend, owns, known, over, legends, short, unknown, ok }
 *
 * Exactly 60 cards, at most 4 of a card (common materials excepted), at most 1 Legendary, only real cards
 * (no capes, nothing cut from the set, no Story Mode boss's own cards), and, when `owned` is given, no more copies
 * than you own.
 */
(function (root) {
  'use strict';
  const DECK_SIZE = 60;
  const MAX_COPIES = 4;
  const MAX_LEGENDS = 1;

  function make(CARDS, set1) {
    const rarityOf = (n) => (set1[n] ? set1[n].rarity : 'C');
    const isBasic = (n) => !!CARDS[n] && CARDS[n].kind === 'material' && (!set1[n] || set1[n].rarity === 'C');   // any number
    const real = (n) => !!CARDS[n] && CARDS[n].kind !== 'cape' && !/\(cut/.test(n) && CARDS[n].story !== 'boss';
    function check(cards, owned) {
      const rows = Object.entries(cards || {});
      const total = rows.reduce((s, [, k]) => s + k, 0);
      const unknown = rows.filter(([n, k]) => !real(n) || !Number.isInteger(k) || k < 1).map(([n]) => n);
      const over = rows.filter(([n, k]) => k > MAX_COPIES && !isBasic(n)).map(([n]) => n);
      const legends = rows.filter(([n]) => rarityOf(n) === 'L').reduce((s, [, k]) => s + k, 0);
      const short = owned ? rows.filter(([n, k]) => k > owned(n)).map(([n]) => n) : [];
      const r = { total, sixty: total === DECK_SIZE, copies: !over.length, legend: legends <= MAX_LEGENDS, owns: !short.length,
        known: !unknown.length, over, legends, short, unknown };
      r.ok = r.sixty && r.copies && r.legend && r.owns && r.known;
      return r;
    }
    return { check, isBasic, rarityOf, real };
  }

  const api = { make, DECK_SIZE, MAX_COPIES, MAX_LEGENDS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGDeckRules = api;
})(typeof window !== 'undefined' ? window : globalThis);
