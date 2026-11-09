# RuneScape TCG simulator

Plays the three Set 1 starter decks against each other thousands of times
with a simple bot, to find balance problems before anyone plays by hand.
Needs Python 3.9+ and nothing else.

## Run it

    python simulate.py            # 1000 games per seat order per matchup (~30 s)
    python simulate.py 300        # quicker
    python simulate.py 1000 --respawn-cost 5 --punch 1
    python experiments.py         # baseline vs. a list of rule and card tweaks

## Files

| File | What it is |
| --- | --- |
| `cards.py` | Every card's numbers and the three starter decklists. Change a card here. |
| `engine.py` | The rules: turns, actions, damage order, Punch, Respawn, slots. No strategy. |
| `bot.py` | A greedy bot that scores each legal action and takes the best one. |
| `simulate.py` | Runs every matchup in both seat orders and prints the summary. |
| `experiments.py` | Runs a list of tweaks side by side. Add your own lines to `EXPERIMENTS`. |

## Using the engine for a real game later

The engine never decides anything. Each turn it asks the active player's
agent to pick from `game.legal_actions(i)` and applies the choice with
`game.apply(i, action)`. A human interface or a smarter AI opponent plugs in
the same way the bot does: any object with `choose(game, i, legal)` and
`discard_choice(game, i)`.

## Assumptions the rules doc doesn't settle

- Food can't heal above 50 LP.
- Equipping a bow with an empty Ammo slot loads its 4 built-in Bronze shots.
- Punch is reduced by Longrange's melee reduction, since that isn't armor.
- The bot knows which style the opponent's deck plays (as a player would after a turn or two).

## Swing rule and tuning

`swing_action` is on by default (adopted): weapons, bows, staves and thrown
weapons only attack when you spend an action on `("swing", style)`. Hybrid
weapons offer one swing per style. `staff_autocast` is an old experiment, off.

    python tune.py search 50 100     # search weapon/bow/staff/spell numbers under the swing rule
    python tune.py confirm 400       # rerun the best few with more games
    python hybrids.py 400            # dual-style decks against the starters
    python field.py 200              # every test deck against every other
    python diagnose.py 150           # how each deck's games go: start speed, damage per turn, action use
    python replay.py "Lean Melee" "Smoke Mage" 7    # one game, turn by turn, in plain English (add --reversals)

## Reactions

Prayers and reversals (Mirror Shield, Ricochet, Parry) are reactions: the
engine offers them to the defending agent through `react(game, i, amount,
style, src, options)` when an attack is about to land, one per player per
round. `reversal_fraction` (default 1.0) sets how much of a reversed attack
goes back. `cards.with_reactions(deck, reactions)` swaps reactions into a
deck, and `cards.NATURAL_REVERSALS` gives each test deck its natural set.
Reactions show up in `replay.py` output as lines starting with `!`.

`cards.py` also holds the lean, dual-style (with and without hybrid weapons),
Thrown Ranged, Melee/Thrown and Smoke Mage test decks.

## Prayers, special attacks and quests (Round 7)

- **Buff prayers** (`kind="buff"`): free to play, one at a time, +bonus to every
  attack of their style (not Punch or reversals). Elite ones need Bones in play
  and end when the Bones go (`check_buff`). `buff_upkeep` > 0 brings back the
  first design, where each turn burns a Bones; `buff_free` False makes them an action.
- **Special attacks** (`("spec",)`): energy is the player's (`player_energy`), +1 at
  the start of each of your turns, up to `max_energy`. Effects are in `Game.do_spec`.
- **Quests** (`("quest", target)`): one in progress; starting another abandons it.
  `free_quest_steps` steps per turn cost no action. Rewards are in `Game.quest_reward`.
  The Quest Point Cape wins at `cape_qp` quest points.

      python new_cards.py 100              # every Round 7 package against the reaction field
      python new_cards.py 100 specs        # only packages with "specs" in the name
      python new_cards.py 100 --field      # Round 6 field vs Round 7 field (each deck's best package)
      python replay.py "Lean Ranged + specs" "R7 Lean Melee" 4

## The smarter bot (Round 8)

