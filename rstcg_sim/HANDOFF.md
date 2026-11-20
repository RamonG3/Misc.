# RuneScape TCG: handoff (2026-11-20)

This file describes where things stand. `CLAUDE.md` covers how the code fits together and the ground rules. `README.md` covers the balance history (Rounds 1–27).

## Links

- **The Arena (latest build, claude.ai artifact):** https://claude.ai/artifact/DTVmjFMHpVvqVYmtS29tRm (version 26, built from `web/dist/arena.html`). Online play and accounts need the Netlify app; the artifact can't reach the server.
- **The installable app (Netlify):** https://runescapetcg.netlify.app, deployed from `web/dist/netlify/` (see "Phone version" below).
- **The online server (Supabase):** project "RuneScape TCG" (`udmaqoxyhiwpvxkrugwr`, https://udmaqoxyhiwpvxkrugwr.supabase.co), Edge Function `duel`. See `ONLINE_BRIEF.md`.
- **Rules doc:** https://claude.ai/artifact/AA5kzzcL6QWhBjmwqwhntj
- **Player rulebook:** https://claude.ai/artifact/87VaH6ai4i5UXEdbRrHkfC
- **Card templates / design canvas:** https://claude.ai/artifact/NdJNNtUEMTV8FUHc7zTPUg. The card back is the "Crest", artboard `BackCrest.dc.html`.
- **Card art:** Ramon's Google Drive folder (the link is kept out of this public repo; ask Ramon).
  - Files are named by item; look in a `Detail` subfolder when there is one.
  - `art/art_map.json` maps each card to its Drive file ID; downloaded images are in `art/img/`.
  - Every card has art since 2026-11-17; `rstcg_missing_art.csv` (one folder up, on Ramon's PC) is out of date. Pictures that aren't on Drive have `id: null` and their source in `path`.
- **Spell sounds:** Ramon's Drive folder RuneScape Kingdoms > Images > Spells > Spell Sounds (53 `.ogg` files, named by spell), plus the seven `Crafting_<rune>_runes.ogg` he gave in chat on 2026-11-17. On his PC they go in `art/sounds/`; see "Spell sounds" below.
- **Jagex's Fan Content Policy:** https://legal.jagex.com/docs/policies/fan-content-policy (version 1.4, 29 September 2026, when the notice was added). See "The fan-content notice" below.

## Start here (for a new chat, after 2026-11-20)

- **Chapter 2 (Falador) is built (2026-11-20, second chat).** Ramon's plan: `STORY_CH2_BRIEF.md`; the script he approved: `STORY_CH2_SCRIPT.md` (Project docs); decks and tuning: `STORY_OPPONENTS.md` ("Chapter 2"). See "Chapter 2" under "7. Story Mode" below for what was built and what's still to come (mostly art).
- **Also done that day:** story duels show the opponent's chathead on their nameplate (`oppFace()`, when the face exists), and every duel's cape rows are ordered like the table: the other side's row on top, yours below (`capeHTML`).
- **Where chapter 1 leaves the player:** road A ends with the Wise Old Man heading to Falador ("an associate there who knows more about this sort of thing"); road B with Reldo sending you there (old research about a ruby sword). Delrith rose from Draynor's coffin; Count Draynor rose at the stone circle; the silent stranger with the ruby sword is behind it. The player's tone at the stranger (`run.tone.stranger1/2`) and road (`profile.story.roads`) are saved and can be used later.
- **What a chapter now has to offer (all from 2026-11-20):** places with Ramon's paintings (`art/scenes`, `AS.scene`, a scene step's `art`), a line's `bg` to change the painting mid-scene, a red `flash` with a cut under it, a slow change of place with the place's name, Ramon's recorded music per place (`PLACE_MUSIC`, `arena_music.py`; a scene step's `music`), boss rematches (`chapter.bosses`), boss-only cards and a locked Location.
- **Things to deploy or check first:**
  - The newest Netlify build given to Ramon is `runescapetcg-story-ch2-v5.zip` (2026-11-20, chapter 2 with the new chatheads; built in the cloud with the spell and rune sounds lifted out of his v10 deploy, since the cloud has no `art/sounds`). If the live app is older, he drags the new one in. A cloud build without `web/arena_sounds.js` from a recent deploy loses the recorded sounds: get them from the newest deploy he has (`window.ARENA_SOUNDS = …` in its `index.html`).
  - **Ramon's PC wasn't reachable on 2026-11-20.** Everything from that day went to him as `rstcg_pc_update_2026-11-20c.zip` (code, the generated `arena_assets.js` and `arena_music.js`, and the new art), to unzip over `rstcg_project`, plus his four MP3s to put in `art/music/` himself. Before regenerating anything on the PC, check it has them (`art/scenes/scene_swamp_stranger_gone.png`, `art/faces/Stranger.png`, `art/music/Bazaar.mp3`, `web/arena_music.js`). If it doesn't, the repo and the zip are the truth; `python web/recover_generated.py` can pull the generated files out of the latest built page.
