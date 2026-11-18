# Brief: online play for the RuneScape TCG Arena

**Status (2026-11-16): phases 0 to 3 are built. Challenge a friend works end to end** (Play Online → a name → a deck and cape → a code or link → the duel → rematch), against the real server. **Accounts are built and tested against a stand-in server** (sign in with Google or an emailed code; progress saved to the account and shared between devices); **Google sign-in is live (2026-11-17)** and worked on Ramon's Pixel; emailed codes wait on a sender. See "Phase 3 (accounts): what's built". **The server is the bank (phase 3b, built 2026-11-17):** GP, cards and packs live on the server for everyone who has been online; see "Phase 3b (the bank): what's built". Next is Find a match (phase 4). Ramon has made the main decisions (below). This file sets out what he wants, how it works, what is built, and what is still open. Read `HANDOFF.md` for the wider project and `CLAUDE.md` for how the Arena's code fits together.

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

**Not done yet (as of phases 1 and 2):** Find a match (phase 4). Accounts came next, below. A duel in progress when the rules change stops with "a new version is out" and pays nobody.

## Phase 3 (accounts): what's built (2026-11-16)

**Ramon's answers** to the three questions (2026-11-16):
1. **Sign in with both:** Google, and a code sent by email. Google goes first; email switches on once there is something to send it with.
2. **A device with progress meets an account with progress: ask each time.**
3. **Cloud save now;** the server becomes the bank before Find a match lets strangers in.
4. His real progress is in the Netlify app, so nothing had to be carried across from the claude.ai artifact.

**What a player sees:**
- **An account button** in the main menu's corner: "Sign in", or your name with a tick (saved), a turning arrow (saving), a cloud with a line through it (offline) or a warning (a question is waiting). Settings has an **Account** row too.
- **The account screen.** Signed out: **Sign in with Google** and **Sign in with an emailed code** (whichever the server has switched on), and a line on what an account is for. Email asks for the address, then the 6-digit code. Signed in: who you are, when progress was last saved, **Sign out**.
- **Which progress do you keep?** When a device that has been played on signs in to an account that has progress too, the two are shown side by side (GP, cards, decks, record, when last played) and you pick one, with a second press to confirm. The one you didn't pick is put aside on the device (`rstcg-backup-v1`), in case it was the wrong button. A device nobody has played on just takes the account's; an account with nothing takes the device's.
- **After that it is invisible:** progress is saved a moment after it changes, fetched when you come back to the menu or the app, and what two devices earned apart is added together.
- **Signing out** makes a last save, then the device forgets the sign-in and the online name. The progress stays on the device as it was.
- **Accounts need the app** (`runescapetcg.netlify.app`): the artifact and a page opened from a file say so, as Play Online does.

**How signing in works** (`web/online.js`, plain calls to Supabase's Auth API, no library; each checked against auth-js 2.119.0 and the Auth server's source):
- **A guest who signs in stays the same player.** Their anonymous user is *linked* to the sign-in, so the id, the online name and any duel carry over.
  - Google: `GET /auth/v1/user/identities/authorize?provider=google&redirect_to=…&code_challenge=…&code_challenge_method=s256&skip_http_redirect=true` (needs **manual linking** on), then the page goes to the address it returns.
  - Email: `PUT /auth/v1/user {email}`, then `POST /auth/v1/verify {email, token, type: 'email_change'}` with the code.
- **If that sign-in already belongs to a player** (the second device), linking is refused (`identity_already_exists` / `email_exists`) and the page signs in as that player instead: Google by going to `/auth/v1/authorize?…`, email by `POST /auth/v1/otp {email, create_user: true}` and `verify` with type `email`. The device's guest is left behind.
- **PKCE:** the page makes a verifier (48 random bytes, base64url), sends its SHA-256 as the challenge, and keeps the verifier in `rstcg-online-v1` (`flow`) across the trip to Google. Google sends the player back to the game with `?code=…`; `POST /auth/v1/token?grant_type=pkce {auth_code, code_verifier}` turns it into a session. Errors come back as `?error=…&error_code=…` (sometimes after `#`). The address is cleaned as soon as it has been read.
- **Codes, not links, for email:** a link opened from an email app might land in a browser tab instead of the installed app. The three templates a player can get (Confirm signup, Magic Link, Change Email Address) must show `{{ .Token }}`.
- **Which ways are offered** comes from `GET /auth/v1/settings` (`external.google`, `external.email`), asked at most once a minute. With neither on, the screen says signing in isn't switched on yet.
- **A sign-in that ends on the server** (signed out elsewhere, or the account deleted): the next refresh fails, the device forgets it, says so once, and keeps the progress it has. A guest whose session ends silently becomes a new guest, as before.
- **Two windows:** signing in or out in one reloads the other (a `storage` listener), unless it is in the middle of a duel.