`planner.RolloutBot` plays close decisions out to the end of the game (12 playouts
per candidate move, both sides greedy, hidden cards resampled) and picks the move
that wins most. No tuned weights. It beats the greedy bot 58-62% with identical
decks, at about 400x the cost per game. Plug it in anywhere a `HeuristicBot` goes.

    python smart.py field 10        # Round 6 and Round 7 fields, smart bot on both sides
    python smart.py packages 10     # key Round 7 packages
    python smart.py confirm 20 "Lean Ranged + specs"
    python replay.py "Lean Ranged" "Lean Melee" 3 --smart

## Round 9: no Bones, LP sweep

Bones and Bonecrusher are gone. Elite prayers last 3 of your turns (`Card.duration`);
Smite and the Ancient Mace end the opponent's prayer. Protect prayers cost nothing and
halve the attack (`protect_halves`). `lp_sweep.py` runs the field at several starting
Life Points:

    python lp_sweep.py 60               # 30, 40, 50, 60 and 75 LP
    python lp_sweep.py 60 40 50         # only these

## Round 10: deck as life

`rules=engine.DECK_LIFE` plays the Harry Potter TCG way: `Player.lp` becomes the
number of cards in your deck, damage mills the top, healing returns random
discards to the bottom, and an empty deck loses. No Respawn.

    python deck_life.py 80                    # field, packages and starters: 50 LP vs deck as life
    python smart.py field7 10 --deck-life     # Round 7 field with the playout bot

## Round 11: the weakest decks

Deck as life is now the default (`engine.LIFE_POINTS` brings back 50 LP). `weak.py`
shows how each field deck's games go (speed, damage per turn, dead cards, action
mix). New hybrid cards: dual materials (Enchanted Bar, Mithril Grapple, Enchanted
Logs) and Void Knight Top. The Air Mage starter swaps Banker, a Wizards' Tower and
its Shrimp for Augury x2 and Mystic Will x2.

    python weak.py 60                     # per-deck diagnostics in the current field
    python hybrid_cards.py 80             # hybrid packages against the Round 7 field
    python hybrid_cards.py 100 --field    # Round 7 field vs Round 11 field
    python smart.py field11 10            # Round 11 field with the playout bot

## Round 12: Smoke Mage

    python smoke_test.py 150                                   # Smoke Mage variants, greedy bot
    python smoke_test.py 20 "Flurry 9 -> 8|x3|needs Air" --smart   # chosen variants, playout bot

## Round 14: one Legendary per deck (`legend.py`)
- One Legendary card per deck; Legendary materials are gone. Telegraph: "This weapon cannot attack on the
  turn it is played" (rule `legend_telegraph: "sick"`; the reveal-then-equip draft is `"reveal"`).
- Costs need materials of any tier in play: Dragon Dagger 2 bars, Granite Maul 3, Dark Bow 3 logs, Staff of
  the Dead 3 runes. Tuned so each is about +10% win rate when it's in your opening hand: Dragon Dagger one
  swing (was +31% with two), Dark Bow hit 15 (was +2% at 12). Granite Maul lost equip-and-swing (it
  contradicts Telegraph). Greedy and playout bots agree within 2 points.
- Quest Point Cape needs 5 QP; Rune gear costs 1 Mithril + 2 Rune (both adopted).

## Round 15: the cape zone (`capes.py`, `cape_zone.py`)
- Each player brings one cape, chosen blind and revealed at the start. Either player can claim either cape
  when they meet its condition (an action); each cape is claimed once; you wear one. 
- Rule `capes: [cape0, cape1]`, `cape_need` overrides, `cape_turns` countdown.
- Finding: games last about 8 of your turns, and capes get claimed around turn 6, so a 5-turn countdown
  rarely finishes (capes decide 15% of games). At 3 turns they decide 38%, and each cape moves its deck's
  win rate by -4 to +6 against a no-cape baseline, except the Quest Point Cape (-10: quests cost too much
  tempo). Faster claims with a 5-turn countdown also decide 38%, but the material capes run away (+10 to +14).
- Adopted: countdown 3 (`cape_turns`), needs in capes.py. The bot goes all-in on damage when the opponent's cape
  wins at their next turn. Replays: `python replay.py "Smithing deck" "Magic deck" 9 --capes="Smithing Cape,Magic Cape"`.
- `python cape_zone.py h2h 60 3 --v3` (both bring capes), `built 40 3 --v3` (one cape, field opponent),
  `h2h 60 0` (no-cape baseline).