- **Page size:** about 13 MB since the music (Bazaar, Forest Day, Swamp Land, Ambient Room, ~3½–4 min loops at 56 kbit/s). More tracks for chapter 2 add ~2 MB each; say so before adding many, or trim them shorter in `arena_music.py`.
- **Chapter 1 is settled (Ramon, 2026-11-20):** Claws of Guthix 14, Shield of Arrav soak 6, and the boss Locations stay as they are. **Still open:** tracks for the places still on the synthesized songs (Draynor Village, Draynor Manor, the cellar, the stone circle, the Story screen and map).

- **Everything below is built and pushed** to `RamonG3/Misc.` branch `claude/card-game-ai-opponents-e90esn` (`rstcg_sim/`), **and copied to Ramon's PC** (`C:\Users\ramon\Downloads\rstcg_project\rstcg_sim`). Live: the Netlify app as of the bank (the art and rune sounds below are in `web/dist/netlify` and the zip given to Ramon on 2026-11-17, deployed when he drags it in); Supabase `duel` function redeployed 2026-11-18 for Story Mode (see below).
- **Online:** sign-in is by emailed 6-digit code for anyone (sender runescapetcg.game@gmail.com; Google switched off); the server is the bank for everyone who has been online (`ONLINE_BRIEF.md`, phase 3b). Ramon's account is **Zukrathion**.
- **Every card in Set 1 has art (2026-11-17).** See "Card art for every card" below. Runes now make their runecrafting sound when played (see "Spell sounds").
- **Open items:**
  1. **The fan-content notice:** Ramon was told the Jagex policy (clauses 6.1.3 and 2.4) doesn't cover video games or the RuneScape trade marks. He hasn't said whether to keep the policy's wording or swap to a plain "fan-made, not endorsed by Jagex" line.
  2. **A CAPTCHA on sign-ups** (Supabase recommends Cloudflare Turnstile) before the link is shared widely. Ramon: "later".
  3. **Find a match** (phase 4).
  4. **The mobile flicker** is still there; Ramon wants to come back to it later, perhaps with a packaged Android app (APK).
  5. Smaller: sounds for the six Flurry spells (the Surge sounds are spare).
  6. **Story Mode:** see "Start here" above.
- **Working with the PC:** the art and spell sounds live only in its `art/` folder. Its device shell has python3, PIL and ffmpeg, so `python3 web/arena_assets.py` and `python3 web/arena_sounds.py` run there. On 2026-11-17 Ramon connected the folder one level up (`rstcg_project`), so `rstcg_sim/art/` and the old missing-art CSV are both reachable. The shell can't delete files without asking Ramon. Copy files to the PC only after the command that writes them has finished.

## The Arena: what's done

