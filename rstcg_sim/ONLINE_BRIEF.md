# Brief: online play for the RuneScape TCG Arena

**Status (2026-11-16): phases 0 to 2 are built. Challenge a friend works end to end** (Play Online → a name → a deck and cape → a code or link → the duel → rematch), against the real server. Next is accounts (phase 3, from 2026-11-16): see "Phase 3 (accounts): start here", which ends with the questions to ask Ramon first. Ramon has made the main decisions (below). This file sets out what he wants, how it works, what is built, and what is still open. Read `HANDOFF.md` for the wider project and `CLAUDE.md` for how the Arena's code fits together.

## What I want

Two ways to play another person, both from one **Play Online** screen:

- **"I just want to play someone": Find a match.** Press one button and get paired with whoever else is looking.
- **"I want to play my friend": Challenge a friend.** Send a link, or read out a short code. When the friend opens it, you duel.

It is one web app, so PC and phone play each other.

## Decided (Ramon, 2026-11-15)

1. **The server referees from day one.** The duel runs on the server, and each player is sent only what they may see. Nobody can peek at a hand or a deck.
2. **A name for now, accounts very soon.** The first time you go online you pick a name, with no email. Accounts follow soon after, so progress (GP, collection, decks) doesn't reset between devices.
3. **Online decks are your own decks,** under the same rules as against bots.
4. **Online duels pay GP like bot duels:** 100 for a win, 50 for a loss.
5. **Timers:** 60 seconds per decision (shown once 15 remain), 20 seconds to answer an attack, and running out three times loses the duel.
6. **Supabase:** a free project with a keep-alive for now; Ramon will move it to Pro later.
7. **Your opponent's deck list stays hidden** during a duel.
8. **Accounts come after a test with friends:** build phases 0 to 2, test Challenge a friend with real people, then do accounts.
9. **The reaction tell stays** (the duel waiting on the defender during your turn shows they hold a reaction). Same as MTG Arena.
10. **Timeouts win automatically.** A player who runs out of time three times loses; there is no "claim the win" button.
11. **Nothing at the table gives a deck away before it's played.** Both seats show the same hooded stranger (seat-coloured trim) and a name, never the deck's style icon or deck name; the intro reads "You vs" and their name. Done for bot duels too (2026-11-15).

**The project** (created 2026-11-15): "RuneScape TCG", id `udmaqoxyhiwpvxkrugwr`, region us-east-2, URL `https://udmaqoxyhiwpvxkrugwr.supabase.co`, publishable key `sb_publishable_eaN07rbCUAT_-FtdXkB2Iw_IjdGi_bJ` (public by design; the game ships it). Besides the online tables (below) it holds `keepalive`, a one-row table the keep-alive reads. The keep-alive is its own private repo, `RamonG3/rstcg-keepalive`: a GitHub Action reads that row on Mondays and Thursdays (first run checked 2026-11-15). Delete the repo once the project is on Pro.

## Phase 0: what's built (2026-11-15)

**On the server (live):**
- **Tables** (migration `server/supabase/migrations/20261115230722_online_v1.sql`):
  - `profiles`: your name. 3 to 12 letters, digits, spaces, `_` or `-`; unique whatever the capitals; "You" and "Bot" are taken. Players write their own row.
  - `matches`: a room or a duel: code, host, guest, seats (who plays first), names, version, the move count, the pending decision and its deadline, strikes, result. Players can read only their own; Realtime tells them when their row changes.
  - `match_secrets` (the seed and both decks) and `moves`: no player can read or write them.
  - Seven database functions the referee calls (create, find, load, mine, start, append, cancel); only the server's secret key can run them. `duel_append` adds a move only if nobody else got in first.
- **The `duel` Edge Function** (`server/supabase/functions/duel/index.js`), at `https://udmaqoxyhiwpvxkrugwr.supabase.co/functions/v1/duel`. Every call is a POST from a signed-in player:
  - `hello` → your name, your open room or duel, the version, and `intact` (the deployed rules still play a fixed test duel exactly as the build did).
  - `challenge {deck, cape, version}` → a room and its code. `peek {code}` → who is challenging. `join {code, deck, cape, version}` → the coin toss, the seed, and the duel starts. `cancel {match}`.
  - `view {match, since}` → what you may see. `move {match, seq, move}` → the same, after your move. `move {t: 'concede'}` works at any time.