- Final cape tuning (card-list pass): Magic Cape at 3 hits (effect -1, was -4 at 4) and Quest Point Cape
  at 6 QP (smart bot -1 with a quest deck built for it; the greedy bot's -10 was it fumbling quests).

## Round 16: fixes found while porting to the browser game
- Elite prayers lasted 4 of your turns in the sim; the rule says 3 counting the turn you play one
  (rule `elite_fix`). A Protect prayer halving a buffed attack added the buff back after halving
  (rule `halve_fix`). Both fixed; the Round 11 field moves by at most 2 points (Thrown Ranged and
  Lean Mage -2), spread 9 either way (greedy, 150 games per seat order per pairing).
- `export_cards.py` writes `web/cards.json` (cards, decks, capes) for the browser game.
- Browser game (`web/`): `engine.js` and `bot.js` are a JavaScript port of the engine and greedy bot;
  `game.src.html` is the page; `python web/build.py` writes the single-file `web/dist/game.html`.
  Parity: 83 matchups x 400 games against the Python sim, win rates within chance (mean |z| 0.88,
  none above 2.5), same game length and cape rates (`web/test/spec.js`, `web/test/parity.js`).
  Playthrough tests in Chromium: `web/test/play.js`, `web/test/quest.js`.

## Round 17: group 1 (cards the sim knew but no deck used) — `group1.py`, `bestof.py`
- Most of the 42 are fine or slightly weak (reward quests, Protect from Melee, Superheat -2 to -4).
  Fire Blast/Wave are weak in Smoke Mage and neutral in a mono-Fire deck, which is weak (no Fire staff yet).
- Smart bot: Mace Melee +10, Rune Melee +8, specials +7 vs the Round 11 field. Crush 6->4 changed nothing;
  maces -1 hit gave +5; adopted Mithril Mace 10 (was 12), Bronze Mace 7 (was 8): -1 smart, +2 greedy.
- Best-of-each-style field (smart): Magic 55% avg (Smoke Mage 62), Melee 49% (Rune Melee 55), Ranged 46%.
  No Melee nerf. Watch Smoke Mage; Ranged is the weakest style on average.

## Round 18: group 2 (cards the sim had never seen) — `group2.py`, `g2probe.py`
- 34 new cards in cards.py/engine.py and the JS engine. Rulings (Ramon): Lumbridge once per player; Duel Arena
  stops all prayers and ends active ones; Grand Exchange is free, once per turn. Defaults (not yet ruled): Water
  spells heal even if reacted to; teleports do nothing if Varrock isn't in the deck or is in play; Smith's Hammer
  can't target a Legendary or a bow with no durability; Anti-dragon Shield ignores Sunder too; Brother Jered
  recovers buff and Protect prayers; Wise Old Man takes the Legendary slot.
- Vs the best-of field: Air-pair flurry decks are strong (Mist Mage 59 greedy / 65 smart, Dust Mage 62 / 58);
  non-Air pairs weak (-6 to -9). Flurry cost/damage nerfs move them 1-2 points: the strength is Air Strike and
  the Air-only special staves, not the flurries.
- Water Mage -17/-18 (both bots). Air's damage fixes it to 46%; splashing Air for Iban's/Light +5.
- Staff of Water/Earth/Fire are starter-grade (like Staff of Air). Anti-dragon Shield < Kiteshield even vs
  Legendaries. Everything else within -5..+5 (Mage Arena +3/+4, Smith's Hammer in Mace +5 smart, Shark +2..+4).
- JS parity: 128 matchups x 400 games, mean |z| 0.82.

## Round 19: Air and Water settled — `r19.py`, `web/test/parity19.py`
- Air was never too strong: Iban's Staff and Staff of Light needed an Air Rune, so only Air decks could run them.
  Adopted (Ramon): both need 1 Chaos + 1 Air/Water/Earth/Fire rune (`"Elemental Rune"` in req), and Staff of Light
  counts as 2 of the Air/Water/Earth/Fire runes you have the most of in play (`counts_as=(("Elemental Rune", 2),)`,
  `Player.main_elements`): combination runes count for both elements, ties all qualify (you pick per cast),
  none in play counts as nothing. No Air or Water spell numbers change.
- 20-deck round robin, greedy, 300 games per ordered pairing: Mist 65->60, Dust 61->57, Steam 51->60, Mud 47->60,
  Lava 46->56, Lean Mage (pure Air) 52->47, Water Mage on Lean Mage's list 30->50. Air nerfs on top and Water at
  Air's damage both tested and not needed. New low: Fire Mage 32% (no special staves).
- `python r19.py 300` (Round 18 lists with fair staves, all variants), `python r19.py 300 --b` (Round 19 lists).

## Round 20: Fire Mage fixed by its list, not its cards — `r20.py`, `smart20.py`
- Fire Mage's 32% was a stale Round 17 list: 8 damage spells, 3 Superheat (only hits bar decks), and no Iban's
  Staff because it needed Air until Round 19. Swapping **Superheat 3 -> Iban's Staff 3** takes it to 51% (greedy,
  20-deck round robin, 300 games per ordered pairing) and 51.1% with the smart bot (vs 31.6%, 15 games per seat
  vs all 19 decks). No other deck moves more than 1 point; best-worst spread 28 -> 24.
- Other lists (printed cards): Staff of Fire -> Iban's 48, Water Mage's shell on Fire 46, that shell with Wave 45.
- Card options, played with the fixed list: Staff of Fire counts as 3 (51, no effect: games end with ~3 Fire runes
  in play and Strike/Bolt need 2/3), Fire spells shatter shields (51, no effect: every Fire spell already breaks
  Kiteshield/Wooden Shield in one hit), Fire Strike needs 1 Fire (51; Smoke/Steam +2), Fire Strike 1 Fire for 7
  (49), whole Fire line 1 fewer rune (52, but Steam 64, Smoke 59, Lava 59, spread 29), Fire spells ignore shields
  (54, mostly vs Ranged +5-8). None needed.
- Engine: new `Card.burns` field ("shatter"/"pierce", default off) and `deal(..., ignore_shield=)` for the test;
  no printed card uses it. Not ported to JS.
- Watch: Iban's Staff is now in every mage list (autocast + spec ~40% of Fire Mage's damage). Fire Blast/Wave stay
  weak; Staff of Fire counting as 3 is the lever if they're ever revisited, since only Blast/Wave need 4-5 Fire.