- **Main menu** with an idle attract mode that tours the Location paintings (a new place every 20 seconds), GP, the account button, How to Play, and the fan-content notice at its foot.
- **Accounts** (2026-11-16): sign in with Google or an emailed code, and progress follows you between devices. See "Accounts" below and `ONLINE_BRIEF.md`.
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
- **Audio:** synthesised music with an intense layer (latches on once either deck is ≤10), plus sfx. A spell that has a recording makes its real casting sound (see "Spell sounds").
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
- **Tests:** `touch.js` (fingers), `layouts.js` (every screen at 7 window shapes: no button off screen or covered), `pwa.js` (manifest, install, offline), all in `run_all.js` (18 tests, about 22 minutes; all passed on 2026-11-16 on the build that was published as version 26). `tour.js W H` takes screenshots of every screen at one size.
- **Fixed after Ramon's first phone test (2026-11-15):** the board blinked on the phone whenever a card was played (the duel is now redrawn in place, `morphInto`; the test browser never blinked, so this is the likely cause rather than a proven one: confirm on the Pixel); the bot's face-down hand arced the same way as yours (it now arcs the other way, as if held across the table); playing Staff of Light drew a dashed card-sized outline at the bottom left (the "+2 any element" chip was picking up the dragged card's `.ghost` style).
- **Music on the phone (2026-11-15):** Ramon heard it crackle, "like it was buffering". Two causes, both fixed in `arena_audio.js`:
  - Notes were scheduled only 0.3 s ahead from the page's own thread, so when the page was busy (a hard bot thinking, a big redraw; up to half a second on a phone), the notes it missed were dropped. Measured with 600 ms stalls: 89 notes dropped in 12 s before, none after. They are now scheduled 1 s ahead (`LOOKAHEAD`).
  - Phones (touch screens) now ask for a bigger output buffer (`latencyHint: 'playback'`), so the audio thread has more slack. The music is all synthesized live and costs about half a desktop core for the busiest battle song (measured offline), so a phone's audio thread can miss deadlines with the smallest buffer. Desktops keep the small buffer.
  - **Settings → Sound check** shows glitches (the audio thread missing its deadline, from Chrome's `AudioContext.playbackStats`), notes dropped, and the buffer size since the app opened. `audioflow.js` now fails if a busy page drops or delays notes.
  - **Still cutting out (Ramon's screen recording, 2026-11-17):** the sound went to dead silence 28 times in 43 s of a Guard duel: holes of about 12 ms in the music, and three of 1 to 4 seconds, during quiet bot turns. The recording's own audio track was continuous, so these are the browser's audio output missing its deadlines (the audio thread can't render fast enough), not notes being dropped.
  - **Cause found and fixed (2026-11-17):** the battle songs' intense layer was synthesized all duel long at 1/10,000 volume, waiting to be faded in. Rendering 8 s offline in Chrome here: Clash of Steel 35% of a core → 12% without it, Wilderness Ambush 43% → 22%, Arcane Duel 36% → 15% (the reverb is about another 5%). Now its notes aren't played while it is off (`intenseUntil`); when it comes on, the notes the scheduler already passed are played at once (`catchUp`), and it keeps playing through its 4-second fade-out. Checked against a fake AudioContext: switched on mid-song (or on, off and on again) it plays exactly the notes it would have played had it been on all along.
  - **Not fixed yet:** once the intense layer is on (either deck at 10 or less) the cost is back to the full amount (Ambush 56% of a core here). If late duels still cut out on the Pixel, render each song once in the background (`OfflineAudioContext`) and loop the recording, which costs almost nothing to play; or lighten the intense layer (the choir and the 16th-note hats are the busiest parts).
- **Not done / ideas:** the duel itself is unchanged apart from touch (its HUD is fine at phone size but could use bigger buttons); the show-off screens (packs, results) are only shrunk to fit, not re-laid out for phones.

### 3. Online play: Challenge a friend works (2026-11-16)
Phases 0 to 2 of `ONLINE_BRIEF.md` are built: the server referee (phase 0, 2026-11-15), the duel screen online (phase 1) and Challenge a friend (phase 2). The brief has the details, Ramon's decisions and how it's tested and deployed.

- **To try it:** deploy `web/dist/netlify` to the `runescapetcg` site, open it on two devices (or two browsers), **Play Online** on both, pick names, **Challenge a friend** on one and send the link or code to the other.
- **What's in it:** names (3 to 12 letters or numbers, unique); codes and links (`runescapetcg.netlify.app/#duel-K7MQX`) with copy and share; a waiting room; the duel as against the bot, with the other player's name, their hand as card backs, a clock for the last 15 seconds of each decision, and "Reconnecting…" when the network drops; leaving and coming back (Resume on the menu); concede; results that name the opponent and pay as for bots (nothing for conceding or losing on time); rematch, which both players press.
- **Server:** Supabase project "RuneScape TCG" (`udmaqoxyhiwpvxkrugwr`): tables `profiles`, `matches`, `match_secrets`, `moves`; Edge Function `duel` (version 4 on 2026-11-16), deployed by the GitHub Action on every push that touches the rules or `server/`. Keep-alive: `RamonG3/rstcg-keepalive`.
- **Code:** `web/referee.js` (the referee), `web/deckrules.js` (deck rules, shared), `web/online.js` (sign-in, names, the function, Realtime; no library), the Online section of `web/arena.src.html` (`runOnlineDuel`, `odStep`, the hub `onlineHTML`, rematches), `server/` (the function, migrations, build, tests, the stand-in `fake_supabase.mjs`, `smoke.sql`).
- **Accounts (phase 3) came next:** see below.

### 4. Accounts: built (2026-11-16), Google live (2026-11-17)
Ramon chose: Google and emailed codes both; ask each time when a device with progress meets an account with progress; a cloud save now, and the server as the bank before Find a match. `ONLINE_BRIEF.md` ("Phase 3 (accounts): what's built") has how it works, the tests and the set-up steps.

- **In the game:** an account button on the main menu and an Account row in Settings; the account screen (sign in, the emailed code, which progress to keep, signed in, sign out); saving in the background, with a quiet retry when offline.
- **Code:** `web/cloudsave.js` (the sync and the merge), the account calls in `web/online.js`, the "Accounts and the cloud save" section of `web/arena.src.html`, `server/supabase/migrations/20261116155830…` and `…155904…` (table `progress`, its policies and `progress_save`), `server/progress_check.sql`, and `server/fake_supabase.mjs` (now signs players in, for the tests).
- **Save data:** `rstcg-profile-v1` gained `cloud` (which account this progress was last saved to, at which revision, and the save both sides agreed on); old profiles load as they are. `rstcg-online-v1`'s sign-in now says whether it is a guest or an account. New: `rstcg-backup-v1`, the progress that "which progress do you keep?" replaced.
- **Live server:** both migrations are applied and checked (17 checks; a real guest is refused). As of 2026-11-17 the return address and manual linking are set, **Google is on** and **Email is switched off**.
- **Tried for real (2026-11-17):** Google on the Pixel's installed app: there and back into the app, an account made, and the Pixel's progress saved to it (revision 1). That account is Ramon's own; don't delete it.
- **Not tried for real yet:** a second device signing in to the same account ("which progress"), and an emailed code.
- **Next:** the server as the bank (phase 3b), then Find a match (phase 4).

### 5. Small things done alongside (2026-11-16)
- **The menu's tour.** `TOUR` in `arena.src.html`: every 20 seconds (`TOUR.every`) the next place fades in over the last (painting, light, motes and the name in the corner), in one shuffled order per visit. The clock stops with a dialog open, on another screen or with the tab hidden, and starts a place's time again when you come back to the menu. It changes the page in place, never by `render()`, so a pressed button or the controller's focus isn't disturbed. Test: `web/test/ui/menutour.js`.
- **Art:** Air Battlestaff, and Lumbridge (its card, and a wider view as the backdrop behind the board and on the menu, with its own motes in `SCENE_FX`). Water, Fire and Earth Battlestaff pictures are in `art/img` for when those cards exist (Ramon: "I'll look at making the other cards later").
- **Art pipeline, two things learnt:**
  - Pictures copied to Ramon's PC through Claude come out about 6 KB bigger: the transfer adds a provenance tag (C2PA) to image files. The pixels are untouched, but the old "a file under 12 KB is a tiny icon" rule flipped for one icon. `is_pixel_icon` in `print/build_pnp.py` now goes by the picture's size in pixels, and `arena_assets.py` uses it.
  - The PC's `print/build_pnp.py` had lost the Enchanted Bar's art entry; it is back. After any art change, compare the new `arena_assets.js` with the old one entry by entry before trusting it (2026-11-16: only the three new entries differ).
- **The crest no longer jumps** while the menu slides in on phones and laptops (its `scale` sat on the element the slide animates; it is on an inner one now).

### Card art for every card (2026-11-17)
- The 55 cards without art got it. Rune Arrows came from Ramon's Drive folder; 47 from the wikis (Ramon allowed `oldschool.runescape.wiki`, `runescape.wiki` and `dragonwilds.runescape.wiki` for this session; Dragonwilds had nothing better); Dragon Slayer is the Shadow of Elvarg painting Ramon gave in chat (`dragon_slayer_elvarg.jpg`, its black frame trimmed); the six Flurry spells have no art in any RuneScape game, so Claude made each from its two elements' Wave icons plus its combination rune (`*_flurry.png`; Ramon can veto).
- Picks worth knowing: items are the OSRS detail pictures, except Staff of Light (RS3), Shieldbow (RS3's "Shieldbow", the old longbow) and Magical Longsword (RS3's Chaotic longsword: chaos rune in the recipe). Karambwan is the raw one (the cooked one is a white star that vanishes on the card). Abilities are RS3 ability icons: Crush = Assault, Slash = Sever, Stab = Bladed Dive, Longrange = Snipe, Rapid = Rapid Fire, Parry = Preparation, Ricochet = Ricochet. Quests and the two arenas are the wikis' quest/place pictures (`SCENE`), mostly RS3; Ernest the Chicken is OSRS. Brother Jered and the Wise Old Man are RS3 (`PORTRAIT`), cut from head to waist and centred on a square, see-through canvas (`*_bust.png`): the wiki's full-length figures showed only their middles on the small cards, which crop to the centre (Ramon, 2026-11-17).
- On the PC: the pictures are in `art/img` under the card's name in snake case (`ancient_mace.png`); `art/art_map.json` has each with `id: null` and `path` saying where it came from (`art/art_map_additions.json` is the batch as added); `print/build_pnp.py` gained the new quests and arenas in `SCENE` and the two NPCs in `PORTRAIT`. Kept for comparison (delete when happy): `web/arena_assets.before_art.js`, `web/arena_sounds.before_runes.js`, `art/art_map.before_art.json`, `print/build_pnp.before_art.py`.
- Checked: the new `arena_assets.js` against the old one entry by entry: no old entry changed, 55 new (29 item, 13 px, 11 scene, 2 portrait). Two wiki PNGs (Magic logs, Mystic robe top) were RGB with a "black is see-through" key that `load()` drops (they came out on black boxes), so they were saved again as RGBA. **Watch for that in any new wiki picture.**
- Only Quest Point Cape has no `art` entry, as before: capes use `AS.cape`.
- Committing to the PC from the same staged path twice can silently keep the first copy: stage a new file name (or check the md5 on the PC) when sending a file again.

### Spell sounds (2026-11-16)
Ramon: "if you successfully cast Air Bolt, it'll play the Air Bolt.ogg sound".

- **What plays:** a spell with a recording makes it as it is cast, for either player, and whether or not it lands (a Protect prayer can still turn it away: the cast worked, the landing didn't). It takes the place of the synthesized spell sound; the hit keeps its own. A spell discarded (resting in Varrock, trading at the Grand Exchange) is not a cast. Reduced motion, which plays no flights, still plays a cast.
- **Which:** a sound belongs to the card its file is named after. `ALIAS` in `web/arena_sounds.py` holds the two that differ: Superheat ("Superheat Item") and Varrock Teleport ("Standard Teleport", the sound every standard teleport makes; Claude's call, Ramon can veto). The six Flurry spells have no file and keep the synthesized sound. The folder's other files (Surge spells, curses, alchemy and so on) have no cards yet; a card added under one of those names picks its sound up by itself.
- **Runes (2026-11-17, Ramon):** Air, Water, Earth, Fire, Chaos, Death and Blood Rune make their runecrafting sound (`Crafting_<rune>_runes.ogg`, `ALIAS` in `arena_sounds.py`) as the card lands on the table, yours or the bot's, in place of the card's thud, at 0.8 of the spells' volume (`RUNE_V`); reduced motion still plays it. The six combination runes have none and keep the thud. A rune coming back from the discard (Brother Jered, the Wise Old Man) isn't played, and keeps the thud. Measured before packing: the same loudness range as the spell recordings (-30 to -43 dB mean). `spellsound.js` checks yours, the bot's, a combination rune and reduced motion. 25 sounds packed (18 spells, 7 runes), about 500 KB.
- **Pipeline:** `python web/arena_sounds.py` reads `art/sounds/*.ogg`, converts each sound that has a card to a small mono MP3 with ffmpeg (Safari on older Macs and iPhones doesn't play Ogg), keeps those in `art/sounds/game/`, and writes `web/arena_sounds.js` (`window.ARENA_SOUNDS`, 18 sounds, about 340 KB), which `build_arena.py` inlines like the art. The sounds and the generated file stay out of the repo (`.gitignore` has `art/` and `web/arena_sounds.js`); `recover_generated.py` gets the pack back out of a built page.
  - **Run it on Ramon's PC** (Claude's shell there has ffmpeg 4.4; plain Windows may not), then bring `web/arena_sounds.js` back, as with the art. The copies made there are the ones that ship: another ffmpeg makes the same sound as slightly different bytes.
  - The conversion leaves the level as recorded (a plain average of left and right, which are the same in these files), takes off the silence ahead of the sound (up to a fifth of a second), and writes no tags.
  - **One volume for them all:** `CLIP_GAIN` in `arena_audio.js`, set by measuring (`RSAudio.measure`): the 18 average -28 dB through the mixer, beside -30 for a hit and -36 for the synthesized spell sound. They keep their own balance: a Strike is softer than a Wave.
  - `.ogg` files copied to the PC through Claude arrive untouched; `.mp3` files (like pictures) arrive with a provenance tag inside. So the MP3s are made on the PC, never copied there.
- **In the page:** `RSAudio.clip(name)` in `arena_audio.js` (decoded on first use, played through the effects bus, so the Sound effects setting applies); `playChanges` asks for it where it works out the card that was cast (`castSound`).
- **Test:** `web/test/ui/spellsound.js` (in `run_all.js`). Where there is no `art/sounds` folder it only checks that spells keep their synthesized sound.

### The fan-content notice (2026-11-16)
- The foot of the main menu carries the notice Jagex's Fan Content Policy asks fan-made work to show, in the policy's own words (clause 8.1; `FAN_NOTICE` in `arena.src.html`). Ramon asked for it. Don't reword it; if the policy's wording changes, change it to match. `layouts.js` checks it is on screen, readable and clear of the buttons at every window shape.
- Ramon has been told what else the policy says that bears on this project: clause 6.1.3 (video games and other software made with Jagex Property are not among the things it allows) and clause 2.4 (it doesn't cover Jagex's trade marks). What to do about that is his call; the policy gives IP@Jagex.com for questions. Claude isn't a lawyer.

### 7. Story Mode: chapter 1 built (2026-11-18)
Ramon's design, shaped in chat: `STORY_BRIEF.md` (Project doc: the plan and every decision), `STORY_CH1_SCRIPT.md` (Project doc: the script he reviewed, with his edits), `STORY_OPPONENTS.md` (repo: decks and tuning).

- **What's in it.** A **Story** button on the main menu (How to Play and Settings now share a row, to make room) → the Story screen (**New Game / Continue / Replay Chapter**, the reward trophies, "Chapter 2: Falador · coming soon") → scenes with a RuneScape-style dialogue box (chathead left for NPCs, right for you, numbered options, an unvoiced narrator, `1`–`4` and Space work) → an original map of Misthalin (dots open as the story reaches them) → duels → the reward → "Chapter 1 complete".
- **Chapter 1:** Hans → the Bandit → the silent stranger and the red flash → the fork. Road A: Highwayman → Draynor Village → Skeleton → Delrith in Draynor's cellar (Draynor Manor locked in play) → Claws of Guthix from the Wise Old Man. Road B: Goblin Band → Varrock library → Dark Wizard at the stone circle → Count Draynor (Moonlit Circle locked) → Shield of Arrav from Reldo. Both end heading to Falador (the game never says "both roads lead there": Ramon felt it cheapened the choice).
- **Code.**
  - `web/story.js` (`RSTCGStory`): the chapter as data (steps: lines, choices with tone/road/go, scene, flash, map, duel, reward, end) and a pure runner (`start`, `step`, `advance`, `enter`, `dotsOf`). A run is plain JSON pointing into the script by node id and index. `OPP`: each opponent's deck name, bot level, Location, lines, losing line. Inlined by `build_arena.py` (`/*__STORY__*/`).
  - The Story section of `arena.src.html`: `storyHTML`, `sceneHTML`, `mapHTML` (the map is an SVG drawn here, `storyMapSVG`), `storyPump` (runs the steps that don't wait), `storyAct` (every `st*` action), the boss lines (`duelSay`, from `settle`'s events), chatheads (`chathead`: `AS.face`, else the hood or a medallion).
  - **Story duels** are ordinary bot duels whose config has `story: { slot, opp, name, ch, location }`: saved and resumed the same way; they pay GP as bot duels do; `them()` names the opponent everywhere (and the locked Location's chips); the results screen says "Continue the story".
- **Saves.** `profile.story = { main, replay, roads: { ch1: ['A'] }, claimed: { 'ch1:A': card } }`. Old profiles load without it. Replay Chapter runs in `replay` and never touches `main`. New Game and a Settings reset keep every card earned. `cloudsave.js` merges it (rewards and finished roads add up from every device; where you are goes by the usual rule).
- **Rules changes (Ramon's go-ahead, both engines, parity checked):** `locked_location` (a Location in play from the start that nobody can replace or teleport over, owned by nobody); Draynor Manor (Magic +2, Melee −2) and Moonlit Circle (Melee +2, Magic −2), which never change a Punch (Ramon, 2026-11-18: it was dealing 0 in Delrith's cellar; `compute_damage(..., punch)`; and since that evening no Location changes a Punch at all, the Wilderness included: Ramon wants it a flat last-ditch chip, not a Melee edge; claw weapons that boost Punches may come in Set 2); abilities with `heals` (Bite). The JS engine now plays `burns` (pierce / shatter) as the Python one always did. Checks: `python web/test/story_rules.py` (each mechanic, same position, both engines), `python web/test/parity_story.py 200` (42 matchups, mean |z| 0.76). The 30 old decks and every old card export unchanged.
- **New cards** (`cards.py`, field `story`): rewards **Claws of Guthix**, **Shield of Arrav** (`story: 'reward'`: never in packs, collectable, legal everywhere); boss-only **Draynor Manor**, **Moonlit Circle**, **Demon Fire**, **Hellfire**, **Vampyric Claws**, **Bite** (`story: 'boss'`: `deckrules.js` refuses them in any player's deck, online too). Numbers and tuning: `STORY_OPPONENTS.md`.
- **Rewards and the bank.** Not banked: the card goes straight into the collection. Banked: `bankStory` queues the road in `rstcg-bank-v1`'s new `story` list, shows the card at once, and `bankSync` sends it (`story { keys }`; the server gives each `story:<chapter>:<road>` once ever). `packs.js` has `STORY_REWARDS` and `plausible()` keeps a story card a profile's `story.claimed` vouches for, so opening or replacing a bank keeps it (logged `story-kept`); a reset keeps the story's cards (`bank_story_refs`, migration `20261118190000_story_rewards.sql`, applied 2026-11-18).
- **Art** (all on the PC, as usual): chatheads are RS3 wiki chatheads in `art/faces/<speaker>.png` (the stranger is drawn: dark robes, ruby trim); four scenes in `art/scenes/` (Lumbridge Swamp, Draynor Village, Draynor Manor's interior, the dark wizards at the stone circle, cropped); card art in `art/img` with `art_map.json` entries; `arena_assets.py` packs `AS.face` and `AS.scene`. Other scenes reuse the backdrops and quest pictures, tinted (`SCENE_LOOK`). Kept for comparison on the PC: `*.before_story.*`.
- **Tests:** `node web/test/story_test.js` (every road and option walked, 2,053 checks), `node web/test/ui/story.js` (both roads by clicking, losing and retrying, Replay, resume, an old save, no "Bot", the bosses' lines and locked Locations), `node web/test/story_tune.js` (the difficulty ladder), `python story_rewards.py` (the rewards' worth), story cases in `bank.js`, `cloud_test.js`, `test_local.mjs`, `sql_test.mjs`, and the story screens in `layouts.js`.
- **Sound and transitions (2026-11-18, Ramon: too jarring, spells too soft, music audible when off).** Story screens dip through black (0.6 s out, 0.9 s in, `runFade`) instead of sliding; a change of place inside a scene crossfades the backdrop over 1.8 s, and the backdrops' slow drift no longer restarts on every line. Three new synthesized tracks: Tales of Gielinor (dialogue), Shadows over Draynor (the manor, the circle, night) and The Ancient Dark (boss duels, with an intense layer). The music-off leak was the shared reverb, wired straight into the master: each bus has its own reverb now, and music turned off plays nothing. Spell recordings: +6 dB and a low shelf for weight (about 8 dB over a hit); runes turned down to stay where they were. Check: `node web/test/ui/storyaudio.js`.
- **Also on 2026-11-18:** Vampyre Slayer's card art is Count Draynor in his coffin (Ramon's RuneScape Kingdoms page, cropped: `art/img/vampyre_slayer_count.png`; the wiki picture stays on the PC); Settings → Card text → Large for reading cards on a TV (`cardtext.js`).
- **2026-11-20 (Ramon):** boss rematches from the Story screen (once that road is finished; no reward, no story change); a change of place clears the box and fades the new place in slowly with its name; the stranger now meets you in Lumbridge Swamp (his painting `art/scenes/scene_swamp_stranger*.png`, his chathead `art/faces/Stranger.png`, keyed out of Ramon's picture), never in the menu's backdrops; Ramon's music (`art/music`, `arena_music.py`): Bazaar in Lumbridge and the Varrock library, Forest Day on the two ambush roads, Swamp Land in the swamp, and Ambient Room after a silence for the stranger. When he vanishes, the swamp without him (`scene_swamp_stranger_gone.png`) is cut in under the red flash (`FLASH_CUT`, 500 ms in, while the flash covers everything; the flash is now 3 s) and comes back into focus; the next line waits for the flash to fade. With it, a sound: `sfx('flare')` ("Ember", Ramon's pick of four candidates in `web/test/flare_candidates.js`), a breath in, a deep falling boom at the moment he vanishes, a shimmer and a low hum. Other places keep the synthesized songs. The page grew to about 13 MB with the music. Check: `node web/test/ui/storyscene.js`.
- **Chapter 2 (2026-11-20).** Starts in Falador on either chapter 1 road. Road A: Zamorak Cultist → (take him to the White Knights) → Sir Amik → Zombie Knight in the vaults → Crawling Corpse in the tunnels → **Dragith Nurn** (Falador Tunnels locked: Magic +2, Ranged −2) → **White Knight Armor** (found). Road B: Cultist → (follow him north) → Kinshra Scout → Shadow in the Edgeville house → **Melzar the Mad** (Hidden Prison locked: no prayers) → **Cabbage Armor**. Both end heading for the Black Knights' Fortress (chapter 3, "coming soon").
  - **Cards** (`cards.py`, both engines, `story_rules.py` all passed, `parity_story.py` mean |z| 0.68): rewards White Knight Armor (−2 Melee / 0 Ranged / −2 Magic, +2 to your attacks, any 2 bars) and Cabbage Armor (−3 / −3 / 0, any 3 logs); boss-only Necrotic Bolt, Soul Siphon, Raise Dead (Dragith; Necrotic Bolt costs Death Runes, as Bones aren't in Set 1), Cabbage (a material of its own family, counted by no skill cape), Cabbage Mace, Cabbage Seeds, Cabbages! (Melzar), Falador Tunnels, Hidden Prison. No new rules: Hidden Prison shares the Duel Arena's no-prayer check (`no_prayers()` / `noPrayers()`; food is still allowed there).
  - **The page:** the Story screen has a panel per chapter (trophies, Replay, rematches; Continue / New Game on the chapter you're in; a slim "coming soon" panel; the row scrolls sideways on narrow screens); `stchapter` starts the next chapter in the main story. `profile.story.path` records the road the main story took in each chapter (`storyPath()`; old saves fall back to the finished run or the only road done) and is handed to `STY.start(ch, prev)`: a step with `when: { ch1: 'A' }` is only played on that road (one line to Sir Amik). Each chapter has `home`, `roadText`, `doneMsg`, `region` (the map: `asgarniaSVG()` for Asgarnia, an original drawing from Ramon's references), `places`. A scene step `{ flash, far: true }` is the softer far-off flash. A reward step may carry `t` (found, not given). The deck picker greys out a cape the locked Location makes unearnable (`capeOff`: the Prayer Cape in the Hidden Prison). `SCENE_LOOK` entries may borrow another place's painting (`pic`), tinted.
  - **Rewards:** `STORY_REWARDS` has `ch2:A` and `ch2:B`; the server needed nothing new (`test_local.mjs` checks them).
  - **Art in the build:** Ramon's Falador, castle and Edgeville paintings (`art/scenes/scene_falador*.png`, `scene_edgeville.png`), the Shadow's chathead (`art/faces/Shadow.png`, the Shadow Demon helm), card art for Cabbage, Cabbage Mace, Cabbage Seeds, Cabbages!, Cabbage Armor (background removed), White Knight Armor (`art/img/<snake case>.png`; `arena_assets.py` now finds a card's picture by its name when `art_map` has none).
  - **Chatheads (2026-11-20, later):** Ramon's White Knight, Sir Amik Varze, Melzar the Mad, the Kinshra Scout (a Black Knight helm) and Dragith Nurn (cut from his artwork); the Crawling Corpse cut out of Jagex's concept art (GrabCut); the Zamorak Cultist cut from Ramon's full figure (hood and shoulders); the Zombie Knight is the White Knight helm tinted grey-green (`art/faces/<name>.png`). Long names on the duel nameplate shrink to fit (over 12 letters).
  - **Boss card art:** Necrotic Bolt is RS3's Necromancy ability icon, Soul Siphon its Soul Strike, Raise Dead Conjure Skeleton Warrior (`art/img/necrotic_bolt.png`, `soul_siphon.png`, `raise_dead.png`, upscaled to 360 px so the packer doesn't take them for pixel icons).
  - **Every chapter 2 picture is in.** (Ramon's Gemini paintings of the tunnels, Dragith's chamber, the house and the prison arrived the same day: `art/scenes/scene_falador_tunnels.jpg`, `scene_dragith_chamber.jpg`, `scene_edgeville_house.jpg`, `scene_hidden_prison.jpg`; the tunnels and the prison are also the two Locations' card art (`art/img/falador_tunnels.jpg`, `hidden_prison.jpg`), and the chamber and the prison their duel backdrops. Only the castle vaults still borrow the castle, darkened.) Ramon's music only plays in Falador, the castle, Edgeville and the two roads.
  - **Tests:** `story_test.js` (4,038 checks, both chapters), `ui/story2.js` (86 checks: from a pre-chapter-2 save, both roads by clicking, the Prayer Cape greyed out, the rewards, no "Bot"), `layouts.js` (the Story screen with two chapters, the Asgarnia map, Melzar's picker).
- **A new chapter** is mostly data: its script in `story.js` (`CHAPTERS`, `NEXT`: chapter 2's `NEXT` is chapter 3's "coming soon"), opponents in `OPP` and `STORY_DECKS`, its rewards in `STORY_REWARDS` (packs.js) and `cards.py`, its art, and its map (`storyMapSVG(ch)` by `ch.region`).

### 6. Later
- **Sounds for the six Flurry spells,** if Ramon wants them (the four Surge sounds are spare).
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