- **The referee** (`web/referee.js`) rebuilds the duel from the seed and the move list on every call, on the synchronous engine.
  - A view holds the duel as you may see it, the decision now pending (with your choices, if it's yours), strikes, the result, and with `since`, each move you haven't seen with the picture after it (up to 40; past that, the whole duel afresh with its log).
  - Each player's own name becomes "You" in the log, so the engine's sentences read as they do against the bot.
- **The clock:** 60 seconds to choose or discard, 20 to react, plus 5 for animations and lag, plus 12 more for the first decision (the intro). The next call from either player after a deadline settles it: the turn ends (choose), no reaction (react), or the newest card in hand goes (discard), and it counts as a strike. The third strike loses the duel.
- **Deck check:** `web/deckrules.js` holds the deck rules for both the Arena and the server (60 cards, at most 4 of a card except basic materials, at most 1 Legendary, real cards only). The server can't check ownership until accounts.
- **Version stamp:** the first 10 hex digits of sha256(`engine.js` + newline + `cards.json`); now `d5389b9ea6`. The app will have to send the same one (phase 1: `build_arena.py` works it out the same way).

**Two things the tests found:**
- **Rune Pouch gave the deck order away.** Its targets are listed in the order the runes sit in your deck. The referee sorts them before sending; the engine is untouched.
- **The database reorders deck lists** (jsonb sorts keys), and the engine builds a deck in list order before shuffling. The referee always sorts deck lists by name before building the duel, so storage can't change a duel.

**Tested:**
- `node web/test/referee_test.js` (2,000 duels + 150 driven, about 20 s): every field of the engine's Player and Game is sorted into "public" or "hidden" (a new field fails the test until it is sorted); at every decision, everything the player can't see is dealt again at random and the picture and choices must not change; the log never names a card the player hasn't seen; replays match; illegal, out-of-turn, wrong-player, wrong-kind and stale moves are refused; timeouts, strikes, forfeits and concedes end duels correctly. Deliberately leaking a hand, the top card, the Rune Pouch order or a "drew X" line each made it fail.
- `node server/test_local.mjs` (about 6 s): the function itself in node, with a pretend database, playing whole duels between two "devices" that see only what the server sends; plus a check that the generated (and the minified) copy gives exactly the same views as `web/referee.js`.
- **Live smoke test** (`server/smoke.sql`, run through the Supabase connector because Claude's container and desktop shell can't reach supabase.co): names and their rules, the policies, challenge → peek → join, 24 moves, out-of-turn moves refused, a timeout settled after the deadline, concede, and reads of the secret tables refused. 126 ms per call on average. The test users were deleted afterwards.

**Deploying:** after a change to the rules, the cards or the server code, the function must be deployed again (until then the app and server disagree on the version):
- **Automatic, once set up:** `.github/workflows/rstcg-referee.yml` runs both tests on every push that touches them, and deploys if the repo has the secret `SUPABASE_ACCESS_TOKEN`.
- **By hand:** from `rstcg_sim/server`, `node build.js`, then `npx supabase functions deploy duel --project-ref udmaqoxyhiwpvxkrugwr --use-api` (needs `npx supabase login` once).
- **From a Claude session:** `NODE_PATH=<terser> node server/build.js --min`, then the connector's `deploy_edge_function` with `index.js` and the six `gen/` files (about 60 KB; check `hello` says `intact: true`).
- Duels in progress on an older version stop with a message; deploy between play sessions.

**Ramon's set-up steps:**
1. ~~Turn on anonymous sign-ins~~: done 2026-11-15, and checked: an anonymous sign-up gets a token the `duel` function accepts.
2. ~~A deploy token~~: done 2026-11-15 (the Actions secret `SUPABASE_ACCESS_TOKEN` in the Misc. repo). Pushes that touch the rules, the cards or `server/` now test and deploy the function by themselves (versions 3 and 4 went out that way on 2026-11-16).

**Also installed:** `pg_net` (database HTTP calls), for the smoke test and perhaps a scheduled clock sweep later.

## Phases 1 and 2: what's built (2026-11-16)

**In the game** (`web/online.js` and the Online section of `web/arena.src.html`):
- **Play Online** on the main menu (it shows your online record once you have one). The first time, it asks for a name. Then the duel screen in "online" mode: your deck and cape as usual, and **Challenge a friend** or **Join with a code** where the bot picker was.
  - **Challenge a friend** opens the waiting room: the 5-character code shown big, **Copy code**, **Copy link** (`https://runescapetcg.netlify.app/#duel-K7MQX`), **Share…** on phones, and Cancel. The duel starts on both screens when the friend joins.
  - **The link** opens the game straight on the challenge, past the first-launch welcome: pick a name if you need one, then "Duel Ann", your deck and cape, **Accept duel**.
  - **Join with a code**: a box near the top of the screen (a phone's keyboard covers the bottom half), then the same Accept screen.
- **The duel** looks and plays as against the bot. The page keeps a `Game` it never plays, sets it to each picture the server sends, and lets the bot duel's animations play the difference: cards fly, damage mills, capes tick. The other player's hand and deck are only counts (cards called `'?'`); when a card leaves their hand, the move names it, so it still flies from their hand. Your choices come from the server and go back to it; a card you play leaves your hand at once while the server answers.
  - Words: the other side is the player's name ("Ann swings…", "THEIR TURN", "THEY CAN CLAIM"), and "Ann is deciding" or "Ann may react" (the reaction tell) where the bot's "thinking" was.
  - **The clock** shows in a decision's last 15 seconds: on the End turn button, the reaction box or the hand-limit box, red and pulsing in the last 5. The other player's clock shows on their side. When time runs out, either page asks the server, the server settles it, and both pages say so ("Your time ran out: your turn ended (1 of 3)").
  - **Hearing moves:** Supabase Realtime pings the page when the duel's row changes (live: about a tenth of a second). If the live connection drops, the page asks every 2.5 seconds instead, and every 20 seconds anyway. "Reconnecting…" shows while the server can't be reached; the page keeps trying.
  - **Leaving and coming back:** Back to menu leaves the duel running (your clock too). The menu then shows **Resume duel · Online · vs Ann**, and reopening the app does the same. The page keeps its log and its last picture (`rstcg-online-v1`), so it catches up on the moves it missed without animating them.
  - **Concede** is in the duel menu, as against the bot.
- **Results:** the opponent is named ("Ann conceded", "Ann ran out of time"). 100 GP for a win, 50 for a loss or a draw, nothing for conceding or for losing on time (my call: it's walking away, the same as conceding). Each duel pays once on each device (`profile.onlinePaid`), and wins and losses go into an online record of their own (`profile.online`).
- **Rematch:** either player presses Rematch; the other sees "Ann wants a rematch" and presses it too, and the new duel starts on both screens with a new coin toss, each with the deck they have picked. Leaving the result screen takes back an offer nobody took up.
- **No library:** `online.js` is about 150 lines of fetch and WebSocket (Supabase's sign-in, names table, the function and Realtime's channel protocol), instead of bundling the Supabase client.
- **Same version check:** `build_arena.py` works out the version stamp exactly as `server/build.js` does (now ignoring Windows line endings in both) and puts it in the page; the server refuses a challenge from another version, and the page says "A new version is out: reload".
- **The artifact can't play online** (claude.ai blocks other sites): Play Online there says so and points to the app.

**On the server:**
- **Rematches:** a `rematch` op (offer, take up, or take back) and migration `20261116035711_online_rematch.sql`: `matches.next` points a finished duel at the room for its rematch; `duel_room` and `duel_next`; closing a room clears the offers pointing at it; two offers at once end in one duel.
- **Views now include what's left in your own deck** (`left`: how many of each card, never the order), for the "no Silverlight in your deck" kind of preview.
- Function version 4 (2026-11-16), deployed by the GitHub Action.

**Tested:**
- `node web/test/ui/online.js [decisions]` (about 2.5 minutes, in `run_all.js`): two browsers against `server/fake_supabase.mjs`, a stand-in for Supabase that runs the real function on the pretend database and speaks Realtime's protocol. Ann challenges; Bob joins with the link and brings a deck full of reactions; they play by clicking and choosing; Ann reloads mid-duel and resumes from the menu while Bob plays on; Ann's clock runs out once; the live connection drops for a while and the pages poll; the board is checked against a fresh redraw; then a concede, the results and GP, a rematch offered and taken up, and the refusals (a taken name, a code that isn't there). Screenshots in `web/test/ui/shots/online/`.
- `node server/test_local.mjs` now also covers rematches (offered, taken up, taken back, replaced by a new challenge, two at once, an offer from an older version).
- **Live, 2026-11-16:** in the desktop app's browser pane (on the live site, with `online.js` pasted in), two pretend players went through sign-up, names (a taken one refused), challenge, peek, join, six moves, a token refresh, concede, and a rematch offered and taken up. Realtime pinged both players for every change, in about 0.1 s; moves took 230 to 400 ms. The test players were deleted afterwards.

**Not done yet:** Find a match (phase 4), accounts (phase 3). A duel in progress when the rules change stops with "a new version is out" and pays nobody.

## Phase 3 (accounts): start here

Ramon moved on to accounts on 2026-11-16, and nothing for them is built yet. This section was written before the chat that builds them, as the ground to stand on. Ask Ramon the three questions at the end before building.

**Progress today** lives only in the browser's `localStorage` (through `store` in `arena.src.html`):
- **`rstcg-profile-v1`, the progress itself:**
  - `gp`; `coll` (the cards you own, name → count; basic materials aren't in it, they're unlimited); `decks` (your own decks);
  - `packs` (how many opened) and `lastPack`; `tutorial` and `tutorialPack` (the free pack, given once); `tips` (one-time tips already seen);
  - `wins` and `losses` against bots, `online` (`{wins, losses}`), and `paid` / `onlinePaid` (so a duel pays once).
- **Per device, and probably staying so:** `rstcg-settings` and `rstcg-speed`, `rstcg-arena-setup` (the last deck, cape and level picked), `rstcg-arena-duel-v1` (a bot duel in progress).
- **`rstcg-online-v1`:** the anonymous sign-in (`auth`), your online name, the online duel you're in.
- **The device is the bank:** packs are rolled on it (`grantPack`), and bot duels pay on it.

**What Supabase offers** (its docs, read 2026-11-16):
- **An anonymous player becomes a permanent one by linking a sign-in, and keeps the same user id,** so the online name, the duels and anything stored against that id carry over.
  - Email: `updateUser({email})`, then the player confirms with what's emailed.
  - Google: `linkIdentity({provider: 'google'})`, a round trip to Google and back.
  - Both need **manual linking** turned on (dashboard: Authentication → Sign In / Providers).
- **On a second device, the player simply signs in** (Google, or an emailed code). That device's own anonymous player and its progress are left behind, which is question 2 below.
- **An emailed 6-digit code can replace the link:** the email templates need `{{ .Token }}`, and the game checks the code (`verifyOtp`). A code typed into the game never has to find its way back into the installed app, which a link opened from an email app might not.
- **Sign-ins only return to allowed addresses:** `https://runescapetcg.netlify.app` goes in Authentication → URL Configuration.
- **Anonymous sign-ups are limited to 30 an hour per IP address** (the default). Fine for friends; Supabase advises a CAPTCHA before strangers come (phase 4).
- **No library:** `online.js` talks to Supabase with fetch alone, so each of these becomes a plain call to its Auth API. Check every call against the docs or the supabase-js source before building on it, and teach `server/fake_supabase.mjs` the same calls so the browser tests cover them.
- **Check early, on the Pixel, in the installed app,** that Google's round trip comes back into the app and not into a Chrome tab.
- **The connector can't change Auth settings:** manual linking, providers, email templates, SMTP and allowed addresses are Ramon's clicks in the dashboard.

**Ask Ramon first:**
1. **Sign in with Google, email, or both?**
   - **Google:** no passwords and no emails to send. Ramon makes a free OAuth client in Google Cloud (type Web application; its redirect URI is the callback the Supabase dashboard shows under Providers → Google, `https://udmaqoxyhiwpvxkrugwr.supabase.co/auth/v1/callback`) and pastes its id and secret into Supabase. Google's sign-in screen may name the Supabase address rather than the game.
   - **Email:** for anyone, with or without Google. Supabase's own email only reaches the project's team, so it needs a sending service (custom SMTP). Most want a domain of your own to send from, and `runescapetcg.netlify.app` can't be one (Netlify owns `netlify.app`): so a domain of Ramon's, or a personal mailbox such as Gmail (the sign-in emails then come from that address).
   - **Both:** they sit side by side; the extra work is mostly email's set-up.
2. **A device that already has progress signs in to an account that has some too: which wins?** Keep the account's; keep whichever has more cards; merge (every card from both, the higher GP); or ask each time.
3. **How much should the server trust the device?** Today someone with the browser's developer tools can give themselves GP or cards. Offline that hurts nobody; online it decides which cards they can bring.
   - **Cloud save:** the account stores progress as the devices report it, and they sync. Quick to build; bot play stays offline; cheating stays possible.
   - **The server is the bank:** GP and cards live on the server; it opens packs, pays online duels (it referees them, so it knows who won) and checks online decks against the collection, as planned above. Bot duels still run on the device, so their GP either comes on the device's word (perhaps with a daily cap) or is checked: a bot duel is its seed plus your choices, which the save already keeps, so the server could replay it before paying. Mind the 2 s CPU limit with Champion (about 36 ms a move), and that a device choosing its own seeds could choose kind ones.
   - A suggestion (Claude's, 2026-11-16): cloud save while it's friends, and the bank before Find a match lets strangers in.

## Why the server referees

**Today:** a duel is the engine plus two agents: `human` (you) and `botAgent` (the bot).
- The engine asks whichever agent it needs: `choose`, `react` or `discard_choice`.
- Everything random, every shuffle included, comes from one seeded `RNG`. The same seed and the same answers always give the same duel; saved duels resume this way.

**The design not taken ("lockstep"): both devices run the duel and only pass moves.**
- It is quicker to build, but whoever holds the seed can work out both decks' order and the opponent's hand.
- A player with the browser's developer tools could read them.

**With a referee, the seed and the decks never leave the server.**

**Measured (200 bot-vs-bot duels on the starter decks, 2026-11-15):**
- A duel is about 36 decisions on average, 65 at most.
- The move list is about 1 KB as JSON.
- Replaying a whole duel from its move list takes about 0.5 ms in node, 8 ms at worst, and every replay matched the original.

So the server can afford to rebuild the duel from scratch on every move.

## How the referee works

### On the server

- **One Edge Function, `duel`, runs the same `engine.js` and `cards.json`.**
  - A build step makes the Deno copy, as `build_arena.py` does for the page.
  - Never keep a second engine by hand.
- **Each request rebuilds the duel:**
  1. Load the match, its secret seed and its move list.
  2. Rebuild the `Game` and answer the engine's questions from the list.
  3. The first question the list can't answer is the decision now pending: who must answer, what kind (choose, react or discard), and the legal answers.
  - It will use the synchronous engine copy (`unwait`, as the hard bot does). Its agent throws "decision needed" when the list runs out, so the rebuild simply stops there.
- **When a player sends a move, the server:**
  1. checks that the move comes from the player being asked, and that it is one of the legal answers;
  2. adds it to the move list;
  3. runs on to the next pending decision;
  4. works out what each player may see;
  5. tells both players something changed. Each then fetches its own view.
- **The clock:** each pending decision gets a deadline, kept by the server. A claim that the other player has run out of time is checked against it.

### On the device

- **The duel screen gets a second driver.**
  - Bot duels keep running the engine on the device, offline, as now.
  - Online, a new `runOnlineDuel` feeds the same screen from the server.
- **Each view from the server becomes a display-only `Game`.**
  - Everything public is real, and so is your own hand. Hidden cards are blank placeholders.
  - The aim is for `duelHTML`, `snap` and `playChanges` to keep working on it, so cards still fly and capes still tick.
  - Legal moves and prompts come from the server, not from the device's engine.
- **Making a move:** your answer goes to the server, and the screen waits briefly for the next view.
  - While the other player decides, the bot's "thinking" spot shows "Waiting for Zezima…" with their clock.

### What each player sees

| Who | What |
| --- | --- |
| **Both players** | Everything in play, both discard piles, deck and hand sizes, cape progress, the duel log. |
| **Only you** | Your hand, and the cards you draw. |
| **Nobody** | The order of either deck (your own too) and the seed. |
| **Hidden until the duel starts** | Each player's cape, as now: both capes flip face up when the duel starts. |
| **Hidden all duel** | Your opponent's deck list, as in MTG Arena. |
| **Hidden until played** | The style of either deck: both seats show the same hooded stranger and a name, not a style icon or deck name. |

### Testing the referee

- **Node tests.** The referee code also runs in node. Tests play thousands of duels through it and check that:
  - no view sent to a player ever names a card that player can't see (a "no leaks" test);
  - every duel replays identically;
  - illegal and out-of-turn moves are refused.
- **Browser test.** Two headless browsers duel each other through a real Supabase project, from start to finish, like `arena_play.js`.

## The two modes

### Challenge a friend

1. **Start it:** Play Online → pick a deck and cape → **Challenge a friend**.
2. **The server opens a duel room with a 5-character code.** It skips look-alike characters: no 0/O, no 1/I/L.
   - On a phone, the share sheet sends a link (`https://runescapetcg.netlify.app/#duel-K7MQX`) to Messages, WhatsApp and so on.
   - On PC there is a Copy button.
   - The code is shown big, so it can be read out.
3. **The friend joins:** they open the link, or type the code under **Join with a code**. They see "Ramon challenges you", pick a deck and cape, and press Accept.
4. **The duel starts:** the server flips for first player, picks the seed, and starts the duel.

- **Waiting room:** "Waiting for your friend…" with Cancel. Rooms expire after a day.
- **Afterwards:** Rematch, which both players must press: the first press offers it, the second takes it up.

### Find a match

1. **Start it:** Play Online → pick a deck and cape → **Find a match**.
2. **Queue:** first come, first served; ratings can come later. The server pairs you with the player who has waited longest.
3. **Match:** "Opponent found: Zezima" → the duel starts.

- **Show who's around:** how many players are online and searching, so an empty queue isn't a mystery.
- **Nobody there:** after a while, offer **Play a bot instead**.
- **With strangers:**
  - Preset emotes only (Good luck, Well played, Thanks, Oops), no free chat.
  - A short word filter on names.
  - **Block**, so you are never paired with that player again.

### Shared by both modes

- **Names:** the first time you go online, you pick a name, and names are unique.
- **Timers:** online needs a clock, or a player who walks away freezes the duel. Decided:
  - 60 seconds per decision, shown once 15 remain.
  - 20 seconds to answer an attack.
  - Running out three times loses the duel.
- **Leaving:** Leave keeps your seat, and your clock still runs.
- **Dropping out:** if a device loses connection or goes to sleep, reopening the app fetches the duel as it stands and carries on. A player who stays away runs out of time, and the third time loses automatically (no claim button).
- **Concede** counts as a loss.
- **Same version only:** each build gets a version stamp, a hash of `engine.js` and `cards.json`.
  - The device and the server must agree.
  - An out-of-date app is told to update. The app's offline copy fetches the new version whenever it's online, so a reload is enough.
- **Results and GP:**
  - Results are stored with the duel, and the result screen names the opponent.
  - GP is 100 for a win and 50 for a loss.
  - Until accounts exist, the device adds the GP to its local save, as for bots. With accounts, the server pays it.
- **Decks:**
  - Until accounts exist, the server checks the deck rules (60 cards, at most 4 copies, at most 1 Legendary, real cards) but has to trust that you own the cards.
  - With accounts, it checks them against your collection.

## Backend: Supabase

**Ramon's account today** (checked 2026-11-15):
- Organization "RG3 Designs", on the free plan.
- It has one project: RuneScape Kingdoms TTRPG Website.
- The free plan allows 2 active projects, so the TCG gets its own free project.

**What Supabase provides:**
- Anonymous sign-in: an account with no email, which can later be linked to Google or email, keeping the same account.
- Postgres tables with row-level security.
- Realtime: the "something changed" pings, and presence for "players online".
- Edge Functions (Deno), for the referee and matchmaking.

**Free-plan limits** (pricing page, checked 2026-11-15):
- 200 simultaneous Realtime connections.
- 2 million Realtime messages a month.
- 500,000 Edge Function calls a month.
- 500 MB of database.

Even if every move costs three function calls (the move, plus each player fetching their view), a duel is about 110 calls. That leaves room for about 4,500 duels a month.

**The catch: free projects pause after a week with no activity.** Online play would then stop until someone resumes the project in the dashboard. Two fixes:
- A keep-alive ping: done, in its own repo (`RamonG3/rstcg-keepalive`, Mondays and Thursdays).
- The Pro plan at $25 a month, which never pauses (Ramon's plan for later).

**Other facts:**
- An Edge Function gets 2 seconds of CPU per request. Rebuilding a duel takes milliseconds.
- Anonymous sign-up is rate-limited per IP address. Supabase recommends adding a CAPTCHA (such as Cloudflare Turnstile) to stop abuse.
- A Claude session can set up the project, tables, policies and functions through the Supabase connector. The app itself still goes to Netlify by drag-and-drop.

**Alternatives considered:**
- **Cloudflare Durable Objects:** one live object per duel room, on the free plan since April 2025, sleeping when idle and waking on the next request. A good fit for a referee, but it needs a Cloudflare account and its own deploy tool, while Supabase is already connected.
- **Peer to peer (WebRTC):** can't hide cards, because both devices would run the duel.

## Data (as first planned; see "Phase 0: what's built" for what was made)

- **`profiles`:** user id, name, created. With accounts, it also holds progress.
- **`matches`:**
  - id, invite code, kind (challenge or queue), status (waiting, ready, playing, done, abandoned);
  - the two players, version stamp, the pending decision and its deadline, result, timestamps;
  - `next`: the room for a rematch (added in phase 2).
  - Both players can read these.
- **`match_secrets`:** the seed, both deck lists and both capes. **No player can read this table.** Only the referee function can.
- **`moves`:** match id, sequence number, seat, the move. Moves are only ever added, and only by the referee.
- **Pairing and joining run as database functions,** so two players can't grab the same opponent.

## Where it plugs into the code

- **Menu:** "Play Online" (now "coming later") opens a new Play Online screen.
  - It is a new `onlineHTML` in `SCREENS`, reusing the deck and cape pickers from `pickHTML`.
- **Duel:** a new `runOnlineDuel(match)` sits beside `runDuel`.
  - It builds the display-only `Game` from each view.
  - It turns the server's pending decision into the same `ui.pending` prompts `decide()` shows today.
  - It sends your answer instead of resolving a local promise.
  - Names come from the match, not "Bot": `oppName()` already names the other side on the nameplate and in the intro (the bot's level name for now).
- **Network code:** a new module `online.js`, inlined by `build_arena.py` like the others.
  - Built without the Supabase JavaScript client (fetch and WebSocket only). No CDN.
  - Offline play makes no network calls.
- **Server code:** `server/supabase/functions/duel/` holds the Edge Function. `node server/build.js` copies the current `engine.js` (synchronous), `cards.json`, `referee.js` and `deckrules.js` into its `gen/` folder.
- **Saves:** a new key, `rstcg-online-v1`, holds your online id and name and the duel you're in. Don't touch the existing keys.
- **Online play only works from the Netlify app** (runescapetcg.netlify.app, installed or in the browser).
  - The claude.ai artifact blocks connections to other sites.
  - In the artifact, the Play Online screen points to the app.

## Phases

0. **Groundwork (done 2026-11-15; see "Phase 0: what's built"):**
   - the Supabase project and its keep-alive;
   - tables and policies;
   - anonymous sign-in and names;
   - the referee function (rebuild, move checks, views, clock);
   - its node tests (no leaks, replays, refused moves).
1. **The duel screen online (done 2026-11-16):** `runOnlineDuel`, the display-only `Game`, waiting and clocks, rejoining. Tested with two headless browsers.
2. **Challenge a friend (done 2026-11-16):** links and codes, the waiting room, rematch.
3. **Accounts and cloud progress** (next, from 2026-11-16; see "Phase 3 (accounts): start here"):
   - **Signing in:** sign in with Google and/or email, linked to the name you already have.
   - **Progress on the server:** GP, collection, decks, packs and tutorial state, so PC and phone share progress.
   - **The server takes over:** online decks are checked against your collection, and the server pays online GP.
   - **Setup needed:**
     - Google sign-in needs a Google OAuth client, made once in Google Cloud.
     - Email sign-in needs a mail-sending service, because Supabase's built-in email only reaches the project's own team members.
   - **The first sign-in on a second device** asks which progress to keep.
   - Decided: this comes after a test of Challenge a friend with real people.
4. **Find a match:** the queue, players online, emotes, blocking, and recent opponents you can challenge again.

**Later:**
- a friends list with who's online;
- challenge notifications;
- a ranked ladder.

## Still open for Ramon

- **Accounts (phase 3):** the three questions at the end of "Phase 3 (accounts): start here": Google, email or both; which progress wins when a second device signs in; and how much the server trusts the device.
- **Capes still hint at the deck.** Both capes flip face up when the duel starts, and an Attack Cape points at melee about as clearly as the old sword icon did. That's a rule (Ramon's call): keeping capes hidden until claimed would close it.

## Done means (phases 0 to 2)

Everything below is met in tests (2026-11-16) except the first, which is Ramon's test with friends.

- **Full duels across devices:** two people on different devices (PC and phone) can duel by link or code from start to finish, with no page errors.
- **Nothing leaks:** no view either player receives contains a card they shouldn't see. The node "no leaks" test checks thousands of duels.
- **The server enforces the rules:** it refuses moves that are illegal, out of turn or from the wrong player.
- **Rejoining works:** closing the app mid-duel and reopening it carries on where the duel was.
- **Clocks work:**
  - A player who stops moving loses on time.
  - A player who leaves loses on time automatically; nobody has to press anything.
- **Tested:** two headless browsers play full duels in a test (against a stand-in for Supabase that runs the real function), and the page's network code was checked live against the real server.
- **Nothing else changes:** the bot game, offline play and the artifact work as before.

## Sources

- Supabase pricing (free-plan limits, 2 active projects, pausing, Pro): https://supabase.com/pricing
- Free project pausing: https://supabase.com/docs/guides/platform/free-project-pausing
- Anonymous sign-ins (linking to Google or email later, rate limit, CAPTCHA): https://supabase.com/docs/guides/auth/auth-anonymous
- Built-in email only reaches the project's team; custom SMTP: https://supabase.com/docs/guides/auth/auth-smtp
- Linking an identity (manual linking): https://supabase.com/docs/guides/auth/auth-identity-linking
- Emailed codes instead of links (`{{ .Token }}`, `verifyOtp`): https://supabase.com/docs/guides/auth/auth-email-passwordless
- Google sign-in: https://supabase.com/docs/guides/auth/social-login/auth-google
- Edge Function limits (2 s CPU): https://supabase.com/docs/guides/functions/limits
- Cloudflare Durable Objects on the free plan: https://developers.cloudflare.com/changelog/2025-04-07-durable-objects-free-tier/