- `python r20.py 300 --lists`, `python r20.py 300 --list "L1 Superheat -> Iban's"`, `python smart20.py 15`.

## Round 21: every card tested — `sweep.py`, `capefix.py`, `report21.py`
- All 172 Set 1 cards (+ Crumble, sim-only) under Round 19 rules with the Round 20 Fire Mage list. One measure: VALUE OVER
  BLANK — the card in a deck that would run it vs the same deck with those slots as a Blank (`Card("Blank")`, can never
  be played), same seeds, vs the other 19 field decks, both seats. Off-field cards are swapped into a host (mostly earlier
  rounds' swaps); "vs replaced" = swapped deck - host. Basic materials: 2 copies blanked. Specialty runes also vs the two
  basics. Quests also blanked in a Quest Point Cape deck with the cape in play. Capes: host brings it, opponent brings none.
- Greedy: 100 games per opponent per seat, ~900k games (`sweep_greedy.json`, ±1 pt). Smart: the 50 flagged items,
  4 per opponent per seat (`sweep_smart.json`, ±5 pts), run in 3 checkpointed shards (`--part k/3`, `--merge 3`).
  Uses per game counted by `CGame` (a Game subclass; clones are plain Games, so rollouts don't count or leak).
- Verdicts (`report21.py`): 78 Fine, 43 Low impact, 13 Resource, 9 Strong, 24 Weak, 3 Dead, 3 Bot-limited.
  Strong: Iban's Staff, Shark (R), Willow Shortbow (beats Magic Shortbow, hit 5 vs 6), Smithing/Woodcutting/Runecrafting/
  Prayer/Cooking capes, Quest Point Cape (smart only). Weak: all armor but Void Knight Top, Smite, Mystic Will, Grand
  Exchange, Varrock Teleport + Law Rune, Thessalia, Horvik, Gunnar's Crusade, Superheat, Anti-dragon Shield, Protect from
  Melee, Fire/Earth Blast/Wave, Magic Shortbow, Ranged Cape. Dead: Protect from Missiles, Tinderbox, Crumble.
  Bot-limited (greedy can't play, smart can): Quest Point Cape, Varrock + Tablet, The Restless Ghost.
- Capes (`capefix.py`, greedy 100): each +1 to a need costs ~8 pts. Smithing +10.6 (Rune Thrown +11.6 -> 71%),
  Woodcutting +4.5 (Yew Ranged +10.3), Runecrafting +8.3, Prayer +10.9, Cooking +8.8, Attack +6.6, Magic +6.2, Defence
  +4.4, Ranged -1.6 (claimed by its own deck 60%, stolen 7%; 4 hits come too late; 3: -0.2, 2: +2.4). Quest Point Cape
  (smart): +8.8 to +13 at 6 QP, +0.3 at 7 (quest deck 27% without it). Candidate (not adopted): Smithing 6, Woodcutting 7,
  Runecrafting 7, Ranged 3; Prayer 3 (+1.2) and Cooking 4 (+3.2) tested but their capes mostly help Melee.
- Results are in the rules doc, tab "Card test (Round 21)". Nothing adopted.

## Round 22, batch 1: number tweaks — `r22.py`, `r22_smart.py`
- Same tests as Round 21 (sweep.py hosts and seeds); only the tested card changes, so host/blank/opponents are fixed.
  Engine hooks (inert unless a card uses them): buff `prayer="any"` boosts every style; Grand Exchange `draws` per trade.
- Fixed (greedy 100 / smart 12 or 4), vs replaced before -> after:
  Smite +3 to all attacks: -2.0 -> -0.3 (smart +0.7 -> +4.6). Anti-dragon Shield soak 7: -3.2 -> -1.4 (smart 12:
  -2.9 -> +2.9). Grand Exchange free to play: -1.8 -> -0.1 (smart 12: -0.2 -> +0.2). Fire Blast Death + 3 Fire:
  -4.7 -> -1.4 (smart +1.3 -> +0.7). Fire Wave Blood + 4 Fire: -6.9 -> -3.1 (smart -0.7 -> +2.0).
- Not weak after all: Earth Blast/Wave (control: Air Blast under the same Air Strike 4 swap is -6.4, Air Wave -8.0, so
  the Round 21 swap was unfair); Mystic Will (even with Burst of Strength and Sharp Eye, its Common peers).
- Rejected: Magic Shortbow hit 6 or 1 Normal + 1 Willow (Lean Ranged 52.9 -> 57.8, Ranged avg 54.9 -> 56.9: a Ranged
  buff); Grand Exchange draws 2 (worse: extra draws cost deck-life); raw damage bumps on Fire/Earth spells.
- Deferred to the armor round: Horvik, Thessalia. Smart bot at 4 games per seat is ±5 pts; settle small calls at 12.
- Nothing adopted yet.

## Round 22, batch 2: armor — `r23.py` (`--b`, `--c`, `--d`, `--force`, `--rr`)
- 2a (no weakness / weakness +1 / free to play / both): no effect on any card or deck (all within 0.5).
- 2b (main style + -1 to the others; flat -1/-2/-3 by tier): still about zero.
- Why: Wizard Robe drawn in 50% of Lean Mage games, worn in 36% of those; 95 of 122 misses had no Cloth in play
  (decks run 2 Cloth, which does nothing else). Bronze Platebody (bars plentiful) drawn 175, worn 35: the bot
  chooses not to. Forced-armor bot (`--force`): printed armor worn every time is WORSE than not (-0.3 to -1.3/copy),
  so the bot was right; with the 2c fix, worn armor is +0.6 to +2.7/copy.
- 2c: armor costs your style's own material (robes 1/2/3 runes, d'hide 1/2/3 logs via `fam_req`; engine now checks
  `fam_req` for armor). Robes/d'hide go positive; Melee avg 39 -> 35 (flat per-hit reductions hurt many small hits,
  and Melee loses the +3 from d'hide's weakness).
- 2d (best): style materials + -1 to the other styles + platebodies +2 to all your attacks (`bonus`, like Void):
  every armor card +0.9 to +2.9/copy; Melee 39.1 -> 37.6, Ranged 55.0 -> 54.6, Magic 52.0 -> 52.6, spread 24.3 -> 24.5.
  Thrown decks lose Leather Body (no logs): Rune Thrown 60.4 -> 57.6. Bot now values armor `bonus`
  (Python only so far). Cloth and Leather would have no use left (Thessalia recovers only them).
- Not adopted.
- 2e: platebody +3 vs +2: Melee 37.5 -> 38.1, spread 24.9 -> 23.4 (greedy 300). Ramon: not enough to matter; adopt +2.
- ADOPTED (2026-11-03): style materials + -1 to other styles + platebodies +2 attacks; Cloth, Leather, Thessalia cut
  (`cards.RETIRED`, kept in CARDS so old experiments run; not exported). `cards.modernize(deck)` converts decks:
  Cloth -> top basic rune, Leather -> top log (else bar), Thessalia -> Lowe/Doric, ranged armor in a no-log deck ->
  Bronze Platebody. Applied in r19.decks (field), export_cards (all game decks), STARTER_DECKS. Sheep Shearer: have 2
  materials (was 1 Cloth); Tinderbox: no requirement. Engine/JS: armor checks `fam_req`; bots value armor `bonus`.
- Adopted state, greedy 300 round robin: Melee 36.7 (was 38.8), Ranged 55.9 (55.0), Magic 52.4 (52.1), spread 24.1
  (24.0). Worse for Melee than the 2d test (37.5) because Thrown decks now wear Bronze Platebody (+2 attacks) instead
  of a dead Leather Body; switching them to shields instead is worse (Rune Thrown 60.8, spread 26.4).
- Parity: parity19 232 matchups x 200, mean |z| 0.87; the 5 outliers rerun at 2000 games all z < 1. Game v6.

## Round 23: cape needs — `r24.py`
- Re-tested under Round 22 rules (mages +2 runes, ranged +2-4 logs). Value = host with cape - host without (greedy 100):
  Smithing 5/6/7: +11.1/+3.9/+0.2 (Rune Thrown +15.3/+6.3/+1.2); Woodcutting 6/7/8: +5.8/+0.8/0 (Yew Ranged
  +12.5/+5.4/0); Runecrafting 6/7/8: +9.7/+3.9/+0.5 (Wave Mage +11.6/+5.2/+1.7); Ranged 4/3/2: +1.1/+3.6/+5.2
  (Rune Thrown +0.8/+2.5/+6.9); Prayer 2/3: +12.2/+1.8; Cooking 3/4: +10.0/+3.7; Attack 6/7: +8.5/+7.1;
  Magic +5.8; Defence +5.2.
- Smart (6 per seat): Smithing 6, Woodcutting 7, Runecrafting 7 all lower and still positive. Ranged (16 per seat):
  4 vs 3 tied (Lean Ranged +3.8/+3.0, Rune Thrown -1.3/+0.5).
- ADOPTED: Smithing 6, Woodcutting 7, Runecrafting 7, Ranged 3. Prayer, Cooking, Attack kept (they help Melee, the
  weakest style); Quest Point kept at 6. Game v7.

## Round 24: Protect prayers block — `r25.py`, `web/test/parity24.py`
- Round 21 tested each Protect in the wrong deck (Missiles in Lean Melee, Melee in Lean Mage). Re-tested in decks that
  lose to its style, bots valuing a halving Protect honestly (`HeuristicBot.honest_protect`, off by default).
- Greedy 100, vs replaced (all / vs target style), printed (halve) -> block 1 attack:
  Magic in Lean Melee -0.5/+0.3 -> +2.3/+4.7, Rune Melee +1.3/+1.0 -> +3.7/+4.9; Melee in Lean Ranged +1.2/+6.6 ->
  +2.1/+11.0, Thrown Ranged +1.2/+3.0 -> +2.4/+8.5; Missiles (for Wizards' Tower) in Lean Mage -0.5/-2.9 -> 0.0/-0.5,
  Smoke Mage -1.1/-3.0 -> -0.1/+1.8. "Block + halve rest of turn" (`protect_turn`) adds ~0; any-style (`protect_any`)
  tested OK but not needed. Missiles is used (152 blocks / 320 games vs Ranged, ~10.5 each); mages lose to Ranged by
  the triangle. Smart 6 per seat: inconclusive (Protect fires ~0.3/game). Ramon accepted the greedy result.
- ADOPTED: `protect_halves` False (block). JS engine + UI text + bot unchanged (JS bot already valued a full block).
  Parity: 58 matchups x 300, mean |z| 0.74; one z 2.9 outlier traced to noise (fresh seeds all z <= 2.2). Game v8.

## Round 25: destroy cards — `r26.py`, `web/test/parity25.py`
- Destroy cards were blanks outside their matchup (only 2 field decks run logs, 6 bars, 12 runes). Each tested in a
  deck that loses to the style it destroys from. Engine: a destroy card with `damage` deals it (its style; Tinderbox
  "none") and is playable with no target; `destroy_n` (experiment, default 1).
- Greedy 100, vs replaced (all / vs decks running the material), printed -> +6 damage, always playable:
  Superheat Fire Mage -2.4/-0.1 -> +2.1/+5.1, Smoke Mage -1.5/-1.5 -> +2.0/+1.6; Gunnar's Lean Melee -2.2/-2.7 ->
  +0.7/+1.5, Rune Melee -1.1/-1.3 -> +1.3/+2.2; Tinderbox Lean Mage -2.2/-2.8 -> +2.8/+2.8, Water Mage -2.3/-1.0 ->
  +2.9/+3.2. +4 damage ~even; destroy 2 alone ~no help; +4 + destroy 2 ~ +6. Tinderbox splash (Lean Melee, Rune Thrown,
  Yew Ranged, Steam Mage) +0.7 to +2.5: not an auto-include. Smart (6 per seat): all better vs target, 5/6 overall.
- ADOPTED: damage 6 on all three. JS engine/bot/UI ported (Superheat now labelled a Spell). Parity 87 x 300, mean |z|
  0.77. Game v9.

## Round 26: teleport cut
- Ramon: cut the Varrock Teleport spell and Law Runes (you break a tablet to escape a fight; casting a teleport takes
  too long). Crumble (sim-only) cut too. All three in `cards.RETIRED`; `modernize` turns Law Rune/Crumble into the
  deck's top basic rune and the spell into Varrock Teleport Tablets. Only Earth Mage changed (Crumble 2 -> Earth Rune
  2). Set 1 is now 167 cards. Game v11. Every Round 21 problem card is fixed or cut.
- Rulings confirmed (Ramon): Water heals even if blocked/reversed; Varrock Teleport does nothing if Varrock isn't in
  your deck or is in play; Smith's Hammer not on Legendary weapons or bows; Anti-dragon Shield ignores Shatter and
  Sunder; Brother Jered recovers buff and Protect prayers; End turn: you may end your turn early. The tablet prints as
  "Varrock Teleport" (`export_cards.PRINTED`; the sim keeps "Varrock Teleport Tablet"; parity19 maps it back).
  All already matched the engine. Game v12.

## Round 27: starters rebuilt

`starter_lists.py` holds the new starters (Melee, Ranged, Air Mage) and their suggested capes; `cards.STARTER_DECKS`
reads it, and the old lists stay as `cards.STARTER_DECKS_R26` (experiments.py builds from those, so the field is
unchanged: same fingerprint). Each starter carries a little of everything its style needs. `python starters27.py 400`.

| | Melee | Ranged | Air Mage |
|---|---|---|---|
| Old, head to head (no capes / capes) | 61.4 / 30.6 | 47.1 / 70.4 | 41.5 / 49.0 |
| New, head to head (no capes / capes) | 48.8 / 48.7 | 51.7 / 52.8 | 49.5 / 48.5 |
| New, smart bot 80/pairing (no capes / capes) | 48.1 / 44.4 | 46.9 / 51.2 | 55.0 / 54.4 |
| New vs 20-deck field | 39.6 | 41.5 | 44.1 |

- The Ranged Cape (3 hits) won the old Ranged starter 70% of cape games; Ranged suggests the Defence Cape now
  (spread 3.3 vs 23.7). Worth a look later: Ranged Cape at 3 may be easy everywhere, not just in starters.
- Mage lost to Melee until it ran Iban's Staff 2 / Staff of Air 1 and Mirror Shield over Parry.
- Parity (web/test/parity27.py, 87 matchups x 200): mean |z| 0.73; the one z 3.2 outlier was 0.5 at 2000 games.
- Quest text: Sheep Shearer step reads "Have 2 materials in play"; the Respawn line was removed from the docs.
