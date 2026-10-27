// Glue between the Android app and the game page. MainActivity loads it ahead of the game's scripts.
(function () {
  'use strict';

  // Keep track of every AudioContext the game creates so the app can silence it in the background.
  const contexts = [];
  const Native = window.AudioContext || window.webkitAudioContext;
  if (Native) {
    class TrackedAudioContext extends Native {
      constructor(options) { super(options); contexts.push(this); }
    }
    window.AudioContext = TrackedAudioContext;
    if (window.webkitAudioContext) window.webkitAudioContext = TrackedAudioContext;
  }
  const silenced = new Set();

  window.duelAndroid = {
    // App left the screen.
    pause() {
      for (const c of contexts) if (c.state === 'running') { silenced.add(c); c.suspend(); }
    },
    // App came back: pick the music up where it stopped.
    resume() {
      for (const c of silenced) c.resume();
      silenced.clear();
    },
    // Back button closes the open dialog. Returns false when there is none, so the app can step aside.
    back() {
      const open = document.querySelectorAll('dialog[open]');
      if (!open.length) return false;
      const d = open[open.length - 1];
      if (d.close) d.close(); else d.removeAttribute('open');
      return true;
    },
  };

  // Long-presses should feel like an app, not select text on a web page.
  const style = document.createElement('style');
  style.textContent = 'html { -webkit-user-select: none; user-select: none; -webkit-touch-callout: none; }';
  document.head.appendChild(style);
})();
