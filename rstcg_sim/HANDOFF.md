# RuneScape TCG: handoff (2026-11-16)

This file describes where things stand. `CLAUDE.md` covers how the code fits together and the ground rules. `README.md` covers the balance history (Rounds 1–27).

## Links

- **The Arena (latest build, claude.ai artifact):** https://claude.ai/artifact/DTVmjFMHpVvqVYmtS29tRm (version 25, built from `web/dist/arena.html`). Online play needs the Netlify app; the artifact can't reach the server.
- **The installable app (Netlify):** https://runescapetcg.netlify.app, deployed from `web/dist/netlify/` (see "Phone version" below).
- **The online server (Supabase):** project "RuneScape TCG" (`udmaqoxyhiwpvxkrugwr`, https://udmaqoxyhiwpvxkrugwr.supabase.co), Edge Function `duel`. See `ONLINE_BRIEF.md`.
- **Rules doc:** https://claude.ai/artifact/AA5kzzcL6QWhBjmwqwhntj
- **Player rulebook:** https://claude.ai/artifact/87VaH6ai4i5UXEdbRrHkfC
- **Card templates / design canvas:** https://claude.ai/artifact/NdJNNtUEMTV8FUHc7zTPUg. The card back is the "Crest", artboard `BackCrest.dc.html`.
- **Card art:** Ramon's Google Drive folder (the link is kept out of this public repo; ask Ramon).
  - Files are named by item; look in a `Detail` subfolder when there is one.
  - `art/art_map.json` maps each card to its Drive file ID; downloaded images are in `art/img/`.
  - `rstcg_missing_art.csv` (one folder up, on Ramon's PC) lists the 64 cards still without art.

## The Arena: what's done

- **Main menu** with an idle attract mode, GP, and How to Play.
- **Settings:** motion, music, sfx, tips, "show tutorial again".
- **Pick screen:** your deck, your cape (blind), the opponent's deck.
- **Duel on a tilted 3D table.**
  - Drag or click to play cards, with animated moves, attacks, milling, heals, quests and capes.
  - Cape progress has animated ticks with sound, plus a "READY TO CLAIM" stamp.
  - The bot's hand is shown as card backs at the top.
  - Both nameplates show the same hooded stranger (gold trim for you, blue for the other side) and a name (the bot's level name), never the deck's style icon or deck name; the intro reads "You vs Champion". A deck gives itself away in play, not before (Ramon, 2026-11-15, for online).
  - The log drawer is at the top right.
  - Phone-width stages get a shorter, wider table automatically; 16:10 screens get a taller one.
- **Results:** GP payout (100 win / 50 loss / 0 concede). Leaving a duel saves it; concede is a separate choice.
- **Play Online: Challenge a friend** (2026-11-16): a name, a code or link, the duel against another person, rematch. See "Online play" below and `ONLINE_BRIEF.md`.
- **Collection:** you start with the three starter decks' cards; basic materials are unlimited.
- **My Decks and the deck editor:** filters, search, paging, drag to add, live legality.
- **Deck Wizard:** pick up to 2 cards you own and it builds a whole 60-card deck around them.
  - It adapts the Starter/Tournament lists, then tests up to 4 builds with greedy-vs-greedy games and keeps the best.
  - The result shows its win rate. You can save it, tweak it in the editor, or pick again.
- **Packs:** 200 GP for 6 cards (3 Common, 2 Uncommon, 1 Rare that sometimes upgrades to a Legendary), with a pack-opening animation.
- **Guided tutorial:** a scripted 19-step first duel with stacked decks and a scripted bot.
  - It is offered on first launch and from the menu, and finishing it grants a free pack.
  - After it, one-time tips show up in real duels.
- **Audio:** synthesised music with an intense layer (latches on once either deck is ≤10), plus sfx.
- **Xbox controller:** gold focus ring; A/B/X/Y, LB/RB, Menu/View; the right stick scrolls.
- **Phones and any screen shape:** touch controls, a rotate-your-phone screen, layouts that fill 4:3 to 21:9, and an installable offline app. See "Phone version and adaptive layout" below.

## AI opponents (2026-11-10)

Three levels in `web/bot_levels.js`. `makeBot(level, seed)` builds one, and Normal is the default.

| Level | Name | What it is | Wins against greedy, starter decks | Time per decision (node) |
| --- | --- | --- | --- | --- |
| easy | Goblin | Greedy with a new player's habits. It overlooks some of its options, is slow to claim capes and forgets reactions. It never misses a kill or a cape about to win. | 29.4% of 5,400 games | 0.02 ms |
| normal | Guard | `HeuristicBot`, unchanged. | 50.1% | 0.02 ms |
| hard | Champion | Looks ahead by playing its best few moves out on copies of the game. | 66.9% of 2,880 games (±1.7) | 30 ms on average, 107 ms at worst |

- **Across all 12 decks the bot can be given:** Champion 64.8% (±2.3), Goblin 31.1%. Champion beats Goblin 81% of the time.
- **No peeking.** No bot reads the other player's hand or the order of either deck.
  - Champion's copies pool the opponent's hand and deck and deal them back at random, and shuffle its own deck.
  - The check: every decision is asked a second time with the hidden cards dealt differently, and the answer must be the same. Champion: 2,499 decisions, none changed. Goblin: 34,935, none changed.
  - The bots do know both deck lists, as greedy always has.
- **Same seed, same game.** Every random number comes from the engine's seeded `RNG`. Champion's search is bounded by how many turns it simulates, never by the clock, and it keeps nothing between decisions. Every measured run was replayed in a second process and matched.
- **How Champion thinks.**
  - It tries its six best moves on the same deals of the hidden cards and plays each to the end. A move that falls clearly behind is dropped. Its first choice keeps its place unless another move is clearly ahead.
  - In those playouts the opponent is greedy. Champion's own seat is played by `SharpBot`: greedy that knows the game is a race. Steps towards a cape are worth more to it, it won't claim second, and it sees a kill that takes the whole turn. That alone is worth 3 points; the search on top of it is worth the rest.
  - The playouts run on `engine.js` with its `async`/`await` words taken out. Same rules, word for word, about a fifth faster. Nothing is kept by hand: node makes the copy when it loads, and the build makes one for the page.
- **On the phone.** Settings has **Champion's thinking time → Test**: it plays a hidden practice duel (Champion against Guard, fixed seeds, the same 12-playout breathing room as a real duel) and times 40 of Champion's moves on that device. `node web/test/bot_speed.js [slowdown]` runs it in Chrome. Results (2026-11-15): **Ramon's Pixel 7 Pro: 0.17 s a move on average, 0.55 s at worst (40 moves)**, right between the 4× and 6× slowed-Chrome estimates (0.15 s / 0.36 s and 0.23 s / 0.58 s); 0.05 s / 0.13 s in the cloud container. In the Arena it thinks during the bot's "thinking" pause, so at normal speed there is no extra wait.
- **Saves.** A hard bot's answers are saved with the duel, as an extra `bot` list in `rstcg-arena-duel-v1`, so resuming doesn't think them all through again. Saves without the list still load: the bot thinks again and lands on the same position. A duel saved before there were levels is a normal one.
- **If cards or rules change:** there is nothing to retrain. Run the ladder again. The settings are the `EASY`, `SHARP` and `HARD` tables in `bot_levels.js`.
- **Tried and dropped:** deciding reactions and discards by playout (no gain), and stopping playouts early to score the position with a learned estimate (worse than playing them out, for the same time).
- **Engine speed-up (2026-11-15, Ramon approved it on condition the parity tests didn't move):** `count`, `main_elements`, `meets` and `family_count` in `engine.js` are now plain loops instead of building arrays on every call. Same logic as `engine.py`, which is unchanged.
  - Checked: 3,000 greedy games across all 12 decks play move for move the same on the old and new engine (103,219 decisions). 40,000 random boards count the same, 2,381 of them with Staff of Light-style weapons. The JS side of `parity27.py` gives byte-identical results, and the Python comparison is normal noise (87 matchups, mean |z| 0.69).
  - Gain: greedy games 31% faster. Champion 46 → 36 ms a decision on the same seeds, with identical games and results (67.1% of 432).
  - Champion's search budget was left as it was, so it is the same strength, only quicker. Spending the time on more playouts instead would make it stronger; that would be a balance call for Ramon.

```
node web/test/bot_ladder.js 160 hard greedy             # any two bots: starter pairings, both seats, same seeds, 4 cores
node web/test/bot_ladder.js 6 hard greedy all           # every starter and tournament deck
node web/test/bot_ladder.js 8 hard greedy --peek-test   # also ask every decision twice with the hidden cards dealt differently
node web/test/bot_selftest.js                           # the pieces the bots stand on; must end "all passed"
node web/test/arena_levels.js                           # the levels in the built page, in a real browser; must end "all passed"
```

## What's next, in Ramon's order

### 1. AI opponents: done, and in the Arena (2026-11-15)
- The three levels are above. The picker (Goblin / Guard / Champion tiles) is on the pre-duel screen, Guard by default, and the result screen names the level.
- The Arena source and its tests are now in this repo (`web/arena.src.html`, `build_arena.py`, `web/test/ui/`). The generated `arena_assets.js` / `arena_fonts.css` stay out: `python web/recover_generated.py <saved Arena page>` rebuilds them, or `arena_assets.py` with the art folder.
- Champion on Ramon's Pixel: 0.17 s a move on average, 0.55 s at worst (measured 2026-11-15).

### 2. Phone version and adaptive layout: done (2026-11-15)
Ramon decided: landscape only, the same file as the PC version, an installable app on Netlify, and (added 2026-11-15) layouts that adapt to any screen shape, with bigger type on small screens ("stretchy stage + size tiers").

- **The stage follows the screen.** `stageFor(key)` sizes it; `SW()` and `SH()` give its width and height. Never use 1920 or 1080 for anything placed against the right or bottom edge.
  - Menus: the height is a tier from the window's height (`menuHeight`): **760** under 500 CSS px tall (phones), **900** under 820 (laptops, 4:3 tablets), otherwise **1080**. The width is that height times the window's shape, clamped to 1200–2560, so 4:3 to 21:9 fill the screen with no bars. A 1920×1080 window gets exactly the old 1920×1080 stage, so the PC layout is unchanged.
  - `#stage` gets `.short` (height under 1000) and `.phone` (under 800). `html.touch` is set from `(pointer: coarse)` and makes tap targets bigger (`.touch …` rules in the CSS).
  - The duel stays 1920 wide; its height follows the shape between 864 (phones) and 1200 (16:10).
- **How each screen adapts.**
  - Menu: on a short stage, a smaller crest and the buttons in two columns.
  - Pick: deck and cape sections wrap and scroll on short screens; the matchup bar goes to two rows under 1500 wide, drops "VS" and shortens labels under 1820; it stays 1800 wide, centred, on an ultrawide.
  - My Decks: tile grid fills the width; detail panel narrower under 1500.
  - Deck editor and wizard: `cardGridLayout()` works out the columns, rows and card size (cards shrink on short stages so 2–3 rows fit). On short stages the filters fold into one row with a **Filters** button (`edfilt` / `wizfilt`) that opens them in a panel.
  - Packs, pack opening, pack summary, results: pictures composed on 1920×1080, so `boxed()` centres that canvas and shrinks it to fit (never enlarges); the background still fills the screen. `designBox()` gives its position for effects such as the sparks.
- **Touch.** Hold a card (anything with `data-zoom`) for 0.42 s to see it full size in the middle of the screen; letting go closes it and doesn't count as a tap. Moving your finger first means a drag or scroll instead. Taps no longer trigger the mouse hover zoom, and a long press never opens the browser's own menu or removes a card in the editor. Finger drag-to-play works on the table; the deck editor's − and + buttons are bigger on touch screens. Words say "tap" instead of "click" on touch screens (`tapWord()`).
- **Rotate screen.** Holding a phone upright shows "Turn your phone sideways" (`#rotate`, shown by `html.portrait`).
- **Installable app.** `python web/build_netlify.py` (after `build_arena.py`) makes `web/dist/netlify/`: `index.html` (the built Arena plus the manifest link and service-worker registration), `manifest.webmanifest` (landscape, full screen), `sw.js` (network first for the page, so a new deploy shows up straight away, with the saved copy used offline), `_headers` and the icons from `web/app/` (made by `node web/make_icons.js` from the Crest). `web/dist/arena.html` itself is untouched and stays the artifact.
  - Deploy: drag `web/dist/netlify` onto the Deploys page of the `runescapetcg` site, or `netlify deploy --prod --dir web/dist/netlify`.
  - On the phone: open https://runescapetcg.netlify.app in Chrome → menu → **Add to Home screen / Install**. It opens full screen in landscape and works offline.
- **Tests:** `touch.js` (fingers), `layouts.js` (every screen at 7 window shapes: no button off screen or covered), `pwa.js` (manifest, install, offline), all in `run_all.js`. `tour.js W H` takes screenshots of every screen at one size.
- **Fixed after Ramon's first phone test (2026-11-15):** the board blinked on the phone whenever a card was played (the duel is now redrawn in place, `morphInto`; the test browser never blinked, so this is the likely cause rather than a proven one: confirm on the Pixel); the bot's face-down hand arced the same way as yours (it now arcs the other way, as if held across the table); playing Staff of Light drew a dashed card-sized outline at the bottom left (the "+2 any element" chip was picking up the dragged card's `.ghost` style).
- **Music on the phone (2026-11-15):** Ramon heard it crackle, "like it was buffering". Two causes, both fixed in `arena_audio.js`:
  - Notes were scheduled only 0.3 s ahead from the page's own thread, so when the page was busy (a hard bot thinking, a big redraw; up to half a second on a phone), the notes it missed were dropped. Measured with 600 ms stalls: 89 notes dropped in 12 s before, none after. They are now scheduled 1 s ahead (`LOOKAHEAD`).
  - Phones (touch screens) now ask for a bigger output buffer (`latencyHint: 'playback'`), so the audio thread has more slack. The music is all synthesized live and costs about half a desktop core for the busiest battle song (measured offline), so a phone's audio thread can miss deadlines with the smallest buffer. Desktops keep the small buffer.
  - **Settings → Sound check** shows glitches (the audio thread missing its deadline, from Chrome's `AudioContext.playbackStats`), notes dropped, and the buffer size since the app opened. `audioflow.js` now fails if a busy page drops or delays notes.
  - If it still crackles, the next step is to render each song once in the background (`OfflineAudioContext`) and loop the recording, which costs almost nothing to play.
- **Not done / ideas:** the duel itself is unchanged apart from touch (its HUD is fine at phone size but could use bigger buttons); the show-off screens (packs, results) are only shrunk to fit, not re-laid out for phones.

### 3. Online play: Challenge a friend works (2026-11-16)
Phases 0 to 2 of `ONLINE_BRIEF.md` are built: the server referee (phase 0, 2026-11-15), the duel screen online (phase 1) and Challenge a friend (phase 2). The brief has the details, Ramon's decisions and how it's tested and deployed.

- **To try it:** deploy `web/dist/netlify` to the `runescapetcg` site, open it on two devices (or two browsers), **Play Online** on both, pick names, **Challenge a friend** on one and send the link or code to the other.
- **What's in it:** names (3 to 12 letters or numbers, unique); codes and links (`runescapetcg.netlify.app/#duel-K7MQX`) with copy and share; a waiting room; the duel as against the bot, with the other player's name, their hand as card backs, a clock for the last 15 seconds of each decision, and "Reconnecting…" when the network drops; leaving and coming back (Resume on the menu); concede; results that name the opponent and pay as for bots (nothing for conceding or losing on time); rematch, which both players press.
- **Server:** Supabase project "RuneScape TCG" (`udmaqoxyhiwpvxkrugwr`): tables `profiles`, `matches`, `match_secrets`, `moves`; Edge Function `duel` (version 3), deployed by the GitHub Action on every push that touches the rules or `server/`. Keep-alive: `RamonG3/rstcg-keepalive`.
- **Code:** `web/referee.js` (the referee), `web/deckrules.js` (deck rules, shared), `web/online.js` (sign-in, names, the function, Realtime; no library), the Online section of `web/arena.src.html` (`runOnlineDuel`, `odStep`, the hub `onlineHTML`, rematches), `server/` (the function, migrations, build, tests, the stand-in `fake_supabase.mjs`, `smoke.sql`).
- **Next:** Ramon's test with friends. Then accounts (phase 3: progress that follows you between devices), then Find a match (phase 4). Open questions are at the end of the brief.

### 4. Later
- **Card art** for the 64 cards in `rstcg_missing_art.csv`. Rune Arrows art exists on Drive but isn't downloaded yet.
- **Rebuild decks from the full tested pool,** to see what independent deckbuilding does to balance.
- **Print-and-play:** `print/build_pnp.py` + `print/render.js` → PDFs. Done for the 3 starters.

## Simulator state (Rounds 1–27)

The cards and rules are settled through Round 27 (rebuilt starters). See `README.md` and the rules doc.

Watch list, from Ramon:
- Ranged is the weakest style on average.
- Earth/Fire Blast and Wave are weak.
- The elemental staves are starter-grade.
- Anti-dragon Shield is worse than Kiteshield.

Ramon accepts that some deck will always be weakest. He prefers fixing weak cards to cutting them, but will cut for a better game.

Common simulator commands:
- `python group2.py 40` (greedy) or `python group2.py 5 --smart` (slow).
- `python bestof.py 40`.
- `python starters27.py` for the current starters.