**How the cloud save works:**
- **One row per account** in `progress` (migrations `20261116155830_accounts_progress.sql` and `20261116155904_accounts_progress_accounts_only.sql`): `data` (the profile as jsonb, 400 KB at most), `rev` (a trigger adds one on every change), `updated_at`. Row security: only a signed-in *account* can read or write its own row; guests and strangers get nothing.
- **`progress_save(p_data, p_rev)`** saves only if the row is still at the revision the device last saw (`p_rev` null = "there is no row yet"). Otherwise it changes nothing and hands back the newer save. That is what stops a device with an old copy overwriting a newer one.
- **`web/cloudsave.js`** (`RSTCGCloud`) is the sync, with no knowledge of the page:
  - the device keeps a **mark** inside the profile itself (`profile.cloud`: the account's id, the revision, when, and `base`, the save as both sides last agreed it), so progress and mark are always written together;
  - nothing changed here: fetch anything newer; something changed here: save, and if the server has moved on, **merge** and try again;
  - **merging** is three-way against `base`. GP, wins, losses and packs opened: theirs plus what this device added. Cards: the same, card by card. Decks: by id (changed here wins, made on either side kept, deleted on one side gone unless the other changed it, never left with none). Tips already seen and online duels already paid: either side's. Anything else (the tutorial flags, the last pack): this device's if it changed it, else theirs;
  - a device that has never met this account asks (see above) instead of merging: there is no shared `base` to merge from.
- **Still per device:** settings, speed, the last deck and cape picked, a bot duel in progress, and the online duel's log.
- **The device was still the bank** until phase 3b (below): GP, cards and packs now live on the server.

**Tested:**
- `node web/test/cloud_test.js [worlds]`: the merge rules case by case, the sync step by step against a pretend server that reorders keys as jsonb does, and thousands of random "worlds" of three devices earning, spending, deleting and syncing in any order with the network failing at random. Every world must end with all devices and the server agreeing, nothing earned lost and nothing counted twice. (3,000 worlds: about 360,000 steps and 27,000 merges.)
- `node web/test/accounts_test.js`: every account call in `online.js` against `server/fake_supabase.mjs`, which now speaks enough of Supabase Auth to be signed in to (Google with a pretend consent page, emailed codes read back through `/__test/mail`, linking on or off, sessions ended from the server) and holds `progress` with the same rules as the SQL.
- `node web/test/ui/accounts.js` (about 3 minutes, in `run_all.js`): browsers as a PC, a phone, a tablet and a laptop. A guest plays, signs in with Google and keeps their name; a second device signs in and gets the progress; both earn apart, one of them offline, and the totals add up; "which progress" both ways, and when the account changes mid-choice; emailed codes, a wrong code, a mailer that refuses; signing out and in; a second window; a session ended by the server; neither way switched on; and signing in held back during an online duel. Screenshots in `web/test/ui/shots/accounts/`.
- `server/progress_check.sql`: 17 checks of the live table as an account, another account, a guest and a stranger; it rolls itself back. Run through the connector.
- **Live, 2026-11-16** (through the connector, with `pg_net`): a real guest sign-in was refused a save (403) and read nothing; linking Google answered `manual_linking_disabled`, as the page expects; `/auth/v1/settings` reads as the page reads it. The test guest was deleted. **Live, 2026-11-17:** with Google switched on, `/auth/v1/authorize?provider=google` leads to Google's normal sign-in page (a wrong client would give Google's error page). Then Ramon signed in with Google in the installed app on his Pixel: he came back into the app (not a Chrome tab), and the server holds one account with a `google` identity and a `progress` row at revision 1 holding the Pixel's progress. The Pixel had no live guest, so the page made a fresh guest and linked Google to it (made and signed in a second apart); that account has no online name yet, so Play Online will ask for one. It is Ramon's real account: keep it. **Emailed codes have not been tried live** (email is off), nor a second device signing in to the same account.

