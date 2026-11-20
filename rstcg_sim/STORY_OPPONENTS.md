# Story Mode: opponents and decks (2026-11-18)

Goes with `STORY_BRIEF.md`. Ramon's rules for story opponents, the decks as built, and how they were tuned.

## Ramon's rules (2026-11-18)

- **Never "Bot" in a story duel.** Everything that names the other side uses the opponent's name ("HIGHWAYMAN'S TURN", their cape progress, the log, the results). `them()` in `arena.src.html` gives the words.
- **Each enemy has its own deck**, limited to what it would carry: the Highwayman and the goblins nothing above Bronze (tier 1), the Skeleton and the Dark Wizard up to tier 2 (Mithril, Blasts), the bosses the hardest, with their locked Location and boss-only cards.
- **Clear escalation** from the first duels to the bosses; decks may mix styles where it fits.

## Where things live

- The decks: `STORY_DECKS` in `cards.py` (exported to `web/cards.json` as group "Story decks", never offered in the deck picker). Their capes: `STORY_CAPES`. The bosses' locked Locations: `STORY_LOCATIONS`.
- Who plays which deck at which bot level, and their lines: `OPP` in `web/story.js`.
- `node web/test/story_tune.js 60 [opponent]`: the three starter decks, played by Guard (standing in for a new player), against each opponent at its level, deck and locked Location, both seats.

## The ladder as built (story_tune.js, 2026-11-18)

How often a starter deck (Guard) wins. The targets were Ramon's escalation: about 70–75 / 60–65 / 50–55 / 30–35.

| Fight | Opponent | Bot level | Starter wins | Melee / Ranged / Air Mage |
|---|---|---|---|---|
| Duel 1 | Bandit (Bronze melee) | Guard | 68% | 63 / 70 / 72 |
| Duel 2, road A | Highwayman (Shortbow, Bronze darts) | Goblin | 68% | 67 / 77 / 59 |
| Duel 2, road B | Goblin Band (Bronze melee + thrown) | Guard | 62% | 55 / 50 / 81 |
| Duel 3, road A | Skeleton (Mithril melee) | Guard | 48% | 39 / 53 / 53 |
| Duel 3, road B | Dark Wizard (Blasts, Fire and Air) | Champion | 47% | 37 / 47 / 57 |
| Boss, road A | Delrith (Demon Fire, Hellfire; Draynor Manor) | Champion | 34% | 13 / 31 / 59 |
| Boss, road B | Count Draynor (Vampyric Claws, Bite; Moonlit Circle) | Champion | 29% | 29 / 39 / 20 |

(±2–3 points; 40–80 games per starter per seat.)

- **The locked Locations cut both ways.** Draynor Manor (Magic +2, Melee −2) makes Delrith brutal for a Melee deck (13%) and fair for the Air Mage (59%). The Moonlit Circle does the reverse to mages. The averages are on target; how a fight feels depends a lot on your deck. That fits "go away, rebuild, come back", but it's Ramon's call.
- The decks as built are in `cards.py`. The Bandit carries filler quests on purpose (he's the easiest fight).

## The reward cards (story_rewards.py, 2026-11-18)

- **Claws of Guthix** (14 damage, ignores shields, needs a Blood Rune + 3 elemental runes): two in the Air Mage starter for two Air Blasts, against the starter and tournament field, 400 games a matchup: **−3.6 points** at 14 (−4.7 at 12, −6.4 at 10). It costs a Blood Rune the deck doesn't otherwise want, so it isn't too strong for a starter. A deck built for Blood Runes would get more out of it; worth testing when decks are rebuilt from the full pool.
- **Shield of Arrav** (soak 6, free, Shatter can't break it) for the Wooden Shields: Melee **+1.5**, Ranged **+1.4**, Air Mage ±0. Trophy-sized.

## Chapter 2 (2026-11-20)

Decks in `STORY_DECKS` (cards.py), levels and lines in `OPP` (story.js). Same targets as chapter 1: about 70–75 / 60–65 / 50–55 / 30–35. Tuned with `node web/test/story_tune.js 40 <opponent>`.

| Fight | Opponent | Bot level | Starter wins | Melee / Ranged / Air Mage |
|---|---|---|---|---|
| Duel 1 | Zamorak Cultist (Bronze melee, Strikes and Fire Bolts) | Champion | 77% | 56 / 83 / 94 |
| Duel 2, road A | Zombie Knight (Mithril melee, armour, Kiteshields; Defence Cape) | Goblin | 62% | 62 / 49 / 75 |
| Duel 3, road A | Crawling Corpse (Mithril maces, Fire Bolts and Blasts) | Champion | 53% | 47 / 62 / 52 |
| Boss, road A | Dragith Nurn (Necrotic Bolt, Soul Siphon, Raise Dead; Falador Tunnels) | Champion | 37% | 31 / 14 / 65 |
| Duel 2, road B | Kinshra Scout (Willow Shortbow, Mithril Sword) | Guard | 65% | 70 / 65 / 60 |
| Duel 3, road B | Shadow (knives, darts, Rapid, scimitar) | Guard | 50% | 39 / 65 / 45 |
| Boss, road B | Melzar the Mad (Cabbage Mace, Cabbage Seeds, Cabbages!; Hidden Prison) | Champion | 35% | 28 / 26 / 53 |

(±2–4 points; 40–60 games per starter per seat.)

- The Cultist needed Champion to come down from 88% at Guard: a gentle deck played well. The Zombie Knight is the reverse (49% at Guard, 62% at Goblin): a strong deck played loosely, which suits a zombie.
- **Falador Tunnels (Magic +2, Ranged −2) is hard on Ranged** (14%), as Draynor Manor is on Melee; the Air Mage does well (65%). Ramon accepted chapter 1's Locations cutting this way.
- **Melzar**: Cabbage Mace hit 8, Cabbages! 9 (from 7 and 8: he was a little easy at 39%).

### The rewards (story_rewards.py ch2, 2026-11-20)

Swapped into a starter for the armour it would replace, against the starter and tournament field, 150 games a matchup:

| Version | Change |
|---|---|
| White Knight Armor, 3 bars (first draft) | −1.7 |
| **White Knight Armor, 2 bars** (built) | −0.3 (−0.8 on a second run) |
| Cabbage Armor, 3 logs, −2/−2 (first draft) | −2.5 |
| Cabbage Armor, 2 logs | +0.2 |
| Cabbage Armor, 1 log | +0.9 |
| **Cabbage Armor, 3 logs, −3 Melee / −3 Ranged** (built, Ramon's pick) | +1.8 (+1.4 on a second run) |

Trophy-sized, like the Shield of Arrav (+1.5).
