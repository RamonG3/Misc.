/* RuneScape TCG rules engine, ported from the simulator's engine.py (Round 16 rules).
 *
 * The engine knows the rules and nothing about strategy. Agents (the bot, or the page's
 * human player) answer three questions, and may answer with a Promise:
 *   choose(game, i, legal)                       -> one of the legal actions
 *   react(game, j, amount, style, src, options)  -> a reaction card name, or null
 *   discard_choice(game, i)                      -> a card name from hand
 * Actions are arrays mirroring the simulator's tuples:
 *   ["draw"] ["punch"] ["swing", style] ["free_swing", style] ["spec"]
 *   ["quest", target] ["claim", cape] ["play", card, target] ["pass"] (human only: end the turn)
 * Only the adopted rules are ported; experiments and retired rules are left out.
 */
(function (root) {
  'use strict';

  const CARD_DEFAULTS = {
    kind: '', style: 'neutral', req: [], mat: '', hit: 0, durability: 0, wclass: '', allowed: [],
    arrow_tier: 0, arrow_bonus: 0, damage: 0, ignores_armor: false, armor: [], soak: 0, free: false,
    counts_as: [], prayer: '', heal: 0, stacks_with_food: false, recovers: [], destroys: '',
    needs_melee_weapon: false, item: false, legendary: false, combo: [], styles: [], bash_hit: 0,
    two_handed: false, free_swing_after_spell: false, wears: 0, draws: 0, reacts_to: '', converts_to: '',
    bonus: 0, effect: '', spec: '', spec_cost: 0, spec_val: 0, qp: 0, steps: [], reward_val: 0,
    duration: 0, families: 0, hits: 1, equip_swing: false, expend: [], fam_req: [],
    heals: 0, fetch: '', unshatter: false,
  };

  const CARDS = {};
  const CAPES = {};
  const DECKS = [];

  function setData(data) {
    for (const k of Object.keys(CARDS)) delete CARDS[k];
    for (const [name, c] of Object.entries(data.cards)) CARDS[name] = Object.freeze({ ...CARD_DEFAULTS, ...c, name });
    for (const k of Object.keys(CAPES)) delete CAPES[k];
    Object.assign(CAPES, data.capes);
    DECKS.length = 0;
    DECKS.push(...data.decks);
  }

  const RULES = {
    opening_hand: 6, hand_max: 7, actions: 2, punch_damage: 2, max_lp: 60,
    arrow_shots: 4, bow_shots: 4, reversal_fraction: 1.0, max_energy: 4,
    capes: null, cape_need: null, cape_turns: 3, free_quest_steps: 1, free_plays_max: 3,
  };
  const FREE_SPECS = new Set(['sanctuary', 'light']);
  const ELEMENTAL = ['Air Rune', 'Water Rune', 'Earth Rune', 'Fire Rune'];
  const NO_BUFF_SRC = new Set(['punch', 'reversal', 'quest']);
  const WEAPON_KINDS = ['weapon', 'thrown', 'bow', 'staff'];

  // ---------- small helpers ----------
  class RNG {                       // mulberry32: small, fast, seedable
    constructor(seed) { this.s = (seed >>> 0) || 0x9e3779b9; }
    random() {
      let t = (this.s = (this.s + 0x6d2b79f5) >>> 0);
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    }
    randrange(n) { return Math.floor(this.random() * n); }
    shuffle(a) {                    // the same walk as Python's random.shuffle
      for (let i = a.length - 1; i > 0; i--) {
        const j = this.randrange(i + 1);
        [a[i], a[j]] = [a[j], a[i]];
      }
    }
  }
  const uniq = (arr) => [...new Set(arr)];
  const Cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
  // Log wording: a player named "You" reads in the second person.
  const you = (p) => p.name === 'You';
  const subj = (p) => (you(p) ? 'You' : p.name);
  const vb = (p, base, third) => (you(p) ? base : third);
  const Poss = (p) => (you(p) ? 'Your' : `${p.name}'s`);
  const poss = (p) => (you(p) ? 'your' : `${p.name}'s`);
  const get = (counter, k) => counter[k] || 0;
  function inc(counter, k, n = 1) {
    counter[k] = (counter[k] || 0) + n;
    if (counter[k] <= 0) delete counter[k];
  }
  function removeOne(arr, x) {
    const k = arr.indexOf(x);
    if (k < 0) throw new Error(`${x} not found`);
    arr.splice(k, 1);
  }

  // ---------- players ----------
  class Player {
    constructor(name, decklist, rng) {
      this.rng = rng;
      this.name = name;
      this.decklist = { ...decklist };
      this.deck = [];
      for (const [c, n] of Object.entries(decklist)) for (let k = 0; k < n; k++) this.deck.push(c);
      rng.shuffle(this.deck);          // the top of the deck is the end of the array
      this.hand = [];
      this.discard = [];
      this.removed = [];
      this.materials = {};
      this.weapon = null;              // {name, dur, energy, played}
      this.shield = null;              // {name, soak, virtual}
      this.armor = null;
      this.ammo = null;                // {card, bonus, shots, tier}; card null = a bow's built-in shots
      this.prayer = null;
      this.longrange = false;
      this.ate_food = false;
      this.ate_karambwan = false;
      this.reacted_round = -1;
      this.free_swing_ready = false;
      this.free_swing_used = false;
      this.buff = null;
      this.buff_new = false;
      this.buff_left = 0;
      this.quest = null;               // {name, step}
      this.completed = [];
      this.qp = 0;
      this.sanctuary = 0;
      this.light = false;
      this.lost_actions = 0;
      this.cape = null;                // counters on the cape you wear
      this.cape_name = null;
      this.cape_prog = {};             // melee_hit, ranged_hit, magic_hit, soak, elite_prayer, food, reaction
      this.fresh = {};
      this.energy = 0;
      this.free_steps = 0;
      this.lumbridge_used = false;     // Lumbridge has saved this player's last card
      this.exchanged = false;          // Grand Exchange used this turn
    }

    // Your deck is your life: damage mills from the top, healing puts random cards
    // from your discard pile on the bottom (as many as there are).
    get lp() { return this.deck.length; }
    set_lp(value) {
      const delta = value - this.deck.length;
      for (let k = 0; k < -delta; k++) {
        if (!this.deck.length) break;
        this.discard.push(this.deck.pop());
      }
      const heal = Math.min(delta, this.discard.length);
      for (let k = 0; k < heal; k++) {
        const x = this.rng.randrange(this.discard.length);
        this.deck.unshift(this.discard.splice(x, 1)[0]);
      }
    }

    count(mat, real = false) {
      if (mat === 'Elemental Rune') {                // Round 19: any Air, Water, Earth or Fire rune
        let n = 0;
        for (const [m, k] of Object.entries(this.materials)) {
          if (k && (ELEMENTAL.includes(m) || CARDS[m].combo.some((x) => ELEMENTAL.includes(x)))) n += k;
        }
        if (!real && this.weapon) {
          for (const [m, k] of CARDS[this.weapon.name].counts_as) if (ELEMENTAL.includes(m)) n += k;
        }
        return n;
      }
      let n = get(this.materials, mat);
      for (const [name, k] of Object.entries(this.materials)) {   // specialty runes count as both
        if (k && CARDS[name].combo.includes(mat)) n += k;
      }
      if (!real && this.weapon) {
        for (const [m, k] of CARDS[this.weapon.name].counts_as) {
          if (m === mat || (m === 'Elemental Rune' && this.main_elements().includes(mat))) n += k;
        }
      }
      return n;
    }
    // Staff of Light (Round 19): the Air/Water/Earth/Fire runes you have the most of in play. Combination
    // runes count for both their elements; on a tie every tied element qualifies; none in play counts as nothing.
    main_elements() {
      const c = ELEMENTAL.map((e) => this.count(e, true));
      const top = Math.max(...c);
      return ELEMENTAL.filter((e, j) => c[j] === top && top > 0);
    }
    meets(req, real = false) { return req.every(([m, k]) => this.count(m, real) >= k); }
    family_count(fam) {
      let n = 0;
      for (const [m, k] of Object.entries(this.materials)) if (fam === 'any' || CARDS[m].mat === fam) n += k;
      return n;
    }
    weapon_card() { return this.weapon ? CARDS[this.weapon.name] : null; }
  }

  // ---------- the game ----------
  class Game {
    constructor(decks, agents, seed, rules, names) {
      this.rng = new RNG(seed === undefined ? Math.floor(Math.random() * 2 ** 32) : seed);
      this.rules = { ...RULES, ...(rules || {}) };
      names = names || ['P1', 'P2'];
      this.players = [0, 1].map((i) => new Player(names[i], decks[i], this.rng));
      this.agents = agents;
      this.location = null;
      this.location_owner = null;
      this.turn = 0;                   // total turns taken; player 0 goes first
      this.active = 0;
      this.winner = null;
      this.end_reason = null;
      this.stats = [{}, {}];
      this.log = [];                   // {text, who} lines for the page
      this.events = [];                // {type, ...} for the page's effects
      this.actions_left = 0;
      this.free_plays_used = 0;
      this.cape_table = uniq((this.rules.capes || []).filter(Boolean));
      this.cape_claimed = {};
      for (const p of this.players) for (let k = 0; k < this.rules.opening_hand; k++) p.hand.push(p.deck.pop());
      for (const a of agents) if (a && a.setup) a.setup(this);
    }

    own_turn() { return Math.floor(this.turn / 2) + 1; }
    opp(i) { return this.players[1 - i]; }
    stat(i, k, n = 1) { this.stats[i][k] = (this.stats[i][k] || 0) + n; }
    note(text, who = null) { this.log.push({ text, who, at: this.turn }); }
    emit(ev) { this.events.push(ev); }

    // ---------- damage ----------
    attack_bonus(i, style, src) {
      const p = this.players[i];
      let extra = 0;
      if (p.armor && CARDS[p.armor].bonus && !NO_BUFF_SRC.has(src)) extra = CARDS[p.armor].bonus;  // Void
      if (p.buff && !NO_BUFF_SRC.has(src)) {
        const c = CARDS[p.buff];
        if (c.prayer === style || c.prayer === 'any') return c.bonus + extra;   // 'any': Smite (Round 22)
      }
      return extra;
    }

    // [damage to the deck, damage soaked by a shield], changing nothing.
    // Pass src to include the attacker's prayer bonus (deal adds it itself).
    compute_damage(i, amount, style, ignore_armor = false, ignore_prayer = false, src = null, ignore_shield = false) {
      const o = this.opp(i);
      if (src !== null) amount += this.attack_bonus(i, style, src);
      if (this.location === "Wizards' Tower" && style === 'magic') amount += 2;
      if (this.location === 'Wilderness') amount += 2;
      if (this.location === 'Mage Arena') amount += style === 'magic' ? 2 : style === 'ranged' ? -2 : 0;
      if (!ignore_armor && o.armor) for (const [s, m] of CARDS[o.armor].armor) if (s === style) amount += m;
      if (o.longrange && style === 'melee') amount -= 2;
      if (o.light && style === 'melee') amount = Math.floor(amount / 2);
      if (o.sanctuary && style !== 'none') amount -= o.sanctuary;
      amount = Math.max(0, amount);
      if (!ignore_prayer && o.prayer === style) amount = Math.floor(amount / 2);
      let soaked = 0;
      if (o.shield && amount > 0 && !ignore_shield) {
        soaked = Math.min(amount, o.shield.soak);
        amount -= soaked;
      }
      return [amount, soaked];
    }

    async offer_reaction(i, amount, style, src) {
      const j = 1 - i;
      const d = this.players[j];
      const rnd = Math.floor(this.turn / 2);
      if (d.reacted_round === rnd || !this.agents[j].react) return null;
      const opts = [];
      for (const name of uniq(d.hand)) {
        const c = CARDS[name];
        if (c.kind === 'prayer' && c.prayer === style && src !== 'punch' && this.location !== 'Duel Arena') opts.push(name);
        else if (c.kind === 'reversal' && c.reacts_to === style) opts.push(name);
      }
      if (!opts.length) return null;
      const choice = await this.agents[j].react(this, j, amount, style, src, opts);
      if (!choice) return null;
      const c = CARDS[choice];
      removeOne(d.hand, choice);
      d.discard.push(choice);
      d.reacted_round = rnd;
      this.stat(j, 'reactions_' + c.kind);
      inc(d.cape_prog, 'reaction');
      if (c.kind === 'prayer') {       // Round 24: Protect prayers block the attack
        this.note(`${subj(d)} ${vb(d, 'react', 'reacts')} with ${choice}, blocking a ${amount}-damage ${Cap(style)} attack`, j);
        this.emit({ type: 'react', who: j, card: choice });
        return choice;
      }
      this.note(`${subj(d)} ${vb(d, 'react', 'reacts')} with ${choice}: the ${amount}-damage ${Cap(style)} attack comes back as ${Cap(c.converts_to)}`, j);
      this.emit({ type: 'react', who: j, card: choice });
      const back = Math.floor(amount * this.rules.reversal_fraction);
      if (back > 0) await this.deal(j, back, c.converts_to, 'reversal');
      return choice;
    }

    async deal(i, amount, style, src, opts = {}) {
      if (this.winner !== null) return 0;
      const bonus = opts.no_bonus ? 0 : this.attack_bonus(i, style, src);
      amount += bonus;
      if (amount > 0 && !opts.unreactable && (await this.offer_reaction(i, amount, style, src))) return 0;
      if (this.winner !== null) return 0;
      const o = this.opp(i);
      let [to_lp, soaked] = this.compute_damage(i, amount, style, !!opts.ignore_armor, !!opts.ignore_prayer);
      let broke = null;
      if (opts.shatter && o.shield && !o.shield.virtual && !CARDS[o.shield.name].unshatter) soaked = soaked ? o.shield.soak : soaked;
      if (o.shield && soaked) {
        o.shield.soak -= soaked;
        if (o.shield.soak <= 0) {
          broke = o.shield.name;
          if (!o.shield.virtual) o.discard.push(o.shield.name);
          o.shield = null;
          this.stat(1 - i, 'shields_broken');
        }
      }
      if (this.location === 'Lumbridge' && to_lp >= o.lp && o.lp > 0 && !o.lumbridge_used) {
        o.lumbridge_used = true;         // Lumbridge: keep the last card, once per player
        to_lp = o.lp - 1;
        this.stat(1 - i, 'lumbridge_saves');
        this.note(`Lumbridge saves ${poss(o)} last card`, 1 - i);
      }
      o.set_lp(o.lp - to_lp);
      this.stat(i, 'dmg_' + src, to_lp);
      if (to_lp > 0) inc(this.players[i].cape_prog, style + '_hit');
      if (soaked) inc(o.cape_prog, 'soak', soaked);
      this.emit({ type: 'hit', by: i, target: 1 - i, amount: to_lp, soaked, style, src, broke });
      if (broke) this.note(`${Poss(o)} ${broke} breaks`, 1 - i);
      if (o.lp <= 0) {
        this.winner = i;
        this.end_reason = 'damage';
        return to_lp;
      }
      const p = this.players[i];
      if (to_lp && p.buff && CARDS[p.buff].effect === 'smite' && o.buff) {
        this.note(`Smite ends ${poss(o)} ${o.buff}`, i);
        o.discard.push(o.buff);
        o.buff = null;
      }
      if (to_lp && o.buff && CARDS[o.buff].effect === 'redemption' && o.lp <= 10) {
        o.set_lp(Math.min(this.rules.max_lp, o.lp + CARDS[o.buff].reward_val));
        this.note(`${Poss(o)} Redemption heals ${CARDS[o.buff].reward_val}`, 1 - i);
        o.discard.push(o.buff);
        o.buff = null;
      }
      return to_lp;
    }

    lose_material(p, name) {
      inc(p.materials, name, -1);
      p.discard.push(name);
    }

    wear(i) {
      const p = this.players[i];
      p.weapon.dur -= 1;
      if (p.weapon.dur <= 0) {
        this.note(`${Poss(p)} ${p.weapon.name} breaks`, i);
        this.unequip(p);
        this.stat(i, 'weapons_broken');
      }
    }

    // Earth spells and Crumble: the opponent's weapon loses durability, or their ammo loses shots.
    wear_opponent(i, k) {
      const o = this.opp(i);
      if (o.weapon && o.weapon.dur) {
        for (let n = 0; n < k; n++) if (o.weapon) this.wear(1 - i);
      } else if (o.ammo) {
        o.ammo.shots -= k;
        if (o.ammo.shots <= 0) {
          if (o.ammo.card) o.discard.push(o.ammo.card);
          o.ammo = null;
        }
      }
    }

    async autocast(i) {
      const c = this.players[i].weapon_card();
      await this.deal(i, c.hit, 'magic', 'autocast');
      if (this.players[i].weapon) this.wear(i);
    }

    async throw_(i, mod = 0, src = 'thrown') {
      const c = this.players[i].weapon_card();
      await this.deal(i, c.hit + mod, 'ranged', src);
      if (this.players[i].weapon) this.wear(i);
    }

    swing_options(p) {
      const w = p.weapon_card();
      if (!w) return [];
      if (w.legendary && p.weapon.played === this.turn) return [];   // Telegraph
      if (w.kind === 'bow') {
        const opts = p.ammo ? ['ranged'] : [];
        if (w.bash_hit && p.weapon.dur) opts.push('melee');
        return opts;
      }
      if (!p.weapon.dur) return [];
      if (w.kind === 'thrown') return ['ranged'];
      if (w.kind === 'staff') return ['magic'];
      return w.styles.length ? [...w.styles] : ['melee'];
    }

    async do_swing(i, style) {
      const p = this.players[i];
      const w = p.weapon_card();
      if (w.kind === 'bow') {
        if (style === 'ranged') await this.shoot(i);
        else {
          await this.deal(i, w.bash_hit, 'melee', 'weapon');
          if (p.weapon) this.wear(i);
        }
      } else if (w.kind === 'thrown') await this.throw_(i);
      else if (w.kind === 'staff') await this.autocast(i);
      else {
        for (let k = 0; k < w.hits; k++) await this.deal(i, w.hit, style, 'weapon', { shatter: w.legendary });
        if (p.weapon) this.wear(i);
      }
    }

    async shoot(i, mod = 0, src = 'bow', ignore_armor = false, unreactable = false) {
      const p = this.players[i];
      if (!p.ammo || p.ammo.shots <= 0) return;
      const bow = p.weapon_card();
      const dmg = bow.hit + p.ammo.bonus + mod;
      p.ammo.shots -= 1;
      if (p.ammo.shots <= 0) {
        if (p.ammo.card) p.discard.push(p.ammo.card);
        p.ammo = null;
      }
      await this.deal(i, dmg, 'ranged', src, { ignore_armor, unreactable });
    }

    draw(i) {
      const p = this.players[i];
      if (!p.deck.length) {
        this.winner = 1 - i;
        this.end_reason = 'deck_out';
        return;
      }
      p.hand.push(p.deck.pop());
    }

    // ---------- legal actions ----------
    legal_actions(i) {
      const p = this.players[i];
      const no_damage = this.turn === 0;
      const acts = [['draw']];
      if (!no_damage) {
        acts.push(['punch']);
        for (const s of this.swing_options(p)) acts.push(['swing', s]);
        if (p.free_swing_ready) for (const s of this.swing_options(p)) acts.push(['free_swing', s]);
        if (this.can_spec(p)) acts.push(['spec']);
      }
      acts.push(...this.quest_actions(p));
      for (const c of this.claimable(i)) acts.push(['claim', c]);
      for (const name of uniq(p.hand)) acts.push(...this.card_actions(i, CARDS[name], no_damage));
      if (this.location === 'Varrock' && p.lp < this.rules.max_lp)
        for (const n of uniq(p.hand)) acts.push(['varrock', n]);       // discard a card, heal 3
      if (this.location === 'Grand Exchange' && !p.exchanged && p.deck.length)
        for (const n of uniq(p.hand)) acts.push(['exchange', n]);      // free: discard a card, draw one
      return acts;
    }

    card_actions(i, c, no_damage) {
      const p = this.players[i];
      const o = this.opp(i);
      const k = c.kind;
      const n = c.name;
      const play = [['play', n, null]];
      const w = p.weapon_card();
      switch (k) {
        case 'material': return play;
        case 'spell': return (!no_damage || !c.damage) && p.meets(c.req) ? play : [];
        case 'teleport': return play;
        case 'hammer': {
          const t = [];
          if (w && !w.legendary && p.weapon.dur) t.push(['play', n, 'weapon']);
          if (p.shield && !p.shield.virtual) t.push(['play', n, 'shield']);
          return t;
        }
        case 'weapon': case 'bow': case 'thrown': {
          let ok = true;
          if (c.fam_req.length && c.fam_req.some(([f, x]) => p.family_count(f) < x)) ok = false;
          return ok && p.meets(c.req) ? play : [];
        }
        case 'staff': return p.meets(c.req, true) ? play : [];
        case 'arrows': return w && w.kind === 'bow' && c.arrow_tier <= w.arrow_tier ? play : [];
        case 'ability':
          return !no_damage && w && w.kind === 'weapon' && w.wclass && c.allowed.includes(w.wclass) ? play : [];
        case 'rapid': case 'longrange': {
          const loaded = w && ((w.kind === 'bow' && p.ammo) || (w.kind === 'thrown' && p.weapon.dur));
          return !no_damage && loaded ? play : [];
        }
        case 'destroy':
          if (!p.meets(c.req)) return [];
          if (c.needs_melee_weapon && !(w && w.kind === 'weapon')) return [];
          {
            const acts = Object.keys(o.materials).filter((t) => o.materials[t] > 0 && CARDS[t].mat === c.destroys)
              .map((t) => ['play', n, t]);
            if (!acts.length && c.damage) acts.push(['play', n, null]);   // Round 25: never a blank
            return acts;
          }
        case 'pouch':
          return uniq(p.deck).filter((t) => CARDS[t].mat === 'rune').map((t) => ['play', n, t]);
        case 'armor':
          if (c.families && new Set(Object.keys(p.materials).map((m) => CARDS[m].mat)).size < c.families) return [];
          if (c.fam_req.length && c.fam_req.some(([f, x]) => p.family_count(f) < x)) return [];   // Round 22 armor
          return p.meets(c.req) ? play : [];
        case 'shield': return w && w.two_handed ? [] : play;
        case 'food': {
          if (this.location === 'Duel Arena') return [];
          if (p.lp >= this.rules.max_lp || !p.discard.length) return [];
          const used = c.stacks_with_food ? p.ate_karambwan : p.ate_food;
          return used ? [] : play;
        }
        case 'prayer': case 'reversal': return [];      // reactions answer an attack
        case 'npc':
          return uniq(p.discard).filter((t) => recoverable(c, CARDS[t])).map((t) => ['play', n, t]);
        case 'location': return this.location !== n ? play : [];
        case 'buff': return this.location !== 'Duel Arena' && p.meets(c.req) && p.buff !== n ? play : [];
        case 'quest':
          return !p.completed.includes(n) && (!p.quest || p.quest.name !== n) ? play : [];
        default: return [];
      }
    }

    // ---------- special attacks ----------
    can_spec(p) {
      const w = p.weapon_card();
      if (!w || !w.spec || p.energy < w.spec_cost) return false;
      if (w.legendary && p.weapon.played === this.turn) return false;
      if (FREE_SPECS.has(w.spec)) return !p.weapon.spec_used;
      if (w.kind === 'bow') return !!p.ammo;
      return !!p.weapon.dur;
    }

    async do_spec(i) {
      const p = this.players[i];
      const o = this.opp(i);
      const w = p.weapon_card();
      p.energy -= w.spec_cost;
      this.stat(i, 'specs');
      switch (w.spec) {
        case 'sanctuary': p.sanctuary = w.spec_val; p.weapon.spec_used = true; break;
        case 'light': p.light = true; p.weapon.spec_used = true; break;
        case 'war_god':
          await this.deal(i, w.hit, 'melee', 'spec');
          if (o.buff) {
            this.note(`Favour of the War God ends ${poss(o)} ${o.buff}`, i);
            o.discard.push(o.buff);
            o.buff = null;
          }
          if (p.weapon) this.wear(i);
          break;
        case 'sunder':
          await this.deal(i, w.hit + w.spec_val, 'melee', 'spec', { shatter: true });
          if (p.weapon) this.wear(i);
          break;
        case 'snapshot':
          await this.shoot(i, 0, 'spec');
          if (p.ammo) await this.shoot(i, 0, 'spec');
          break;
        case 'soulshot': await this.shoot(i, w.spec_val, 'spec', true, true); break;
        case 'iban':
          await this.deal(i, w.spec_val, 'magic', 'spec');
          if (p.weapon) this.wear(i);
          break;
        case 'lightning':
          await this.deal(i, w.hit, 'melee', 'spec');
          await this.deal(i, w.spec_val, 'magic', 'spec');
          if (p.weapon) this.wear(i);
          break;
        default: break;
      }
    }

    // ---------- quests ----------
    quest_actions(p) {
      if (!p.quest) return [];
      const [kind, arg] = CARDS[p.quest.name].steps[p.quest.step];
      if (kind === 'discard') return uniq(p.hand).filter((n) => arg === null || CARDS[n].kind === arg).map((n) => ['quest', n]);
      if (kind === 'spend') return Object.keys(p.materials).map((m) => ['quest', m]);
      const ok = { have: () => p.family_count(arg[0]) >= arg[1], weapon: () => p.weapon !== null,
        armor: () => p.armor !== null, shield: () => p.shield !== null }[kind]();
      return ok ? [['quest', null]] : [];
    }

    async do_quest_step(i, target) {
      const p = this.players[i];
      const c = CARDS[p.quest.name];
      const [kind] = c.steps[p.quest.step];
      if (kind === 'discard') {
        removeOne(p.hand, target);
        p.discard.push(target);
      } else if (kind === 'spend') this.lose_material(p, target);
      p.quest.step += 1;
      this.stat(i, 'quest_steps');
      if (p.quest.step >= c.steps.length) {
        p.completed.push(c.name);
        p.removed.push(c.name);
        p.quest = null;
        p.qp += c.qp;
        this.note(`${subj(p)} ${vb(p, 'complete', 'completes')} ${c.name} (${p.qp} QP)`, i);
        this.emit({ type: 'quest', who: i, card: c.name });
        await this.quest_reward(i, c);
      }
    }

    fetch(p, fam, n) {
      for (let k = 0; k < n; k++) {
        const pick = p.deck.filter((m) => CARDS[m].kind === 'material' && CARDS[m].mat === fam);
        if (!pick.length) return;
        removeOne(p.deck, pick[0]);
        inc(p.materials, pick[0]);
      }
      this.rng.shuffle(p.deck);
    }

    async quest_reward(i, c) {
      const p = this.players[i];
      const o = this.opp(i);
      switch (c.effect) {
        case 'heal': p.set_lp(Math.min(this.rules.max_lp, p.lp + c.reward_val)); break;
        case 'draw': for (let k = 0; k < c.reward_val; k++) if (p.deck.length) p.hand.push(p.deck.pop()); break;
        case 'fetch_rune': this.fetch(p, 'rune', c.reward_val); break;
        case 'fetch_bar': this.fetch(p, 'bar', c.reward_val); break;
        case 'fetch_prayer': {
          const pick = p.deck.filter((n) => ['buff', 'prayer'].includes(CARDS[n].kind));
          if (pick.length) {
            let best = pick[0];
            for (const n of pick) if (CARDS[n].bonus > CARDS[best].bonus) best = n;
            removeOne(p.deck, best);
            p.hand.push(best);
            this.rng.shuffle(p.deck);
          }
          break;
        }
        case 'lose_action': o.lost_actions = c.reward_val; break;
        case 'damage': await this.deal(i, c.reward_val, 'none', 'quest', { ignore_armor: true, unreactable: true }); break;
        case 'chicken':
          if (o.weapon) {
            this.note(`${Poss(o)} ${o.weapon.name} becomes a chicken`, i);
            this.unequip(o);
          }
          break;
        case 'silverlight': {
          const ws = p.deck.filter((n) => WEAPON_KINDS.includes(CARDS[n].kind));
          if (ws.length) {
            let best = ws[0];
            const val = (n) => CARDS[n].hit * Math.max(1, CARDS[n].durability);
            for (const n of ws) if (val(n) > val(best)) best = n;
            removeOne(p.deck, best);
            p.hand.push(best);
            await this.equip(i, best);
            this.rng.shuffle(p.deck);
          }
          break;
        }
        case 'dragon_slayer':
          for (const kind of ['armor', 'shield']) {
            const pick = p.deck.filter((n) => CARDS[n].kind === kind);
            if (pick.length) {
              removeOne(p.deck, pick[0]);
              p.hand.push(pick[0]);
              await this.equip(i, pick[0]);
            }
          }
          this.rng.shuffle(p.deck);
          break;
        default: break;
      }
    }

    async equip(i, name) {          // put equipment from hand into play without the usual checks
      removeOne(this.players[i].hand, name);
      await this.apply_card(i, name, null);
    }

    // ---------- applying actions ----------
    async apply(i, a) {
      const p = this.players[i];
      switch (a[0]) {
        case 'draw': this.stat(i, 'draw_actions'); this.draw(i); return;
        case 'swing': case 'free_swing':
          if (a[0] === 'free_swing') { p.free_swing_ready = false; p.free_swing_used = true; }
          await this.do_swing(i, a[1]);
          return;
        case 'punch':
          this.stat(i, 'punches');
          await this.deal(i, this.rules.punch_damage, 'melee', 'punch', { ignore_armor: true, ignore_prayer: true });
          return;
        case 'spec': await this.do_spec(i); return;
        case 'quest': await this.do_quest_step(i, a[1]); return;
        case 'claim':
          this.cape_claimed[a[1]] = i;
          p.cape = 0;
          p.cape_name = a[1];
          this.stat(i, 'cape_claimed');
          this.stats[i].cape_claim_turn = this.own_turn();
          if (a[1] !== (this.rules.capes || [null, null])[i]) this.stat(i, 'cape_stolen');
          this.emit({ type: 'claim', who: i, cape: a[1] });
          return;
        case 'pass': return;
        case 'varrock': {
          removeOne(p.hand, a[1]);
          p.discard.push(a[1]);
          const before = p.lp;
          p.set_lp(p.lp + Math.min(3, this.rules.max_lp - p.lp));
          this.stat(i, 'varrock');
          this.note(`${subj(p)} ${vb(p, 'rest', 'rests')} in Varrock, discarding ${a[1]}`, i);
          this.emit({ type: 'heal', who: i, amount: p.lp - before });
          return;
        }
        case 'exchange':
          p.exchanged = true;
          removeOne(p.hand, a[1]);
          p.discard.push(a[1]);
          this.stat(i, 'exchanges');
          this.note(`${subj(p)} ${vb(p, 'trade', 'trades')} ${a[1]} at the Grand Exchange`, i);
          this.draw(i);
          return;
        default: {
          const [, name, target] = a;
          removeOne(p.hand, name);
          await this.apply_card(i, name, target);
        }
      }
    }

    async apply_card(i, name, target) {
      const p = this.players[i];
      const o = this.opp(i);
      const c = CARDS[name];
      const k = c.kind;
      this.stat(i, 'played_' + k);
      switch (k) {
        case 'material': inc(p.materials, name); inc(p.fresh, name); break;
        case 'spell': {
          if (c.damage) await this.deal(i, c.damage, 'magic', 'spell');
          if (c.wears) this.wear_opponent(i, c.wears);
          if (c.heals && this.winner === null) {
            const before = p.lp;
            p.set_lp(p.lp + Math.min(c.heals, this.rules.max_lp - p.lp));
            if (p.lp > before) this.emit({ type: 'heal', who: i, amount: p.lp - before });
          }
          p.discard.push(name);
          if (c.fetch) await this.teleport(i, c.fetch);
          for (let n = 0; n < c.draws; n++) if (p.deck.length) p.hand.push(p.deck.pop());
          const w = p.weapon_card();
          if (w && w.free_swing_after_spell && !p.free_swing_used) p.free_swing_ready = true;
          break;
        }
        case 'weapon':
          this.unequip(p);
          if (c.two_handed && p.shield && !p.shield.virtual) {
            p.discard.push(p.shield.name);
            p.shield = null;
          }
          p.weapon = { name, dur: c.durability, energy: 0, played: this.turn };
          if (c.legendary) this.stat(i, 'legend_equipped');
          break;
        case 'thrown': this.unequip(p); p.weapon = { name, dur: c.durability }; break;
        case 'staff': this.unequip(p); p.weapon = { name, dur: c.durability, energy: 0 }; break;
        case 'bow':
          this.unequip(p);
          p.weapon = { name, dur: c.bash_hit ? c.durability : null, energy: 0 };
          if (c.two_handed) {
            if (p.shield && !p.shield.virtual) p.discard.push(p.shield.name);
            p.shield = { name, soak: c.soak, virtual: true };
          }
          if (!p.ammo || p.ammo.tier > c.arrow_tier) {
            if (p.ammo && p.ammo.card) p.discard.push(p.ammo.card);
            p.ammo = { card: null, bonus: 0, shots: this.rules.bow_shots, tier: 1 };
          }
          break;
        case 'arrows':
          if (p.ammo && p.ammo.card) p.discard.push(p.ammo.card);
          p.ammo = { card: name, bonus: c.arrow_bonus, shots: this.rules.arrow_shots, tier: c.arrow_tier };
          break;
        case 'ability': {
          const w = p.weapon_card();
          await this.deal(i, w.hit + c.damage, 'melee', 'ability', { ignore_armor: c.ignores_armor });
          for (let n = 0; n < (c.durability || 1); n++) if (p.weapon) this.wear(i);
          p.discard.push(name);
          break;
        }
        case 'rapid':                  // two shots (or throws) for one action
          for (let n = 0; n < 2; n++) {
            if (p.weapon && p.weapon_card().kind === 'thrown') await this.throw_(i, 0, 'ability');
            else if (p.weapon) await this.shoot(i, 0, 'ability');
          }
          p.discard.push(name);
          break;
        case 'longrange':
          if (p.weapon_card().kind === 'thrown') await this.throw_(i, -1, 'ability');
          else await this.shoot(i, -1, 'ability');
          p.longrange = true;
          p.discard.push(name);
          break;
        case 'destroy':
          if (c.damage) await this.deal(i, c.damage, ['melee', 'ranged', 'magic'].includes(c.style) ? c.style : 'none', 'spell');
          if (target && o.materials[target] > 0) {
            inc(o.materials, target, -1);
            o.discard.push(target);
          }
          p.discard.push(name);
          break;
        case 'pouch':
          removeOne(p.deck, target);
          inc(p.materials, target);
          this.rng.shuffle(p.deck);
          p.discard.push(name);
          break;
        case 'armor':
          if (p.armor) p.discard.push(p.armor);
          p.armor = name;
          break;
        case 'shield':
          if (p.shield) p.discard.push(p.shield.name);
          p.shield = { name, soak: c.soak };
          break;
        case 'food': {
          const gain = Math.min(c.heal, this.rules.max_lp - p.lp);
          const before = p.lp;
          p.set_lp(p.lp + gain);
          inc(p.cape_prog, 'food');
          this.emit({ type: 'heal', who: i, amount: p.lp - before });
          if (c.stacks_with_food) p.ate_karambwan = true;
          else p.ate_food = true;
          p.discard.push(name);
          break;
        }
        case 'npc':
          removeOne(p.discard, target);
          if (CARDS[target].kind === 'material') inc(p.materials, target);
          else p.hand.push(target);
          p.discard.push(name);
          break;
        case 'teleport':
          p.discard.push(name);
          await this.teleport(i, c.fetch);
          break;
        case 'hammer':
          if (target === 'weapon') p.weapon.dur += c.bonus;
          else p.shield.soak += c.bonus;
          p.discard.push(name);
          break;
        case 'location':
          if (this.location) this.players[this.location_owner].discard.push(this.location);
          this.location = name;
          this.location_owner = i;
          if (name === 'Duel Arena') {      // no prayers: active buff prayers end
            for (const q of this.players) if (q.buff) {
              this.note(`The Duel Arena ends ${poss(q)} ${q.buff}`, i);
              q.discard.push(q.buff);
              q.buff = null;
            }
          }
          break;
        case 'buff':
          if (p.buff) p.discard.push(p.buff);
          p.buff = name;
          p.buff_new = true;
          p.buff_left = c.duration;
          if (c.duration) inc(p.cape_prog, 'elite_prayer');
          break;
        case 'quest':
          if (p.quest) {
            p.discard.push(p.quest.name);
            this.note(`${subj(p)} ${vb(p, 'abandon', 'abandons')} ${p.quest.name}`, i);
          }
          p.quest = { name, step: 0 };
          break;
        default: break;
      }
    }

    async teleport(i, loc) {         // play a location from your deck; nothing if it's not there or in play
      const p = this.players[i];
      if (p.deck.includes(loc) && this.location !== loc) {
        removeOne(p.deck, loc);
        this.rng.shuffle(p.deck);
        this.stat(i, 'teleports');
        this.note(`${subj(p)} ${vb(p, 'teleport', 'teleports')} to ${loc}`, i);
        await this.apply_card(i, loc, null);
      }
    }

    unequip(p) {
      if (p.weapon) {
        (CARDS[p.weapon.name].legendary ? p.removed : p.discard).push(p.weapon.name);
        if (p.shield && p.shield.virtual) p.shield = null;
        p.weapon = null;
      }
    }

    // ---------- turn loop ----------
    start_turn() {               // returns false if the game ended at the start of the turn
      const i = this.active;
      const p = this.players[i];
      p.prayer = null;
      p.longrange = false;
      p.ate_food = p.ate_karambwan = false;
      p.free_swing_ready = p.free_swing_used = false;
      p.sanctuary = 0;
      p.light = false;
      p.free_steps = 0;
      p.exchanged = false;
      p.fresh = {};
      if (p.cape !== null) {
        p.cape += 1;
        if (p.cape >= this.rules.cape_turns) {
          this.winner = i;
          this.end_reason = 'cape';
          return false;
        }
      }
      p.energy = Math.min(this.rules.max_energy, p.energy + 1);
      if (p.weapon) delete p.weapon.spec_used;
      if (p.buff && p.buff_left) {               // elite prayers last 3 of your turns
        p.buff_left -= 1;
        if (p.buff_left <= 0) {
          this.note(`${Poss(p)} ${p.buff} ends`, i);
          p.discard.push(p.buff);
          p.buff = null;
        }
      }
      p.buff_new = false;
      if (this.turn > 0) {                       // the first player skips the turn-1 draw
        this.draw(i);
        if (this.winner !== null) return false;
      }
      return true;
    }

    async play_turn() {
      const i = this.active;
      const p = this.players[i];
      if (!this.start_turn()) return;
      const actions = this.rules.actions - p.lost_actions;
      p.lost_actions = 0;
      await this.resume_turn(actions, 0);
    }

    async resume_turn(actions, free_plays) {
      const i = this.active;
      const p = this.players[i];
      while (actions > 0 && this.winner === null) {
        this.actions_left = actions;
        this.free_plays_used = free_plays;
        const a = await this.agents[i].choose(this, i, this.legal_actions(i));
        if (a[0] === 'pass') break;
        [actions, free_plays] = this.spend(i, a, actions, free_plays);
        await this.apply(i, a);
      }
      this.actions_left = 0;
      if (this.winner !== null) return;
      while (p.hand.length > this.rules.hand_max) {
        const name = await this.agents[i].discard_choice(this, i);
        removeOne(p.hand, name);
        p.discard.push(name);
      }
      this.turn += 1;
      this.active = 1 - i;
    }

    // Does this action leave the turn's action count alone? (Called before apply.)
    is_free(i, a, free_plays) {
      const p = this.players[i];
      if (a[0] === 'quest' && p.free_steps < this.rules.free_quest_steps) return true;
      if (a[0] === 'exchange') return true;
      if (free_plays >= this.rules.free_plays_max) return false;
      if (a[0] === 'free_swing') return true;
      if (a[0] === 'spec') return FREE_SPECS.has(p.weapon_card().spec);
      if (a[0] === 'play') {
        const c = CARDS[a[1]];
        return c.free || c.kind === 'buff';
      }
      return false;
    }

    spend(i, a, actions, free_plays) {
      const p = this.players[i];
      if (a[0] === 'quest' && p.free_steps < this.rules.free_quest_steps) {
        p.free_steps += 1;
        return [actions, free_plays];
      }
      if (a[0] === 'exchange') return [actions, free_plays];   // Grand Exchange: free, outside the cap
      if (this.is_free(i, a, free_plays)) return [actions, free_plays + 1];
      return [actions - 1, free_plays];
    }

    progress(p, cape) {
      const def = CAPES[cape];
      const need = (this.rules.cape_need && this.rules.cape_need[cape]) || def.need;
      if (def.kind === 'event') return [get(p.cape_prog, def.what), need];
      if (def.what === 'qp') return [p.qp, need];
      return [p.family_count(def.what), need];
    }

    claimable(i) {
      const p = this.players[i];
      if (p.cape !== null) return [];
      return this.cape_table.filter((c) => !(c in this.cape_claimed) && this.progress(p, c)[0] >= this.progress(p, c)[1]);
    }

    async run(max_turns = 400) {
      while (this.winner === null && this.turn < max_turns) await this.play_turn();
      if (this.winner === null) this.end_reason = 'timeout';
      return this.winner;
    }
  }

  function recoverable(npc, card) {
    for (const spec of npc.recovers) {
      if (spec === 'any') return true;
      if (spec === 'item' && card.item) return true;
      if (card.kind === 'material' && card.mat === spec) return true;
      if (card.kind === spec) return true;
    }
    return false;
  }

  const api = { setData, CARDS, CAPES, DECKS, RULES, Game, Player, RNG, recoverable, uniq, FREE_SPECS, WEAPON_KINDS, ELEMENTAL };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCG = api;
})(typeof window !== 'undefined' ? window : globalThis);