**Set-up in the Supabase dashboard** (none of it can be done through the connector; Claude can do it in the desktop app's browser pane once Ramon has signed in there). **As of 2026-11-17: steps 1, 2, 3 and 5 are done; Email is switched off**; `/auth/v1/settings` reads `email: false, google: true, anonymous_users: true`.
1. ✅ **Authentication → URL Configuration:** Site URL `https://runescapetcg.netlify.app`, and `https://runescapetcg.netlify.app/**` in Redirect URLs (done 2026-11-17).
2. ✅ **Authentication → Sign In / Providers:** **Allow manual linking** on (done 2026-11-17).
3. ✅ **Google** (done 2026-11-17):
   - **Google Cloud:** project "RuneScape TCG" (id `runescape-tcg`), owned by Ramon's Gmail (Google Cloud made him turn on 2-step verification first). Google Auth Platform: app name "RuneScape TCG", audience External, his Gmail as support and developer contact, basic scopes only.
   - **Client:** "Supabase sign-in", Web application, id `604614878740-gb9l3f6psblf1ic44a4k6tif0th7pkhb.apps.googleusercontent.com`; JavaScript origin `https://runescapetcg.netlify.app`; redirect URI `https://udmaqoxyhiwpvxkrugwr.supabase.co/auth/v1/callback`. Ramon pasted the secret into Supabase himself and has the downloaded JSON; it is in no repo. Google can't show it again: a lost secret means adding a new one on the client and pasting that.
   - **Supabase:** Providers → Google on, with that client id and secret.
   - **Still in "Testing":** only listed test users can sign in (Ramon is the only one; 100 at most, counted over the app's whole life). **To let friends in, publish it** (Audience → Publish app); Google first wants the Branding page finished (it says so on the Audience page). With only email, profile and openid, publishing needs no review.
   - Google's sign-in screen says "to continue to udmaqoxyhiwpvxkrugwr.supabase.co". Showing the game's own address there needs a Supabase custom domain (a paid add-on).
4. **Email, when wanted (switched off 2026-11-17 until then):** a sender (Authentication → Emails → SMTP Settings: a Gmail app password, or a domain of Ramon's), and `{{ .Token }}` added to the Confirm signup, Magic Link and Change Email Address templates. **Until then, switch the Email provider off** (Providers → Email), or the game offers a way to sign in that can't deliver its code.
5. ✅ **On the Pixel, in the installed app:** Google's round trip comes back into the app, not a Chrome tab (Ramon, 2026-11-17).

**Not done yet:**
- **Deleting an account** from inside the game (today: the dashboard).
- **A CAPTCHA** on sign-ups, which Supabase advises before strangers come.

## Phase 3b (the bank): what's built (2026-11-17)

**Ramon's answers** (2026-11-17):
1. **Bot duels: trust the device.** They run on the device, so the server can't see them; the device reports each one and the server believes it. (Replaying them to check was offered and turned down.)
2. **Everyone who has been online is banked,** guests included: a guest's GP and cards live on the server from their first time online, and signing in later keeps them. Find a match will be open to guests.

**What it means for a player:**
- **A device that has never been online is unchanged:** everything stays on it, packs included.
- **Once online, the server keeps the GP, cards and packs.** The device shows them and can't change them. Opened the first time:
  - an account that already has a cloud save: from that save, as it is;
  - otherwise this device's progress, if honest play could have made it (`plausible` in `web/packs.js`: no more cards beyond the starter decks than 6 a pack opened, and no more GP than 100 a duel recorded, less 200 a pack, plus 500 to spare). Ramon's own save passed it to the card (102 extra cards from 17 packs);
  - otherwise the three starter decks, and the player is told why ("This device's progress didn't add up...").
- **Bot duels** pay on the device at once, as before, and are queued (`claims` in `rstcg-bank-v1`) until the server has them: straight away online, or when the device is back. Each duel once (by its seed), at most 200 a day. A duel played while signed out counts for the player this device last banked with, when they sign in again.
- **Online duels** are paid by the database the moment the result is recorded (`duel_append` → `bank_pay_duel`): 100 for a win, 50 for a loss or a draw, nothing for conceding or running out of time. The page just fetches the bank afterwards.
- **Packs are opened by the server** (it rolls them with `web/packs.js`, the same code the page uses offline), so they need a connection: offline, the player is told so and nothing is spent. Each pack has a ref the device keeps until the server answers, so asking again after a lost answer gives the same pack, not a second one. The tutorial's free pack: once per player.
- **Online decks must be owned:** challenge, join and rematch check the deck against the bank's cards ("You don't own enough copies of ...").
- **The cloud save no longer carries GP, cards or packs** once the device is banked (`progressNow()` leaves them out; `progressAll()` is everything). Decks, records, tutorial and tips still go through it as before.
- **"Which progress do you keep?"** shows the account's bank beside this device's numbers (`bankPeek`). Keeping this device's: checked with `plausible` before anything changes, then the cloud save, then the bank (`replace`, sent by `bankSync`, again if the network drops).
- **Reset progress** goes through the server for a banked device (offline: nothing changes, and it says so).

**On the server** (migration `20261118023024_bank.sql`):
- `bank` (one row per player: `gp`, `coll`, `packs`, `free_pack`, `rev`) and `bank_log` (one line per change, keyed by `ref`: `bot:<seed>`, `online:<match>`, `pack:<nonce>`, `free`, `open`, `replace:<time>`, `reset:<time>`, so nothing happens twice). Players may read their own `bank` row; nothing else.
- Functions for the `duel` function only (the secret key): `bank_get`, `bank_progress` (the account's cloud save), `bank_open`, `bank_apply` (one change, refused if GP would go below 0, the free pack twice, or the 201st bot duel in a day), `bank_set` (replace or reset), `bank_pay_duel`; and `duel_append` now pays a finished online duel.
- The `duel` function's new requests: `bank { snapshot? }`, `earn { claims }`, `pack { ref } | { free: true }`, `replace { snapshot }`, `reset` (the header of `server/supabase/functions/duel/index.js` lists them). Its copy of `web/packs.js` and the Set 1 list (`server/set1.json`, refreshed by `server/build.js` from `arena_assets.js` like `rarity.json`) go in `gen/`.

**Tested:**
- `node server/sql_test.mjs`: every migration applied in order to a real Postgres in node (PGlite), with stand-ins for Supabase's roles and `auth`; then opening once, paying once, packs (retried: the same cards, paid once), not enough GP, the free pack once, the daily cap, reset, online duels paid for a win, a concede and a draw, a stale move not paying again, and what a player may touch (their own row, read only).
- `node server/test_local.mjs`: 40 online duels, each checked to pay both players right (every ending came up: 0, 50 and 100); and the bank requests: opening from a cloud save, an honest device and a doubtful one, bot duels (repeats and bad ones refused), packs (6 real cards of the right rarities, 200 GP, retried), the free pack, replace, reset, a deck you don't own refused, no bank no challenge.
- `node web/test/ui/bank.js`: the page: Pat's offline progress opens her bank; packs from the server (and offline: explained, nothing spent, the same pack ref kept); the free pack; cards and GP handed out in the browser taken back; a bot duel reported; reset; Quinn's progress that doesn't add up. Screenshots in `web/test/ui/shots/bank/`.
- `node web/test/ui/accounts.js` (reworked: devices with a bank earn through it, and GP and cards are checked at the bank, not in the cloud save) and `node web/test/ui/online.js` (Bob's extra cards go in at the server) pass.

**Not done yet:** Find a match (phase 4). Bot duels are believed (Ramon's call); a determined cheater can still report wins they didn't play, up to 200 a day.

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
3. **Accounts and cloud progress (built 2026-11-16; see "Phase 3 (accounts): what's built"):**
   - **Signing in:** Google or an emailed code, linked to the name you already have.
   - **Progress in the account:** GP, collection, decks, packs and tutorial state, so PC and phone share progress; a second device with progress of its own asks which to keep.
   - **Set up and live for Google (2026-11-17).** Email waits on a sender.
3b. **The server as the bank (built 2026-11-17; see "Phase 3b (the bank): what's built"):** GP, cards and packs kept on the server, online duels paid there, packs opened there, online decks checked against the collection.
4. **Find a match:** the queue, players online, emotes, blocking, and recent opponents you can challenge again.

**Later:**
- a friends list with who's online;
- challenge notifications;
- a ranked ladder.

## Still open for Ramon

- **Accounts (phase 3):** a real try on a second device (the PC) with the same Google account; publishing the consent screen when friends should sign in. And for email: which address the codes should come from (a Gmail mailbox, or a domain of his own).
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
