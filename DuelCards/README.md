# Duel Cards

The RuneScape Duel Cards game, packaged as an Android app. Ten cards, five stats, one Mithril AI that
does not respect you.

## Install

1. On your phone, download **[DuelCards.apk](https://github.com/RamonG3/Misc./releases/download/duelcards-latest/DuelCards.apk)**
   (GitHub Actions rebuilds it on every push).
2. Open it and allow installing from your browser if Android asks.
3. Tap **Duel Cards** in your app drawer.

## How it works

The game is a single web page, `app/src/main/assets/game.html`, played in a full-screen WebView.
Everything ships inside the APK, so it plays offline.

- The page asks Google Fonts for Cinzel and Alegreya Sans; the app answers with copies bundled in
  `assets/fonts` (SIL Open Font License).
- `assets/android-bridge.js` is added to the page when it loads. It pauses the music when you leave the
  app, lets the back button close the open dialog, and stops long-presses from selecting text.
- Your settings and win/loss record are kept in the page's localStorage, so they survive restarts.
- Back with no dialog open sends the app to the background without ending the duel.

## Updating the game

Replace `app/src/main/assets/game.html` with the new export (unchanged, same name) and push.
CI builds a new APK that installs over the old one and keeps your record.
