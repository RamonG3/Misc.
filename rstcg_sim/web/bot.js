/* The greedy bot, ported from the simulator's bot.py: score every legal action, take the best.
 * It plays reasonably, not well, which is what the balance numbers were measured with. */
(function (root) {
  'use strict';
  const R = (typeof module !== 'undefined' && module.exports) ? require('./engine.js') : root.RSTCG;
  const { CARDS, CAPES, RNG, uniq, ELEMENTAL } = R;
  const FUTURE = 0.6;   // how much a hit that hasn't happened yet is worth vs. one now
  const DAMAGE_ACTS = new Set(['punch', 'swing', 'free_swing', 'spec']);
  const GEAR = { weapon: ['weapon', 'thrown', 'bow', 'staff'], armor: ['armor'], shield: ['shield'] };
  const REWARD = { heal: 0.6, draw: 2.5, fetch_rune: 4.0, fetch_bar: 4.0, fetch_prayer: 3.0, none: 0.0,
    lose_action: 5.0, damage: 0.9, chicken: 0.0, silverlight: 8.0, dragon_slayer: 6.0 };
  const CAPE_TICK = 4.0;

  function deck_needs(decklist) {
    const need = {};
    for (const name of Object.keys(decklist)) {
      for (const [m, k] of [...CARDS[name].req, ...CARDS[name].expend]) need[m] = Math.max(need[m] || 0, k);
    }
    return need;
  }

  function deck_style(decklist) {
    const tally = {};
    for (const [name, n] of Object.entries(decklist)) {
      const c = CARDS[name];
      if (['melee', 'ranged', 'magic'].includes(c.style) && c.kind !== 'armor') tally[c.style] = (tally[c.style] || 0) + n;
    }
    let best = null;
    for (const [s, n] of Object.entries(tally)) if (best === null || n > tally[best]) best = s;
    return best;
  }

  const armorMod = (c, style) => { for (const [s, m] of c.armor) if (s === style) return m; return 0; };

  class HeuristicBot {
    constructor(seed, noise = 0.25) {
      this.rng = new RNG(seed);
      this.noise = noise;
    }

    setup(g) {
      this.need = g.players.map((p) => deck_needs(p.decklist));
      this.style = g.players.map((p) => deck_style(p.decklist));
      this.styles = g.players.map((p) => new Set(Object.keys(p.decklist)
        .filter((n) => ['weapon', 'thrown', 'bow', 'staff', 'spell', 'ability'].includes(CARDS[n].kind))
        .map((n) => CARDS[n].style)));
      this.capes = g.players.map(() => g.cape_table.includes('Quest Point Cape'));
    }

    choose(g, i, legal) {
      let best = null;
      let bestV = -Infinity;
      for (const a of legal) {
        const v = this.score(g, i, a) + this.rng.random() * this.noise;
        if (v > bestV) { best = a; bestV = v; }
      }
      return best;
    }

    discard_choice(g, i) {
      const p = g.players[i];
      let best = null;
      let bestV = Infinity;
      for (const n of p.hand) {
        const v = this.keep_value(g, i, n) + this.rng.random() * 0.1;
        if (v < bestV) { best = n; bestV = v; }
      }
      return best;
    }

    // ---------- estimates ----------
    hit_value(g, i, amount, style, ignore_armor = false, ignore_prayer = false) {
      const [to_lp, soaked] = g.compute_damage(i, amount, style, ignore_armor, ignore_prayer, 'weapon');
      if (to_lp >= g.opp(i).lp) return 1000.0;
      return to_lp + 0.5 * soaked;
    }

    unlocks(g, i, mat, amount = 1) {    // hand cards that become playable with `amount` more of `mat`
      const p = g.players[i];
      const ok = () => new Set(uniq(p.hand).filter((n) => CARDS[n].req.length && p.meets(CARDS[n].req)));
      const before = ok();
      p.materials[mat] = (p.materials[mat] || 0) + amount;
      const after = ok();
      p.materials[mat] -= amount;
      if (p.materials[mat] <= 0) delete p.materials[mat];
      let n = 0;
      for (const x of after) if (!before.has(x)) n++;
      return n;
    }

    incoming(g, i) {                    // rough damage the opponent threatens next turn, and its style
      const o = g.opp(i);
      const w = o.weapon_card();
      if (w && w.kind === 'weapon') return [w.hit + 2, 'melee'];
      if (w && w.kind === 'thrown') return [w.hit, 'ranged'];
      if (w && w.kind === 'bow' && o.ammo) return [w.hit + o.ammo.bonus, 'ranged'];
      const style = this.style[1 - i];
      if (style === 'magic' && o.family_count('rune') >= 1) return [o.count('Chaos Rune') ? 10 : 6, 'magic'];
      return [0, style];
    }

    // ---------- scoring ----------
    score(g, i, a) {
      const o = g.opp(i);
      const doomed = o.cape !== null && o.cape >= g.rules.cape_turns - 1;   // they win at their next turn
      if (a[0] === 'claim') {
        if (doomed) return -10.0;
        return o.lp > 6 ? 40.0 : 15.0;
      }
      let v = this.base_score(g, i, a);
      if (g.cape_table.length && g.players[i].cape === null && !doomed) v += this.cape_bonus(g, i, a);
      if (doomed) {
        const dmg = DAMAGE_ACTS.has(a[0]) || (a[0] === 'play' && CARDS[a[1]].kind === 'spell' && CARDS[a[1]].damage);
        v = dmg ? v * 2.0 + 5.0 : v - 4.0;
      }
      return v;
    }

    cape_events(g, i, a) {
      const p = g.players[i];
      const w = p.weapon_card();
      if (a[0] === 'punch') return [['melee_hit', 1]];
      if (a[0] === 'swing' || a[0] === 'free_swing') return [[a[1] + '_hit', w ? w.hits : 1]];
      if (a[0] === 'spec') return w ? [[(w.styles.length ? w.styles[0] : w.style) + '_hit', 1]] : [];
      if (a[0] !== 'play') return [];
      const c = CARDS[a[1]];
      if (c.kind === 'spell' && c.damage) return [['magic_hit', 1]];
      if (c.kind === 'food') return [['food', 1]];
      if (c.kind === 'buff' && c.duration) return [['elite_prayer', 1]];
      if (c.kind === 'material') return (c.combo.length ? c.combo : [a[1]]).map((m) => [CARDS[m] ? CARDS[m].mat : c.mat, 1]);
      if (c.kind === 'shield') return [['soak', Math.floor(c.soak / 2)]];
      return [];
    }

    cape_bonus(g, i, a) {
      const p = g.players[i];
      const ev = this.cape_events(g, i, a);
      if (!ev.length) return 0.0;
      let v = 0.0;
      for (const cape of g.cape_table) {
        if (cape in g.cape_claimed) continue;
        const what = CAPES[cape].what;
        const ticks = ev.filter(([e]) => e === what).reduce((s, [, n]) => s + n, 0);
        if (!ticks) continue;
        const [have, need] = g.progress(p, cape);
        const left = need - have;
        if (left <= 0) continue;
        v += CAPE_TICK * Math.min(ticks, left) / left;
      }
      return v;
    }

    base_score(g, i, a) {
      const p = g.players[i];
      const o = g.opp(i);
      switch (a[0]) {
        case 'draw': {
          if (!p.deck.length) return -50.0;
          let v = g.own_turn() <= 8 ? 2.6 : 1.6;     // early on, digging for a material beats a punch
          v -= 0.4 + (p.lp <= 8 ? 2.0 : 0.0);       // drawing spends a point of life
          if (p.hand.length >= g.rules.hand_max) v -= 0.6;
          return v;
        }
        case 'punch': return this.hit_value(g, i, g.rules.punch_damage, 'melee', true, true) * 0.9;
        case 'swing': return this.swing_value(g, i, a[1]);
        case 'free_swing': { const v = this.swing_value(g, i, a[1]); return v > 0.5 ? v + 3.0 : -1.0; }
        case 'spec': return this.spec_value(g, i);
        case 'varrock':                            // discard a card to heal 3
          return 3.0 * this.urgency(p) - 0.6 * this.keep_value(g, i, a[1]) - 0.3;
        case 'exchange': {                         // free: swap a card from hand for the top of your deck
          let v = 2.4 - this.keep_value(g, i, a[1]);
          v -= 0.4 + (p.lp <= 8 ? 2.0 : 0.0);
          return v > 0 ? v + 2.0 : v;
        }
        case 'quest': {
          const v = this.quest_step_value(g, i, a[1]);
          const free = p.free_steps < g.rules.free_quest_steps;
          return free && v > 0 ? v + 6.0 : v;
        }
        default: break;
      }
      const [, name, target] = a;
      const c = CARDS[name];
      const k = c.kind;
      const need = this.need[i];
      const [threat, threat_style] = this.incoming(g, i);

      if (k === 'material') {
        const parts = c.combo.length ? c.combo : [name];
        let v = parts.some((m) => p.count(m) < (need[m] || 0)) ? 3.0 : 0.4;
        if (c.combo.length && parts.filter((m) => p.count(m) < (need[m] || 0)).length === 2) v = 4.5;
        return v + 4.0 * this.unlocks(g, i, name);
      }
      if (k === 'spell') {
        let v = c.damage ? this.hit_value(g, i, c.damage, 'magic') : 0.0;
        v += 2.0 * c.draws;
        if (c.heals) v += Math.min(c.heals, g.rules.max_lp - p.lp) * this.urgency(p);
        if (c.fetch) v += this.fetch_value(g, i, c.fetch);
        if (c.wears) {
          const w = o.weapon_card();
          if (w && o.weapon.dur) v += Math.min(c.wears, o.weapon.dur) * (w.hit || w.bash_hit) * FUTURE;
          else if (o.ammo) v += Math.min(c.wears, o.ammo.shots) * 5 * FUTURE;
        }
        return v;
      }
      if (k === 'weapon' || k === 'thrown') {
        let spec = c.spec ? 2.5 : 0.0;
        spec += c.hit * c.durability * (c.hits - 1) * FUTURE;
        if (c.two_handed && p.shield) spec -= p.shield.soak * 0.3;
        return c.hit * c.durability * FUTURE + 1.0 + spec - this.current_weapon_value(p);
      }
      if (k === 'staff') {
        let v = c.hit * c.durability * FUTURE + 1.0;
        for (let [m, n] of c.counts_as) {
          if (m === 'Elemental Rune') {   // Staff of Light: counts as your main element
            m = ELEMENTAL.reduce((best, x) => (p.count(x, true) > p.count(best, true) ? x : best), ELEMENTAL[0]);
          }
          v += 4.0 * this.unlocks(g, i, m, n);
        }
        return v - this.current_weapon_value(p);
      }
      if (k === 'bow') {
        const cur = p.weapon_card();
        if (cur && cur.kind === 'bow') return (c.hit - cur.hit) * 3 * FUTURE - 1.0;
        let v = c.hit * 3 * FUTURE + 4.0 + c.soak * 0.4 + c.bash_hit * c.durability * FUTURE * 0.5;
        for (const [m, n] of c.counts_as) v += 4.0 * this.unlocks(g, i, m, n);
        return v - this.current_weapon_value(p);
      }
      if (k === 'arrows') {
        const bow = p.weapon_card();
        const fresh = 3 * (bow.hit + c.arrow_bonus);
        const cur = p.ammo ? p.ammo.shots * (bow.hit + p.ammo.bonus) : 0;
        return (fresh - cur) * 0.5 + (p.ammo ? 0.0 : 2.0);
      }
      if (k === 'ability') {
        const w = p.weapon_card();
        const v = this.hit_value(g, i, w.hit + c.damage, 'melee', c.ignores_armor);
        return v - 0.3 * w.hit - 1.0;              // a swing that costs a card
      }
      if (k === 'rapid' || k === 'longrange') {
        const bow = p.weapon_card();
        const thrown = bow.kind === 'thrown';
        const left = thrown ? p.weapon.dur : p.ammo.shots;
        const shot = bow.hit + (thrown ? 0 : p.ammo.bonus) + (k === 'longrange' ? -1 : 0);
        const n = k === 'rapid' && left >= 2 ? 2 : 1;
        let v = this.hit_value(g, i, shot * n, 'ranged') - 0.2 * shot * n - 1.0;
        if (k === 'longrange' && threat_style === 'melee') v += 2.0;
        return v;
      }
      if (k === 'teleport') {
        const f = this.fetch_value(g, i, c.fetch);
        return f > 0 ? f : -2.0;
      }
      if (k === 'hammer') {
        if (target === 'weapon') {
          const w = p.weapon_card();
          return c.bonus * w.hit * w.hits * FUTURE * 0.7;
        }
        return c.bonus * 0.45 + (threat > p.shield.soak ? 1.0 : 0.0);
      }
      if (k === 'destroy') {
        let v = 0.0;
        if (target !== null && target !== undefined) {
          const theirs = this.need[1 - i][target] || 0;
          v = o.count(target, true) <= theirs ? 5.0 : 1.0;
        }
        if (c.damage) v += this.hit_value(g, i, c.damage, ['melee', 'ranged', 'magic'].includes(c.style) ? c.style : 'none');
        return v;
      }
      if (k === 'pouch') {
        const v = 2.0 + 4.0 * this.unlocks(g, i, target);
        return v + (p.count(target) < (need[target] || 0) ? 2.0 : 0.0);
      }
      if (k === 'armor') {
        const fit = -armorMod(c, threat_style) * 0.75 + (c.bonus || 0) * 1.6 * 0.7 * 3;   // bonus: Void, Round 22 platebodies
        if (!p.armor) return 3.0 + fit;
        const cur = armorMod(CARDS[p.armor], threat_style);
        return (cur - armorMod(c, threat_style)) * 0.75 - 0.5;
      }
      if (k === 'shield') {
        const v = !p.shield ? 2.0 + Math.min(c.soak, Math.max(threat, 4)) * 0.4 : (c.soak - p.shield.soak) * 0.3 - 0.5;
        return v + (c.free && v > 0 ? 2.0 : 0.0);
      }
      if (k === 'food') {
        const missing = g.rules.max_lp - p.lp;
        const eff = Math.min(c.heal, missing, p.discard.length);
        const urgency = p.lp <= 15 ? 1.2 : p.lp <= 30 ? 0.8 : 0.3;
        return eff * urgency - (c.heal - eff) * 0.3;
      }
      if (k === 'npc') {
        const t = CARDS[target];
        if (t.kind === 'material') return 3.0 + 4.0 * this.unlocks(g, i, target);
        if (['weapon', 'bow', 'staff'].includes(t.kind) && !p.weapon) return 4.0;
        return 2.5;
      }
      if (k === 'buff') {
        const v = this.buff_value(g, i, c);
        return v > 0 ? v + 3.0 : v;
      }
      if (k === 'quest') {
        if (!this.quest_feasible(g, i, c)) return -5.0;
        let v = this.quest_worth(g, i, c) / (c.steps.length + 1) - 0.5;
        if (p.quest) {                             // abandoning the current one
          const cur = CARDS[p.quest.name];
          if (this.quest_feasible(g, i, cur)) v -= this.quest_worth(g, i, cur) * (p.quest.step + 1) / (cur.steps.length + 1) + 2.0;
        }
        return v;
      }
      if (k === 'location') {
        let v = g.location_owner === 1 - i ? 1.0 : 0.0;
        if (name === "Wizards' Tower") {
          v += this.style[i] === 'magic' ? 3.0 : -1.0;
          v -= this.style[1 - i] === 'magic' ? 1.5 : 0.0;
        } else if (name === 'Wilderness') v += p.lp >= o.lp ? 2.0 : 0.5;
        else v += this.location_value(g, i, name);
        return v;
      }
      return 0.0;
    }

    urgency(p) {
      if (!p.discard.length) return 0.0;
      return p.lp <= 15 ? 1.2 : p.lp <= 30 ? 0.8 : 0.3;
    }

    fetch_value(g, i, loc) {
      const p = g.players[i];
      if (!p.deck.includes(loc) || g.location === loc) return 0.0;
      return 1.0 + (g.location_owner === 1 - i ? 1.0 : 0.0) + this.location_value(g, i, loc);
    }

    location_value(g, i, name) {         // what a new location is worth to player i
      const p = g.players[i];
      const o = g.opp(i);
      if (name === 'Mage Arena') {
        const f = { magic: 3.0, ranged: -2.5 };
        return (f[this.style[i]] || 0.0) - (f[this.style[1 - i]] || 0.0);
      }
      if (name === 'Duel Arena') {
        const kinds = (q) => Object.entries(q.decklist).reduce((t, [c, n]) => t + (['food', 'buff', 'prayer'].includes(CARDS[c].kind) ? n : 0), 0);
        return 0.12 * (kinds(o) - kinds(p)) + (o.buff ? 3.0 : 0.0) - (p.buff ? 3.0 : 0.0);
      }
      if (name === 'Lumbridge') return 2.0 + 0.08 * (o.lp - p.lp);
      if (name === 'Varrock') return 1.0 + (p.lp < o.lp ? 1.0 : 0.0);
      if (name === 'Grand Exchange') return 2.5;
      return 0.0;
    }

    attacks_per_turn(g, i, style) {
      if (style === 'any') return 1.6;   // Smite (Round 22): every attack
      if (!this.styles[i].has(style)) return 0.0;
      const n = ['melee', 'ranged', 'magic'].filter((s) => this.styles[i].has(s)).length;
      return 1.6 / n;
    }

    buff_value(g, i, c) {
      const p = g.players[i];
      const o = g.opp(i);
      const turns = c.duration || 5;
      let v;
      if (c.effect === 'smite') v = 1.0 + (o.buff ? 4.0 : 1.0) + c.bonus * this.attacks_per_turn(g, i, c.prayer) * turns * 0.7;
      else if (c.effect === 'redemption') v = p.lp <= 25 ? 6.0 : p.lp <= 40 ? 3.0 : 0.5;
      else v = c.bonus * this.attacks_per_turn(g, i, c.prayer) * turns * 0.7;
      if (p.buff) v -= this.buff_value_current(g, i) + 1.0;
      return v - 1.0;
    }

    buff_value_current(g, i) {
      const p = g.players[i];
      const c = CARDS[p.buff];
      const turns = p.buff_left || 5;
      if (c.effect) return 2.0 + (c.bonus ? c.bonus * this.attacks_per_turn(g, i, c.prayer) * turns * 0.7 : 0.0);
      return c.bonus * this.attacks_per_turn(g, i, c.prayer) * turns * 0.7;
    }

    spec_value(g, i) {
      const p = g.players[i];
      const o = g.opp(i);
      const w = p.weapon_card();
      const [threat, threat_style] = this.incoming(g, i);
      const hv = (amount, style, ignore_armor = false) => this.hit_value(g, i, amount, style, ignore_armor);
      switch (w.spec) {
        case 'sanctuary': return threat > 0 ? 2.0 * w.spec_val * 0.6 : -1.0;
        case 'light': return threat_style === 'melee' && threat > 0 ? threat * 0.4 + 0.5 : -1.0;
        case 'war_god': return hv(w.hit, 'melee') + (o.buff ? 3.0 : 0) - 0.2 * w.hit;
        case 'sunder': {
          const v = hv(w.hit + w.spec_val, 'melee') - 0.2 * w.hit;
          return v + (o.shield && !o.shield.virtual ? o.shield.soak * 0.4 : 0);
        }
        case 'snapshot': {
          const shot = w.hit + p.ammo.bonus;
          const n = p.ammo.shots >= 2 ? 2 : 1;
          return hv(shot * n, 'ranged') - 0.15 * shot * n;
        }
        case 'soulshot': return hv(w.hit + p.ammo.bonus + w.spec_val, 'ranged', true) + 1.5;
        case 'iban': return hv(w.spec_val, 'magic') - 0.2 * w.hit;
        case 'lightning': return hv(w.hit, 'melee') + hv(w.spec_val, 'magic') - 0.2 * w.hit;
        default: return 0.0;
      }
    }

    quest_worth(g, i, c) {
      const p = g.players[i];
      const o = g.opp(i);
      const need = g.cape_table.length ? g.progress(p, 'Quest Point Cape')[1] : 5;
      const open = this.capes[i] && p.cape === null && !('Quest Point Cape' in g.cape_claimed);
      let qp_val = open ? 3.5 : 0.4;
      if (open && p.qp + c.qp >= need) qp_val = 6.0;
      let r = (REWARD[c.effect] || 0.0) * Math.max(1, c.effect !== 'lose_action' ? c.reward_val : 1);
      if (c.effect === 'heal') r = 0.6 * Math.min(c.reward_val, g.rules.max_lp - p.lp + 6);
      if (c.effect === 'chicken') r = this.current_weapon_value(o) + 2.0;
      if (c.effect === 'silverlight' && p.weapon) r = 3.0;
      return c.qp * qp_val + r;
    }

    quest_feasible(g, i, c) {       // can this deck finish the quest at all?
      const p = g.players[i];
      const kinds = new Set(Object.keys(p.decklist).map((n) => CARDS[n].kind));
      const fams = new Set(Object.keys(p.decklist).filter((n) => CARDS[n].kind === 'material').map((n) => CARDS[n].mat));
      for (const [kind, arg] of c.steps) {
        if (GEAR[kind] && !GEAR[kind].some((x) => kinds.has(x)) && !p[kind]) return false;
        if (kind === 'have' && arg[0] !== 'any' && !fams.has(arg[0])) return false;
        if (kind === 'discard' && arg && !kinds.has(arg)) return false;
      }
      return true;
    }

    quest_step_value(g, i, target) {
      const p = g.players[i];
      const c = CARDS[p.quest.name];
      const left = c.steps.length - p.quest.step;
      let v = this.quest_worth(g, i, c) / left;
      const [kind] = c.steps[p.quest.step];
      if (kind === 'discard') v -= 0.6 * this.keep_value(g, i, target);
      else if (kind === 'spend') v -= p.count(target, true) <= (this.need[i][target] || 0) ? 3.0 : 0.6;
      return v;
    }

    // Swing now, or hold it? A hit a shield would mostly soak is worth less now than later.
    swing_value(g, i, style) {
      const p = g.players[i];
      const o = g.opp(i);
      const w = p.weapon_card();
      let amount;
      let cost;
      if (w.kind === 'bow' && style === 'ranged') { amount = w.hit + p.ammo.bonus; cost = 0.15; }
      else if (w.kind === 'bow') { amount = w.bash_hit; cost = 0.2; }
      else { amount = w.hit; cost = 0.2; }
      let [to_lp, soaked] = g.compute_damage(i, amount, style, false, false, 'weapon');
      to_lp *= w.hits;
      soaked *= w.hits;
      if (to_lp >= o.lp) return 1000.0;
      if (w.legendary && o.shield && !o.shield.virtual) soaked += o.shield.soak;
      return to_lp + 0.45 * soaked - cost * amount;
    }

    react(g, j, amount, style, src, opts) {
      const me = g.players[j];
      const att = 1 - j;
      const [incoming, soak] = g.compute_damage(att, amount, style);
      let best = null;
      let bestV = 0.0;
      for (const name of opts) {
        const c = CARDS[name];
        let v = incoming + 0.3 * soak;
        if (c.kind === 'reversal') {
          const [back, bsoak] = g.compute_damage(j, amount, c.converts_to);
          v += 0.8 * back + 0.3 * bsoak;
          if (back >= g.players[att].lp) v += 100;
        }
        if (v > bestV) { best = name; bestV = v; }
      }
      const lethal = incoming >= me.lp;
      return best && (lethal || bestV >= 7) ? best : null;
    }

    current_weapon_value(p) {
      const w = p.weapon_card();
      if (!w) return 0.0;
      if (w.kind === 'weapon' || w.kind === 'thrown' || (w.kind === 'staff' && p.weapon.dur)) return w.hit * w.hits * p.weapon.dur * FUTURE;
      if (w.kind === 'bow') return w.hit * 2 * FUTURE + 3.0;
      return 6.0;
    }

    keep_value(g, i, name) {
      const p = g.players[i];
      const c = CARDS[name];
      switch (c.kind) {
        case 'material': return p.count(name) >= (this.need[i][name] || 0) ? 0.5 : 2.5;
        case 'food': return p.lp >= 45 ? 1.0 : 3.0;
        case 'prayer': return c.prayer !== this.style[1 - i] ? 0.5 : 3.5;
        case 'reversal': return c.reacts_to === this.style[1 - i] ? 3.5 : 1.5;
        case 'spell': case 'weapon': case 'thrown': case 'bow': case 'arrows': return 5.0;
        case 'quest': return p.completed.includes(c.name) || p.quest ? 0.2 : 3.0;
        case 'buff': return 3.5;
        default: return 3.0;
      }
    }
  }

  const api = { HeuristicBot, deck_style, deck_needs };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGBot = api;
})(typeof window !== 'undefined' ? window : globalThis);
