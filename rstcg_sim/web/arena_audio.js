// RuneScape TCG Arena: music and sound, synthesized live with the Web Audio API.
// The tunes are original, written in the spirit of a medieval-fantasy MMO score: modal melodies on
// flute and harp over strings, tavern lutes, war drums and brass for battle. The one thing that is
// recorded, not synthesized, is the casting sound of each spell that has one (see "Recorded sounds").
//
//   RSAudio.unlock()            start audio after the first click / key / pad press (browser rule)
//   RSAudio.want('menu'|'duel'|'quiet')   what should be playing; idempotent, call as often as you like
//   RSAudio.intense(true)       battle music: bring in the intense layer (either deck at 10 or less)
//   RSAudio.sting(name)         'victory' | 'defeat' | 'claim' | 'quest' | 'legendary'
//   RSAudio.sfx(name, opts)     one-shot sound effects
//   RSAudio.clip(name, opts)    a recorded sound, by card name; false if there is none (make the usual sound instead)
//   RSAudio.volumes({ music, sfx })   0..1 each
window.RSAudio = (() => {
  let ctx = null, master = null, musicBus = null, sfxBus = null, revIn = null, fxRevIn = null, noiseBuf = null;
  const vol = { music: 0.55, sfx: 0.8 };
  const A4 = 440;

  // ---------------------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------------------
  function impulse(c, secs, decay) {
    const len = Math.floor(c.sampleRate * secs), buf = c.createBuffer(2, len, c.sampleRate);
    for (let ch = 0; ch < 2; ch++) {
      const d = buf.getChannelData(ch);
      for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, decay);
    }
    return buf;
  }
  function build(c) {                      // the mixer: music and effects buses, a shared reverb, a gentle limiter
    const m = c.createGain(); m.gain.value = 0.76;
    const comp = c.createDynamicsCompressor();
    comp.threshold.value = -12; comp.knee.value = 8; comp.ratio.value = 4; comp.attack.value = 0.005; comp.release.value = 0.2;
    m.connect(comp); comp.connect(c.destination);
    const mus = c.createGain(); mus.gain.value = vol.music; mus.connect(m);
    const fx = c.createGain(); fx.gain.value = vol.sfx; fx.connect(m);
    // Two reverbs, one behind each bus, so each echo follows its own volume. (2026-11-18: there was one, straight
    // into the master, so with the music turned off the melody's echo still played on.) The effects' is shorter.
    const rv = c.createConvolver(); rv.buffer = impulse(c, 2.8, 2.4);
    const rin = c.createGain(); const rout = c.createGain(); rout.gain.value = 0.5;
    rin.connect(rv); rv.connect(rout); rout.connect(mus);
    const frv = c.createConvolver(); frv.buffer = impulse(c, 1.4, 2.6);
    const frin = c.createGain(); const frout = c.createGain(); frout.gain.value = 0.5;
    frin.connect(frv); frv.connect(frout); frout.connect(fx);
    const nb = c.createBuffer(1, c.sampleRate * 2, c.sampleRate), nd = nb.getChannelData(0);
    for (let i = 0; i < nd.length; i++) nd[i] = Math.random() * 2 - 1;
    return { master: m, musicBus: mus, sfxBus: fx, revIn: rin, fxRevIn: frin, noiseBuf: nb };
  }
  function use(c, b) { ctx = c; master = b.master; musicBus = b.musicBus; sfxBus = b.sfxBus; revIn = b.revIn; fxRevIn = b.fxRevIn; noiseBuf = b.noiseBuf; }
  const hz = (midi) => A4 * Math.pow(2, (midi - 69) / 12);
  function osc(type, f, t) { const o = ctx.createOscillator(); o.type = type; o.frequency.setValueAtTime(f, t); return o; }
  function gainNode(v = 1) { const g = ctx.createGain(); g.gain.value = v; return g; }
  function filt(type, f, q = 0.7) { const b = ctx.createBiquadFilter(); b.type = type; b.frequency.value = f; b.Q.value = q; return b; }
  function noise(t, dur) { const s = ctx.createBufferSource(); s.buffer = noiseBuf; s.loop = true; s.start(t, Math.random() * 1.5); s.stop(t + dur + 0.05); return s; }
  function out(node, dest, rev, pan = 0) {          // to a bus, with a reverb send and an optional pan
    let n = node;
    if (pan && ctx.createStereoPanner) { const p = ctx.createStereoPanner(); p.pan.value = pan; n.connect(p); n = p; }
    n.connect(dest);
    if (rev) { const s = gainNode(rev); n.connect(s); s.connect(dest === sfxBus ? fxRevIn : revIn); }
  }
  function env(g, t, a, peak, d, sus, endT, rel) {  // attack-decay-sustain-release on a gain
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(peak, t + a);
    g.gain.setTargetAtTime(peak * sus, t + a, d);
    g.gain.setValueAtTime(peak * sus, Math.max(t + a, endT));
    g.gain.setTargetAtTime(0.0001, Math.max(t + a, endT), rel);
  }

  // ---------------------------------------------------------------------------------
  // Instruments: each plays one note (f in Hz) at time t for d seconds
  // ---------------------------------------------------------------------------------
  const I = {
    harp(t, f, d, v, dest, pan) {
      const g = gainNode(0), lp = filt('lowpass', Math.min(12000, f * 7), 0.8);
      const a = osc('triangle', f, t), b = osc('sine', f * 2, t), bg = gainNode(0.25);
      a.connect(lp); b.connect(bg); bg.connect(lp); lp.connect(g);
      lp.frequency.setTargetAtTime(f * 2.5, t, 0.25);
      const dec = Math.min(2.2, 0.6 + d * 0.6);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.32, t + 0.004); g.gain.setTargetAtTime(0.0001, t + 0.01, dec / 4);
      a.start(t); b.start(t); a.stop(t + dec + 0.2); b.stop(t + dec + 0.2);
      out(g, dest, 0.35, pan);
    },
    lute(t, f, d, v, dest, pan) {
      const g = gainNode(0), lp = filt('lowpass', f * 6, 2);
      const a = osc('sawtooth', f, t), b = osc('square', f * 1.003, t), bg = gainNode(0.3);
      a.connect(lp); b.connect(bg); bg.connect(lp); lp.connect(g);
      lp.frequency.setTargetAtTime(f * 1.6, t, 0.08);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.2, t + 0.003); g.gain.setTargetAtTime(0.0001, t + 0.01, 0.16 + d * 0.1);
      a.start(t); b.start(t); a.stop(t + 1.2); b.stop(t + 1.2);
      out(g, dest, 0.22, pan);
    },
    flute(t, f, d, v, dest, pan) {
      const g = gainNode(0), lp = filt('lowpass', f * 4, 0.5);
      const a = osc('sine', f, t), b = osc('triangle', f, t), bg = gainNode(0.22);
      const lfo = osc('sine', 5.2, t), lg = gainNode(0);
      lfo.connect(lg); lg.connect(a.frequency); lg.connect(b.frequency);
      lg.gain.setValueAtTime(0, t); lg.gain.linearRampToValueAtTime(f * 0.007, t + Math.min(0.45, d * 0.6) + 0.05);
      a.connect(lp); b.connect(bg); bg.connect(lp); lp.connect(g);
      env(g, t, 0.06, v * 0.3, 0.2, 0.85, t + d - 0.05, 0.07);
      const n = noise(t, 0.12), nb = filt('bandpass', f * 2, 1.2), ng = gainNode(0);
      ng.gain.setValueAtTime(v * 0.06, t); ng.gain.setTargetAtTime(0.0001, t + 0.02, 0.04);
      n.connect(nb); nb.connect(ng); ng.connect(g);
      a.start(t); b.start(t); lfo.start(t); const e = t + d + 0.5; a.stop(e); b.stop(e); lfo.stop(e);
      out(g, dest, 0.4, pan);
    },
    strings(t, f, d, v, dest, pan) {
      const g = gainNode(0), lp = filt('lowpass', Math.min(5000, 900 + f * 2.2), 0.6);
      const os = [-7, 0, 6].map((c) => { const o = osc('sawtooth', f * Math.pow(2, c / 1200), t); o.connect(lp); return o; });
      lp.connect(g);
      env(g, t, Math.min(0.35, d * 0.4), v * 0.075, 0.5, 0.9, t + d, 0.35);
      os.forEach((o) => { o.start(t); o.stop(t + d + 1.6); });
      out(g, dest, 0.45, pan);
    },
    stacc(t, f, d, v, dest, pan) {         // short bowed strings for ostinatos
      const g = gainNode(0), lp = filt('lowpass', 2400 + f, 0.9);
      const os = [-5, 5].map((c) => { const o = osc('sawtooth', f * Math.pow(2, c / 1200), t); o.connect(lp); return o; });
      lp.connect(g);
      env(g, t, 0.012, v * 0.11, 0.06, 0.5, t + Math.min(d, 0.2), 0.05);
      os.forEach((o) => { o.start(t); o.stop(t + 0.6); });
      out(g, dest, 0.25, pan);
    },
    brass(t, f, d, v, dest, pan) {
      const g = gainNode(0), lp = filt('lowpass', 300, 1.5);
      const os = [-4, 4].map((c) => { const o = osc('sawtooth', f * Math.pow(2, c / 1200), t); o.connect(lp); return o; });
      lp.connect(g);
      lp.frequency.setValueAtTime(260, t); lp.frequency.linearRampToValueAtTime(Math.min(6000, f * 5), t + 0.06); lp.frequency.setTargetAtTime(Math.min(4000, f * 3), t + 0.07, 0.2);
      env(g, t, 0.045, v * 0.12, 0.25, 0.8, t + d - 0.04, 0.09);
      os.forEach((o) => { o.start(t); o.stop(t + d + 0.6); });
      out(g, dest, 0.35, pan);
    },
    choir(t, f, d, v, dest, pan) {
      const g = gainNode(0), mix = gainNode(1);
      const os = [-9, 0, 8].map((c) => { const o = osc('sawtooth', f * Math.pow(2, c / 1200), t); o.connect(mix); return o; });
      const f1 = filt('bandpass', 750, 6), f2 = filt('bandpass', 1150, 7), f3 = filt('lowpass', 500, 0.5), fg = gainNode(0.6);
      mix.connect(f1); mix.connect(f2); mix.connect(f3); f1.connect(g); f2.connect(g); f3.connect(fg); fg.connect(g);
      env(g, t, Math.min(0.6, d * 0.4), v * 0.22, 0.6, 0.9, t + d, 0.5);
      os.forEach((o) => { o.start(t); o.stop(t + d + 2.5); });
      out(g, dest, 0.6, pan);
    },
    bells(t, f, d, v, dest, pan) {
      const g = gainNode(0);
      [[1, 1], [2.76, 0.32], [5.4, 0.12], [8.9, 0.05]].forEach(([r, a]) => {
        const o = osc('sine', f * r, t), og = gainNode(a); o.connect(og); og.connect(g); o.start(t); o.stop(t + 2.6);
      });
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.14, t + 0.003); g.gain.setTargetAtTime(0.0001, t + 0.01, 0.45);
      out(g, dest, 0.5, pan);
    },
    bass(t, f, d, v, dest) {
      const g = gainNode(0), lp = filt('lowpass', 700, 1);
      const a = osc('triangle', f, t), b = osc('sine', f / 2, t), bg = gainNode(0.5);
      a.connect(lp); b.connect(bg); bg.connect(lp); lp.connect(g);
      env(g, t, 0.01, v * 0.38, 0.25, 0.55, t + d - 0.03, 0.06);
      a.start(t); b.start(t); a.stop(t + d + 0.5); b.stop(t + d + 0.5);
      out(g, dest, 0.05);
    },
    squeeze(t, f, d, v, dest, pan) {        // a little accordion for the tavern
      const g = gainNode(0), lp = filt('lowpass', 2200, 0.7), trem = osc('sine', 5.5, t), tg = gainNode(0.25);
      const os = [-6, 6].map((c) => { const o = osc('square', f * Math.pow(2, c / 1200), t); o.connect(lp); return o; });
      const vca = gainNode(0.75); trem.connect(tg); tg.connect(vca.gain);
      lp.connect(vca); vca.connect(g);
      env(g, t, 0.05, v * 0.06, 0.3, 0.9, t + d - 0.03, 0.08);
      os.forEach((o) => { o.start(t); o.stop(t + d + 0.6); }); trem.start(t); trem.stop(t + d + 0.6);
      out(g, dest, 0.25, pan);
    },
    timp(t, f, d, v, dest) {
      const g = gainNode(0), a = osc('sine', f * 1.08, t);
      a.frequency.exponentialRampToValueAtTime(f, t + 0.12);
      a.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.6, t + 0.006); g.gain.setTargetAtTime(0.0001, t + 0.02, 0.38);
      a.start(t); a.stop(t + 2);
      const n = noise(t, 0.12), nl = filt('lowpass', 900), ng = gainNode(0);
      ng.gain.setValueAtTime(v * 0.25, t); ng.gain.setTargetAtTime(0.0001, t, 0.03); n.connect(nl); nl.connect(ng); ng.connect(g);
      out(g, dest, 0.35);
    },
  };
  // drums: (t, v, dest)
  const D = {
    kick(t, v, dest) {                      // a deep war drum
      const g = gainNode(0), a = osc('sine', 120, t); a.frequency.exponentialRampToValueAtTime(42, t + 0.22); a.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.95, t + 0.004); g.gain.setTargetAtTime(0.0001, t + 0.03, 0.13);
      a.start(t); a.stop(t + 0.8);
      const n = noise(t, 0.04), nl = filt('lowpass', 1500), ng = gainNode(v * 0.18); ng.gain.setTargetAtTime(0.0001, t, 0.01);
      n.connect(nl); nl.connect(ng); ng.connect(g);
      out(g, dest, 0.15);
    },
    tom(t, v, dest, p = 0) {
      const g = gainNode(0), a = osc('sine', 210 - p * 40, t); a.frequency.exponentialRampToValueAtTime(95 - p * 15, t + 0.25); a.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.6, t + 0.004); g.gain.setTargetAtTime(0.0001, t + 0.02, 0.12);
      a.start(t); a.stop(t + 0.7);
      out(g, dest, 0.25, p ? 0.25 : -0.2);
    },
    snare(t, v, dest) {
      const g = gainNode(0), n = noise(t, 0.25), hp = filt('highpass', 1200), bp = filt('bandpass', 3200, 0.8);
      n.connect(hp); hp.connect(bp); bp.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.42, t + 0.002); g.gain.setTargetAtTime(0.0001, t + 0.01, 0.055);
      const b = osc('triangle', 190, t), bg = gainNode(0); b.connect(bg); bg.connect(g);
      bg.gain.setValueAtTime(v * 0.5, t); bg.gain.setTargetAtTime(0.0001, t, 0.03); b.start(t); b.stop(t + 0.2);
      out(g, dest, 0.2, 0.1);
    },
    hat(t, v, dest) {
      const g = gainNode(0), n = noise(t, 0.08), hp = filt('highpass', 7500);
      n.connect(hp); hp.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.16, t + 0.002); g.gain.setTargetAtTime(0.0001, t + 0.004, 0.018);
      out(g, dest, 0.05, 0.3);
    },
    shaker(t, v, dest) {
      const g = gainNode(0), n = noise(t, 0.12), bp = filt('bandpass', 6500, 1.5);
      n.connect(bp); bp.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.2, t + 0.02); g.gain.setTargetAtTime(0.0001, t + 0.03, 0.025);
      out(g, dest, 0.08, -0.3);
    },
    tamb(t, v, dest) {
      const g = gainNode(0), n = noise(t, 0.25), bp = filt('bandpass', 9000, 2.5), hp = filt('highpass', 5000);
      n.connect(hp); hp.connect(bp); bp.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.35, t + 0.003); g.gain.setTargetAtTime(0.0001, t + 0.01, 0.06);
      out(g, dest, 0.15, 0.35);
    },
    frame(t, v, dest) {                     // hand-played frame drum
      const g = gainNode(0), a = osc('sine', 150, t); a.frequency.exponentialRampToValueAtTime(85, t + 0.18); a.connect(g);
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v * 0.55, t + 0.004); g.gain.setTargetAtTime(0.0001, t + 0.02, 0.1);
      a.start(t); a.stop(t + 0.6);
      const n = noise(t, 0.05), nl = filt('bandpass', 900, 0.8), ng = gainNode(v * 0.2); ng.gain.setTargetAtTime(0.0001, t, 0.015);
      n.connect(nl); nl.connect(ng); ng.connect(g);
      out(g, dest, 0.25, -0.15);
    },
  };

  // ---------------------------------------------------------------------------------
  // Writing music: note names, chords, patterns -> a list of events in beats
  // ---------------------------------------------------------------------------------
  const PC = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
  function midi(name) {                    // 'C#5', 'Bb3'
    const m = /^([A-G])([#b]?)(-?\d)$/.exec(name);
    return 12 * (+m[3] + 1) + PC[m[1]] + (m[2] === '#' ? 1 : m[2] === 'b' ? -1 : 0);
  }
  const dur = (s) => (s.includes('/') ? +s.split('/')[0] / +s.split('/')[1] : +s);
  const QUAL = { '': [0, 4, 7], m: [0, 3, 7], 7: [0, 4, 7, 10], m7: [0, 3, 7, 10], sus4: [0, 5, 7], 5: [0, 7, 12], dim: [0, 3, 6] };
  function chord(sym, oct) {               // 'Dm' at octave 3 -> [D3, F3, A3]
    const m = /^([A-G][#b]?)(.*)$/.exec(sym);
    const root = midi(m[1] + oct);
    return (QUAL[m[2]] || QUAL['']).map((i) => root + i);
  }
  function S(spec) {                       // a song: {bpm, beats (per bar), bars, layers {name: [events]}}
    const ev = [];
    const put = (layer, beat, inst, note, d, v, pan) => ev.push({ layer, beat, inst, note, d, v, pan });
    const api = {
      mel(layer, inst, str, start = 0, v = 0.8, oct = 0, pan = 0) {
        let b = start;
        for (const tok of str.trim().split(/\s+/)) {
          if (tok === '|') continue;
          const [n, ds] = tok.split(':'); const d = dur(ds);
          if (n !== 'r') put(layer, b, inst, midi(n) + oct * 12, d, v, pan);
          b += d;
        }
        return b;
      },
      pad(layer, inst, prog, bpc, oct, v = 0.7, pan = 0) { prog.forEach((c, k) => chord(c, oct).forEach((n) => put(layer, k * bpc, inst, n, bpc, v, pan))); },
      arp(layer, inst, prog, bpc, oct, pat, step, v = 0.7, pan = 0) {
        prog.forEach((c, k) => {
          const ch = chord(c, oct), tones = [...ch, ...ch.map((n) => n + 12), ...ch.map((n) => n + 24)];
          for (let s = 0; s * step < bpc - 1e-6; s++) {
            const i = pat[s % pat.length];
            if (i === null) continue;
            put(layer, k * bpc + s * step, inst, tones[i], step * 1.6, v * (s % pat.length === 0 ? 1 : 0.8), pan);
          }
        });
      },
      bass(layer, inst, prog, bpc, oct, rhythm, v = 0.8) {   // rhythm: [[offset, dur, 0 root | 1 fifth | 2 octave]]
        prog.forEach((c, k) => {
          const ch = chord(c, oct);
          for (const [o, d, w] of rhythm) if (o < bpc) put(layer, k * bpc + o, inst, w === 1 ? ch[0] + 7 : w === 2 ? ch[0] + 12 : ch[0], d, v);
        });
      },
      stab(layer, inst, prog, bpc, oct, offsets, d, v = 0.8) { prog.forEach((c, k) => offsets.forEach((o) => { if (o < bpc) chord(c, oct).forEach((n) => put(layer, k * bpc + o, inst, n, d, v)); })); },
      drums(layer, bars, beatsPerBar, steps, pats, v = 0.8) {  // pats: {kick: 'x...x...', ...} one bar each (or several joined by '|')
        for (const [inst, p] of Object.entries(pats)) {
          const barsP = p.split('|');
          for (let b = 0; b < bars; b++) {
            const pp = barsP[b % barsP.length];
            for (let s = 0; s < pp.length; s++) {
              const ch = pp[s];
              if (ch === '.') continue;
              put(layer, b * beatsPerBar + (s * beatsPerBar) / steps, inst, null, 0, v * (ch === 'X' ? 1.15 : ch === 'o' ? 0.55 : 0.85));
            }
          }
        }
      },
      timp(layer, prog, bpc, oct, offsets, v = 0.8) { prog.forEach((c, k) => offsets.forEach((o) => { if (o < bpc) put(layer, k * bpc + o, 'timp', chord(c, oct)[0], 1, v); })); },
    };
    spec.write(api);
    ev.sort((a, b) => a.beat - b.beat);
    return { name: spec.name, kind: spec.kind, bpm: spec.bpm, beats: spec.beats, bars: spec.bars, length: spec.beats * spec.bars, events: ev, mix: spec.mix || {}, loops: spec.loops || 2 };
  }

  // ---------------------------------------------------------------------------------
  // The score (original compositions)
  // ---------------------------------------------------------------------------------
  const SONGS = {};
  // --- Idle 1: a pastoral morning, flute over harp (G major)
  SONGS.meadow = S({ name: 'Morning in the Meadow', kind: 'idle', bpm: 84, beats: 4, bars: 16, write(s) {
    const prog = ['G', 'D', 'Em', 'C', 'G', 'Am', 'C', 'D', 'Em', 'C', 'G', 'D', 'C', 'D', 'G', 'G'];
    s.mel('base', 'flute', `D5:1.5 B4:.5 G4:1 A4:1 | B4:1.5 A4:.5 F#4:2 | G4:1 B4:1 E5:1.5 D5:.5 | C5:1 B4:.5 A4:.5 G4:2 |
      D5:1.5 E5:.5 D5:1 B4:1 | C5:1.5 B4:.5 A4:2 | E5:1 D5:.5 C5:.5 B4:1 A4:1 | A4:3 r:1 |
      B4:1 E5:1 G5:1.5 F#5:.5 | E5:1 D5:1 C5:2 | B4:1 D5:1 G5:1.5 F#5:.5 | E5:1 D5:1 A4:2 |
      G4:1 C5:1 E5:1 D5:1 | C5:1 B4:1 A4:1 F#4:1 | G4:2 B4:1 D5:1 | G4:3 r:1`, 0, 0.75);
    s.arp('base', 'harp', prog, 4, 3, [0, 1, 2, 3, 4, 3, 2, 1], 0.5, 0.6, -0.25);
    s.pad('base', 'strings', prog, 4, 3, 0.5, 0.2);
    s.bass('base', 'bass', prog, 4, 2, [[0, 1.8, 0], [2, 1.8, 1]], 0.55);
    s.drums('base', 16, 4, 8, { shaker: '..o...o.' }, 0.5);
  } });
  // --- Idle 2: the tavern, a jig on lute and squeezebox (A mixolydian, 6/8 felt as 2 beats)
  SONGS.tavern = S({ name: 'The Rusty Tankard', kind: 'idle', bpm: 100, beats: 2, bars: 16, loops: 4, write(s) {
    const prog = ['A', 'G', 'A', 'E', 'A', 'G', 'D', 'E', 'A', 'G', 'A', 'E', 'A', 'G', 'D', 'A'];
    const half = `A4:1/3 C#5:1/3 E5:1/3 A5:2/3 E5:1/3 | D5:1/3 B4:1/3 G4:1/3 B4:2/3 D5:1/3 | C#5:1/3 E5:1/3 A5:1/3 G5:1/3 F#5:1/3 E5:1/3 | D5:2/3 B4:1/3 G#4:1 |
      A4:1/3 C#5:1/3 E5:1/3 A5:1/3 B5:1/3 C#6:1/3 | B5:2/3 G5:1/3 D5:2/3 B4:1/3 | A4:1/3 D5:1/3 F#5:1/3 E5:1/3 D5:1/3 C#5:1/3 |`;
    s.mel('base', 'lute', `${half} B4:1 r:1 | ${half} A4:1 r:1`, 0, 0.85, 0, 0.15);
    s.stab('base', 'squeeze', prog, 2, 4, [0, 1], 2 / 3, 0.55);
    s.bass('base', 'bass', prog, 2, 2, [[0, 2 / 3, 0], [1, 2 / 3, 1]], 0.6);
    s.drums('base', 16, 2, 6, { frame: 'x..o..', tamb: '..x..x' }, 0.6);
  } });
  // --- Idle 3: the wizard's study, bells and a reedy flute over strings (A minor, 3/4)
  SONGS.study = S({ name: "The Wizard's Study", kind: 'idle', bpm: 72, beats: 3, bars: 16, write(s) {
    const prog = ['Am', 'F', 'G', 'Em', 'Am', 'Dm', 'E', 'E', 'Am', 'F', 'G', 'Em', 'Am', 'Dm', 'E', 'Am'];
    s.mel('base', 'flute', `E5:2 C5:1 | A4:1.5 C5:.5 F5:1 | D5:2 B4:1 | E5:3 | A5:1.5 G5:.5 E5:1 | F5:1 E5:1 D5:1 | B4:1 C5:1 D5:1 | G#4:2 r:1 |
      C5:1 E5:1 A5:1 | G5:1.5 F5:.5 C5:1 | B4:1 D5:1 G5:1 | E5:2 r:1 | A5:1 G5:1 E5:1 | D5:1 F5:1 A5:1 | G#5:1.5 F5:.5 D5:1 | A5:3`, 0, 0.7, -1);
    s.arp('base', 'bells', prog, 3, 4, [0, 2, 4, 2, 1, 2], 0.5, 0.55, 0.3);
    s.pad('base', 'strings', prog, 3, 3, 0.55, -0.2);
    s.bass('base', 'bass', prog, 3, 2, [[0, 2.8, 0]], 0.5);
  } });

  // --- Combat 1: clash of steel (D minor, 128). Intense: snare, brass stabs, timpani, choir, doubled melody
  SONGS.steel = S({ name: 'Clash of Steel', kind: 'combat', bpm: 128, beats: 4, bars: 16, loops: 99, write(s) {
    const prog = ['Dm', 'Dm', 'Bb', 'C', 'Dm', 'Dm', 'Gm', 'A', 'Dm', 'F', 'Bb', 'C', 'Gm', 'Bb', 'A', 'A'];
    const mel = `D4:1 A4:1.5 G4:.5 F4:1 | E4:.5 F4:.5 G4:1 A4:2 | Bb4:1.5 A4:.5 G4:1 F4:1 | G4:3 r:1 | D5:1 C5:.5 Bb4:.5 A4:2 | F4:1 G4:1 A4:2 |
      Bb4:1 A4:1 G4:1 Bb4:1 | A4:3 r:1 | D5:1.5 E5:.5 F5:2 | E5:1 D5:1 C5:2 | D5:1.5 C5:.5 Bb4:2 | C5:1 Bb4:1 A4:1 G4:1 | G4:1 Bb4:1 D5:2 | F5:1 E5:1 D5:2 | C#5:2 E5:2 | A4:3 r:1`;
    s.mel('base', 'brass', mel, 0, 0.75);
    s.arp('base', 'stacc', prog, 4, 3, [0, 0, 2, 0, 1, 0, 2, 1], 0.5, 0.7, -0.2);
    s.bass('base', 'bass', prog, 4, 2, [[0, 0.45, 0], [0.5, 0.45, 0], [1, 0.45, 0], [1.5, 0.45, 2], [2, 0.45, 0], [2.5, 0.45, 0], [3, 0.45, 1], [3.5, 0.45, 0]], 0.6);
    s.drums('base', 16, 4, 8, { kick: 'x...x...|x...x.x.', tom: '......x.', hat: '..o...o.' }, 0.75);
    s.pad('base', 'strings', prog, 4, 3, 0.4);
    // intense
    s.mel('intense', 'strings', mel, 0, 0.6, 1);
    s.stab('intense', 'brass', prog, 4, 3, [0.5, 1.5, 3], 0.35, 0.55);
    s.timp('intense', prog, 4, 2, [0, 2], 0.8);
    s.pad('intense', 'choir', prog, 4, 4, 0.5);
    s.drums('intense', 16, 4, 16, { snare: '....x.......x..x|....x.......x.xx', hat: 'x.x.x.x.x.x.x.x.', kick: '......x.......x.' }, 0.7);
  } });
  // --- Combat 2: an ambush in the Wilderness (E phrygian, 140): driving bass, plucked tremolo, toms
  SONGS.ambush = S({ name: 'Wilderness Ambush', kind: 'combat', bpm: 140, beats: 4, bars: 16, loops: 99, write(s) {
    const prog = ['Em', 'F', 'Em', 'D', 'Em', 'F', 'G', 'F', 'Em', 'F', 'Em', 'D', 'Em', 'F', 'G', 'B'];
    const a = `E5:.5 F5:.5 G5:1 F5:.5 E5:.5 D5:1 | C5:1 F5:1 E5:1 C5:1 | B4:2 E5:2 | D5:1 C5:.5 B4:.5 A4:2 | G5:.5 F5:.5 E5:1 B4:1 E5:1 | A5:1.5 G5:.5 F5:2 | G5:1 F5:1 E5:1 D5:1 |`;
    s.mel('base', 'flute', `${a} E5:3 r:1 | ${a} D#5:2 F#5:2`, 0, 0.75);
    s.arp('base', 'lute', prog, 4, 3, [0, 3, 1, 3, 2, 3, 1, 3], 0.5, 0.55, 0.25);
    s.bass('base', 'bass', prog, 4, 2, [[0, 0.4, 0], [0.5, 0.4, 0], [1, 0.4, 2], [1.5, 0.4, 0], [2, 0.4, 0], [2.5, 0.4, 1], [3, 0.4, 0], [3.5, 0.4, 2]], 0.65);
    s.drums('base', 16, 4, 8, { kick: 'x..x..x.', tom: '....x..x|..x.x..x', shaker: 'o.o.o.o.' }, 0.7);
    s.mel('intense', 'brass', `${a} E5:3 r:1 | ${a} D#5:2 F#5:2`, 0, 0.5, -1);
    s.stab('intense', 'stacc', prog, 4, 4, [0, 0.75, 1.5, 2, 2.75, 3.5], 0.2, 0.6);
    s.timp('intense', prog, 4, 2, [0, 1.5, 3], 0.75);
    s.pad('intense', 'choir', prog, 4, 4, 0.45);
    s.drums('intense', 16, 4, 16, { snare: '....x..x....x...|....x..x....x.xx', kick: 'x.....x...x.....', hat: 'xxxxxxxxxxxxxxxx' }, 0.65);
  } });
  // --- Combat 3: an arcane duel (A minor, 6/8 felt as 2 beats): bells and harp triplets, brass and choir when it's close
  SONGS.arcane = S({ name: 'Arcane Duel', kind: 'combat', bpm: 76, beats: 2, bars: 16, loops: 99, write(s) {
    const prog = ['Am', 'G', 'F', 'E', 'Am', 'G', 'F', 'E', 'Dm', 'Am', 'Dm', 'E', 'Am', 'F', 'E', 'E'];
    const mel = `E5:1 A5:2/3 G5:1/3 | F5:2/3 E5:1/3 D5:1 | C5:2/3 D5:1/3 E5:2/3 F5:1/3 | E5:2 | A5:1 B5:2/3 C6:1/3 | B5:2/3 A5:1/3 G5:1 | A5:2/3 G5:1/3 F5:2/3 D5:1/3 | E5:1 G#5:1 |
      F5:1 D5:1 | E5:2/3 C5:1/3 A4:1 | D5:2/3 E5:1/3 F5:2/3 A5:1/3 | G#5:2 | A5:2/3 E5:1/3 C5:2/3 E5:1/3 | F5:2/3 A5:1/3 C6:1 | B5:2/3 G#5:1/3 E5:1 | E5:1 r:1`;
    s.mel('base', 'flute', mel, 0, 0.7);
    s.arp('base', 'harp', prog, 2, 3, [0, 1, 2, 3, 2, 1], 1 / 3, 0.6, -0.3);
    s.arp('base', 'bells', prog, 2, 4, [4, null, null, 2, null, null], 1 / 3, 0.45, 0.35);
    s.pad('base', 'strings', prog, 2, 3, 0.45);
    s.bass('base', 'bass', prog, 2, 2, [[0, 2 / 3, 0], [1, 2 / 3, 1]], 0.6);
    s.drums('base', 16, 2, 6, { frame: 'x..o..|x..x.o', shaker: '.o.o.o' }, 0.65);
    s.mel('intense', 'brass', mel, 0, 0.5, -1);
    s.pad('intense', 'choir', prog, 2, 4, 0.5);
    s.timp('intense', prog, 2, 2, [0, 1], 0.8);
    s.drums('intense', 16, 2, 6, { kick: 'x..x..', snare: '...x..|...x.x', tamb: 'xxxxxx' }, 0.65);
  } });

  // --- Story Mode (2026-11-18): the dialogue, light and dark, and the bosses
  // Tales of Gielinor (F major, 76): harp and flute for Lumbridge, the roads, Draynor Village, the library
  SONGS.story = S({ name: 'Tales of Gielinor', kind: 'story', bpm: 76, beats: 4, bars: 16, loops: 99, write(s) {
    const prog = ['F', 'C', 'Dm', 'Bb', 'F', 'C', 'Bb', 'C', 'Dm', 'Bb', 'F', 'C', 'Bb', 'F', 'Gm', 'C'];
    s.mel('base', 'flute', `C5:2 A4:1 F4:1 | G4:2 E4:1 G4:1 | A4:1.5 Bb4:.5 C5:1 D5:1 | D5:3 r:1 |
      F5:1.5 E5:.5 D5:1 C5:1 | E5:1 D5:1 C5:2 | D5:1 C5:1 Bb4:1 A4:1 | G4:3 r:1 |
      A4:1 D5:1 F5:1.5 E5:.5 | D5:1 Bb4:1 F4:2 | A4:1 C5:1 F5:1.5 G5:.5 | E5:2 C5:2 |
      D5:1.5 C5:.5 Bb4:1 D5:1 | C5:1 A4:1 F4:2 | G4:1 Bb4:1 D5:1 C5:1 | C5:3 r:1`, 0, 0.6);
    s.arp('base', 'harp', prog, 4, 3, [0, 2, 4, 2, 1, 2, 4, 2], 0.5, 0.5, -0.25);
    s.pad('base', 'strings', prog, 4, 3, 0.38, 0.2);
    s.bass('base', 'bass', prog, 4, 2, [[0, 3.8, 0]], 0.45);
  } });
  // Shadows over Draynor (A minor, 60): bells, low strings and a far-off choir, for the manor, the cellar, the circle
  SONGS.storydark = S({ name: 'Shadows over Draynor', kind: 'story', bpm: 60, beats: 4, bars: 16, loops: 99, write(s) {
    const prog = ['Am', 'Am', 'F', 'E', 'Am', 'Dm', 'F', 'E', 'Dm', 'Am', 'Bb', 'E', 'Am', 'F', 'E', 'E'];
    s.mel('base', 'bells', `E5:2 C5:2 | B4:2 A4:2 | C5:1 D5:1 E5:2 | G#4:4 | A4:2 C5:1 E5:1 | F5:2 E5:1 D5:1 | C5:2 A4:2 | B4:3 r:1 |
      D5:2 F5:2 | E5:2 A4:2 | D5:2 F5:1 D5:1 | E5:4 | C5:2 B4:1 A4:1 | A4:2 F4:2 | G#4:2 B4:2 | E4:3 r:1`, 0, 0.8, 0, 0.2);
    s.arp('base', 'harp', prog, 4, 2, [0, 2, 4, 2], 1, 0.6, -0.3);
    s.pad('base', 'strings', prog, 4, 2, 0.65);
    s.pad('base', 'choir', prog, 4, 4, 0.3);
    s.bass('base', 'bass', prog, 4, 2, [[0, 3.8, 0]], 0.7);
    s.timp('base', prog, 4, 2, [0], 0.45);
  } });
  // The Ancient Dark (C minor, 116): the boss fights. Low brass over a driving ostinato; the intense layer
  // brings timpani, a choir and the snare in when either side is low.
  SONGS.boss = S({ name: 'The Ancient Dark', kind: 'combat', bpm: 116, beats: 4, bars: 16, loops: 99, write(s) {
    const prog = ['Cm', 'Cm', 'Ab', 'G', 'Cm', 'Cm', 'Fm', 'G', 'Ab', 'Eb', 'Fm', 'G', 'Cm', 'Ab', 'G', 'G'];
    const mel = `C4:2 G4:1.5 Eb4:.5 | F4:1 Eb4:1 D4:2 | Eb4:1.5 F4:.5 G4:1 C5:1 | B4:3 r:1 | C5:1.5 Bb4:.5 Ab4:1 G4:1 | Eb4:1 F4:1 G4:2 |
      Ab4:1 G4:1 F4:1 Ab4:1 | G4:3 r:1 | C5:1.5 D5:.5 Eb5:2 | D5:1 C5:1 Bb4:2 | C5:1.5 Bb4:.5 Ab4:2 | B4:1 C5:1 D5:1 B4:1 |
      C5:1 G4:1 Eb4:2 | F4:1 Ab4:1 C5:2 | B4:2 D5:2 | G4:3 r:1`;
    s.mel('base', 'brass', mel, 0, 0.72);
    s.arp('base', 'stacc', prog, 4, 3, [0, 0, 1, 0, 2, 0, 1, 0], 0.5, 0.62, -0.2);
    s.bass('base', 'bass', prog, 4, 2, [[0, 0.45, 0], [0.5, 0.45, 0], [1, 0.45, 2], [1.5, 0.45, 0], [2, 0.45, 0], [2.5, 0.45, 0], [3, 0.45, 1], [3.5, 0.45, 2]], 0.62);
    s.drums('base', 16, 4, 8, { kick: 'x...x...|x...x.x.', tom: '......xx|......x.', hat: '..o...o.' }, 0.72);
    s.pad('base', 'strings', prog, 4, 3, 0.36);
    s.mel('intense', 'strings', mel, 0, 0.55, 1);
    s.timp('intense', prog, 4, 2, [0, 2, 3.5], 0.85);
    s.pad('intense', 'choir', prog, 4, 4, 0.45);
    s.stab('intense', 'brass', prog, 4, 3, [0, 1.5, 3], 0.3, 0.5);
    s.drums('intense', 16, 4, 16, { snare: '....x.......x..x|....x.......x.xx', hat: 'x.x.x.x.x.x.x.x.' }, 0.66);
  } });

  // --- Stingers
  SONGS.victory = S({ name: 'Victory', kind: 'sting', bpm: 110, beats: 4, bars: 3, write(s) {
    s.mel('base', 'brass', 'C4:.5 E4:.5 G4:.5 C5:1.5 r:.5 | G4:.5 C5:.5 E5:3 r:1', 0, 0.9);
    s.mel('base', 'brass', 'E4:.5 G4:.5 C5:.5 E5:1.5 r:.5 | C5:.5 E5:.5 G5:3', 0, 0.7);
    s.pad('base', 'strings', ['C', 'C', 'C'], 4, 3, 0.7);
    s.timp('base', ['C', 'G', 'C'], 4, 2, [0, 2.5], 0.9);
    s.drums('base', 3, 4, 8, { snare: '....xxxx|x.......|........' }, 0.7);
    s.arp('base', 'harp', ['C', 'C', 'C'], 4, 4, [0, 1, 2, 3, 4, 5, 6, 7], 0.25, 0.6);
  } });
  SONGS.defeat = S({ name: 'Defeat', kind: 'sting', bpm: 66, beats: 4, bars: 3, write(s) {
    s.mel('base', 'flute', 'A4:1 G4:1 F4:1 E4:1 | D4:2 E4:1 C4:1 | A3:4', 0, 0.7);
    s.pad('base', 'strings', ['Am', 'Dm', 'Am'], 4, 3, 0.6);
    s.timp('base', ['Am', 'Dm', 'Am'], 4, 2, [0], 0.6);
  } });
  SONGS.claim = S({ name: 'Cape', kind: 'sting', bpm: 120, beats: 4, bars: 2, write(s) {
    s.mel('base', 'brass', 'D4:.5 F#4:.5 A4:.5 D5:2.5 r:4', 0, 0.85);
    s.pad('base', 'strings', ['D', 'D'], 4, 3, 0.55);
    s.timp('base', ['D', 'D'], 4, 2, [0, 1.5], 0.8);
  } });
  SONGS.quest = S({ name: 'Quest', kind: 'sting', bpm: 120, beats: 4, bars: 2, write(s) {
    s.mel('base', 'bells', 'G4:.5 C5:.5 E5:.5 G5:.5 E5:.5 C6:2.5 r:4', 0, 0.8);
    s.mel('base', 'harp', 'C4:.5 E4:.5 G4:.5 C5:.5 G4:.5 E5:2.5', 0, 0.7);
  } });
  SONGS.legendary = S({ name: 'Legendary', kind: 'sting', bpm: 80, beats: 4, bars: 2, write(s) {
    s.pad('base', 'choir', ['D', 'D'], 4, 4, 0.8);
    s.pad('base', 'strings', ['D', 'A'], 4, 3, 0.6);
    s.timp('base', ['D', 'D'], 4, 2, [0], 1);
    s.arp('base', 'bells', ['D', 'D'], 4, 5, [0, 1, 2, 3, 4, 5, 4, 3], 0.25, 0.5);
  } });

  // ---------------------------------------------------------------------------------
  // The player: lookahead scheduling, layers, crossfades
  // ---------------------------------------------------------------------------------
  // Notes are scheduled this far ahead. The page's own work (a hard bot thinking, a big redraw) can hold the
  // scheduler up for half a second on a phone; with a short lookahead the notes it missed were dropped, and
  // the music stuttered as if it were buffering.
  const LOOKAHEAD = 1.0;
  const players = [];
  const stats = { late: 0, dropped: 0 };          // notes scheduled late / dropped (the page was too busy)
  let current = null, wanted = 'quiet', wantIntense = false, idleOrder = [], lastCombat = null, timer = null;
  function player(song, at, fadeIn = 1.5) {
    const g = gainNode(0), layers = {};
    g.connect(musicBus);
    for (const ev of song.events) if (!layers[ev.layer]) { layers[ev.layer] = gainNode(ev.layer === 'intense' ? (wantIntense ? 1 : 0.0001) : 1); layers[ev.layer].connect(g); }
    g.gain.setValueAtTime(0.0001, at); g.gain.linearRampToValueAtTime(song.kind === 'sting' ? 1 : 0.9, at + fadeIn);
    // intenseUntil: notes of the intense layer after this time aren't played at all while it is off. A silent
    // layer still costs as much to synthesize as a loud one, and on a phone that was most of the music's cost
    // (2026-11-17: the audio thread fell behind and the sound cut out). horizon: how far notes are scheduled.
    const p = { song, g, layers, start: at + 0.05, idx: 0, loop: 0, spb: 60 / song.bpm, done: false,
      intenseUntil: wantIntense ? Infinity : -Infinity, horizon: 0 };
    players.push(p);
    return p;
  }
  function fadeOut(p, secs = 2) {
    if (!p || p.dying) return;
    p.dying = true;
    const t = ctx.currentTime;
    p.g.gain.cancelScheduledValues(t); p.g.gain.setValueAtTime(p.g.gain.value, t); p.g.gain.linearRampToValueAtTime(0.0001, t + secs);
    setTimeout(() => { p.done = true; try { p.g.disconnect(); } catch (e) { /* gone */ } }, secs * 1000 + 300);
  }
  function schedule(p, until) {
    const s = p.song, L = s.length;
    while (!p.done) {
      if (p.idx >= s.events.length) {
        if (s.kind === 'sting' || p.dying) { p.done = true; break; }
        p.idx = 0; p.loop += 1;
        continue;
      }
      const ev = s.events[p.idx], t = p.start + (p.loop * L + ev.beat) * p.spb;
      if (t > until) break;
      p.idx += 1;
      if (ev.layer === 'intense' && t > p.intenseUntil) continue;     // silent layer: not synthesized
      if (t < ctx.currentTime - 0.05) { stats.dropped += 1; continue; }   // fell behind (tab was asleep): skip, don't pile up
      if (t < ctx.currentTime) stats.late += 1;
      play(p, ev, t);
    }
    p.horizon = Math.max(p.horizon, until);
  }
  function play(p, ev, t) {
    const dest = p.layers[ev.layer];
    if (ev.note === null) D[ev.inst](t, ev.v, dest);
    else I[ev.inst](t, hz(ev.note), ev.d * p.spb, ev.v, dest, ev.pan || 0);
  }
  // The intense layer has just come on: play its notes that the scheduler already passed while it was off
  // (up to a second of them), so it fades in complete. Notes it did play (during a fade-out) aren't repeated.
  function catchUp(p, from, skippedAfter) {
    const s = p.song, L = s.length, lo = Math.max(from + 0.02, skippedAfter);
    for (let loop = Math.max(0, p.loop - 1); loop <= p.loop; loop++) {
      for (let i = 0; i < s.events.length; i++) {
        if (loop === p.loop && i >= p.idx) break;                     // not reached yet: schedule() will play it
        const ev = s.events[i];
        if (ev.layer !== 'intense') continue;
        const t = p.start + (loop * L + ev.beat) * p.spb;
        if (t > lo && t <= p.horizon) play(p, ev, t);
      }
    }
  }
  function nextIdle() {
    if (!idleOrder.length) idleOrder = ['meadow', 'tavern', 'study'].sort(() => Math.random() - 0.5);
    return idleOrder.shift();
  }
  function tick() {
    if (!ctx) return;
    const now = ctx.currentTime;
    for (const p of players) schedule(p, now + LOOKAHEAD);
    for (let k = players.length - 1; k >= 0; k--) if (players[k].done) players.splice(k, 1);
    // an idle song gives way to the next after a couple of times round
    if (current && current.song.kind === 'idle' && !current.dying) {
      const end = current.start + current.song.length * current.spb * current.song.loops;
      if (now > end - 3) { const old = current; current = player(SONGS[nextIdle()], now + 0.5, 3); fadeOut(old, 3); }
    }
  }
  // Story Mode's own tracks: the dialogue (light, or dark for the manor and the circle) and the bosses' fight
  const STORY_SONG = { story: 'story', storydark: 'storydark', boss: 'boss' };
  function apply() {                          // make what's playing match what's wanted
    if (!ctx) return;
    const now = ctx.currentTime;
    // music turned off: nothing is played at all (silent notes cost as much to make as loud ones)
    if (wanted === 'quiet' || vol.music <= 0) { fadeOut(current, 1.5); current = null; return; }
    if (STORY_SONG[wanted]) {
      const song = SONGS[STORY_SONG[wanted]];
      if (!current || current.song !== song) { fadeOut(current, 2.5); current = player(song, now + 0.4, 3); }
      return;
    }
    if (wanted === 'menu' && (!current || current.song.kind !== 'idle')) { fadeOut(current, 1.5); current = player(SONGS[nextIdle()], now + 0.3, 2.5); }
    if (wanted === 'duel' && (!current || current.song.kind !== 'combat')) {
      fadeOut(current, 1.2);
      const pool = ['steel', 'ambush', 'arcane'].filter((n) => n !== lastCombat);
      lastCombat = pool[Math.floor(Math.random() * pool.length)];
      current = player(SONGS[lastCombat], now + 0.2, 1.5);
    }
  }
  function setIntense(on) {
    if (wantIntense === on) return;
    wantIntense = on;
    if (!ctx || !current || !current.layers.intense) return;
    const g = current.layers.intense.gain, t = ctx.currentTime;
    g.cancelScheduledValues(t); g.setValueAtTime(Math.max(0.0001, g.value), t);
    if (on) g.linearRampToValueAtTime(1, t + 1.6);        // linear in: an exponential ramp from silence stays silent for most of its length
    else g.exponentialRampToValueAtTime(0.0001, t + 4);
    const was = current.intenseUntil;
    current.intenseUntil = on ? Infinity : t + 4;          // off: keep playing its notes until the fade has finished
    if (on) catchUp(current, t, was);
  }

  // ---------------------------------------------------------------------------------
  // Sound effects
  // ---------------------------------------------------------------------------------
  function swish(t, f0, f1, d, v, pan = 0, q = 1.2) {
    const n = noise(t, d), bp = filt('bandpass', f0, q), g = gainNode(0);
    bp.frequency.setValueAtTime(f0, t); bp.frequency.exponentialRampToValueAtTime(f1, t + d);
    n.connect(bp); bp.connect(g);
    g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v, t + d * 0.35); g.gain.exponentialRampToValueAtTime(0.0001, t + d);
    out(g, sfxBus, 0.15, pan);
  }
  function tone(t, type, f0, f1, d, v, pan = 0, rev = 0.15) {
    const o = osc(type, f0, t), g = gainNode(0);
    if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, t + d);
    o.connect(g);
    g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(v, t + 0.004); g.gain.exponentialRampToValueAtTime(0.0001, t + d);
    o.start(t); o.stop(t + d + 0.05);
    out(g, sfxBus, rev, pan);
  }
  function metal(t, f, d, v, pan = 0) { [[1, 1], [2.32, 0.5], [3.97, 0.35], [5.73, 0.2]].forEach(([r, a]) => tone(t, 'sine', f * r, f * r * 0.995, d / (1 + r * 0.25), v * a, pan, 0.3)); }
  const sfx = (name, o = {}) => {
    if (!ctx || vol.sfx <= 0) return;
    const t = ctx.currentTime + 0.01, pan = o.pan || 0, v = o.v == null ? 1 : o.v;
    switch (name) {
      case 'click': tone(t, 'sine', 880, 620, 0.07, 0.18 * v, pan, 0.05); swish(t, 5000, 3000, 0.03, 0.05 * v, pan); break;
      case 'move': tone(t, 'sine', 1320, 1180, 0.04, 0.07 * v, pan, 0.02); break;
      case 'sweep': swish(t, 300, 2600, 0.42, 0.18 * v, o.dir ? -0.4 * o.dir : 0, 0.8); break;
      case 'lift': swish(t, 1800, 3200, 0.12, 0.12 * v, pan); break;
      case 'back': swish(t, 2600, 900, 0.16, 0.1 * v, pan); tone(t + 0.1, 'sine', 300, 220, 0.12, 0.08 * v, pan); break;
      case 'draw': swish(t, 2200, 5200, 0.2, 0.14 * v, pan, 1.5); break;
      case 'flip': swish(t, 4000, 2500, 0.06, 0.16 * v, pan, 2); tone(t, 'triangle', 1700, 1200, 0.05, 0.06 * v, pan); break;
      case 'card': tone(t, 'sine', 170, 75, 0.14, 0.38 * v, pan, 0.08); swish(t, 1200, 500, 0.08, 0.14 * v, pan); break;
      case 'reveal': swish(t, 600, 3000, 0.3, 0.1 * v, pan, 0.8); tone(t + 0.05, 'sine', 1560, 1560, 0.5, 0.05 * v, pan, 0.4); break;
      case 'swing': swish(t, 700, 2800, 0.24, 0.32 * v, pan, 1.6); break;
      case 'arrow': tone(t, 'sine', 2600, 1300, 0.26, 0.07 * v, pan); swish(t, 3000, 1500, 0.26, 0.12 * v, pan, 3); break;
      case 'magic': for (let k = 0; k < 6; k++) tone(t + k * 0.04, 'sine', 1200 + Math.random() * 1800, 2600 + Math.random() * 1400, 0.35, 0.045 * v, pan, 0.5); swish(t, 400, 5000, 0.4, 0.1 * v, pan, 0.6); break;
      case 'punch': swish(t, 600, 1800, 0.12, 0.18 * v, pan); break;
      case 'hit': {                                       // where an attack lands: style flavours the impact
        tone(t, 'sine', 140, 48, 0.28, 0.6 * v, pan, 0.1);
        const n = noise(t, 0.2), lp = filt('lowpass', o.style === 'magic' ? 4000 : 2200), g = gainNode(0);
        n.connect(lp); lp.connect(g); g.gain.setValueAtTime(0.32 * v, t); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.18); out(g, sfxBus, 0.2, pan);
        if (o.style === 'melee') metal(t, 820, 0.3, 0.07 * v, pan);
        if (o.style === 'magic') for (let k = 0; k < 4; k++) tone(t + k * 0.03, 'sine', 2000 + k * 600, 900, 0.3, 0.04 * v, pan, 0.6);
        if (o.big) tone(t, 'sine', 70, 35, 0.6, 0.45 * v, pan, 0.3);
        break;
      }
      case 'soak': metal(t, 520, 0.6, 0.16 * v, pan); break;
      case 'shatter': for (let k = 0; k < 9; k++) metal(t + Math.random() * 0.25, 900 + Math.random() * 2500, 0.3, 0.06 * v, (Math.random() - 0.5)); swish(t, 6000, 2000, 0.4, 0.2 * v, pan); break;
      case 'mill': for (let k = 0; k < Math.min(6, o.n || 3); k++) swish(t + k * 0.07, 2600, 5200, 0.12, 0.08 * v, pan, 1.6); break;
      case 'heal': [72, 76, 79, 84].forEach((m, k) => tone(t + k * 0.08, 'sine', hz(m), hz(m), 0.6, 0.07 * v, pan, 0.6)); swish(t, 1500, 6000, 0.5, 0.05 * v, pan); break;
      case 'block': tone(t, 'sine', 330, 660, 0.35, 0.12 * v, pan, 0.4); metal(t + 0.05, 1240, 0.8, 0.07 * v, pan); break;
      case 'reverse': swish(t, 300, 4000, 0.35, 0.2 * v, pan, 0.9); tone(t + 0.3, 'sawtooth', 220, 880, 0.15, 0.05 * v, pan); break;
      case 'turnYou': I.brass(t, hz(62), 0.28, 0.7, sfxBus, 0); I.brass(t + 0.24, hz(69), 0.6, 0.8, sfxBus, 0); break;
      case 'turnBot': D.kick(t, 0.6, sfxBus); D.tom(t + 0.18, 0.4, sfxBus, 1); break;
      case 'coins': for (let k = 0; k < (o.n || 8); k++) metal(t + k * 0.06 + Math.random() * 0.04, 2300 + Math.random() * 900, 0.25, 0.05 * v, (Math.random() - 0.5) * 0.8); break;
      case 'coin': metal(t, 2600 + Math.random() * 600, 0.15, 0.035 * v, pan); break;
      case 'tear': { const n = noise(t, 0.55), bp = filt('bandpass', 2400, 0.9), g = gainNode(0), lfo = osc('square', 38, t), lg = gainNode(0.5);
        lfo.connect(lg); lg.connect(g.gain); n.connect(bp); bp.connect(g); g.gain.setValueAtTime(0.25 * v, t); g.gain.linearRampToValueAtTime(0.0001, t + 0.55);
        lfo.start(t); lfo.stop(t + 0.6); out(g, sfxBus, 0.15, pan); break; }
      case 'shake': for (let k = 0; k < 4; k++) swish(t + k * 0.11, 900, 1500, 0.1, 0.06 * v, (k % 2 ? 0.3 : -0.3), 1); break;
      case 'uncommon': [83, 88].forEach((m, k) => tone(t + k * 0.07, 'sine', hz(m), hz(m), 0.7, 0.07 * v, pan, 0.6)); break;
      case 'rare': [79, 83, 86, 91].forEach((m, k) => tone(t + k * 0.06, 'sine', hz(m), hz(m), 0.9, 0.07 * v, pan, 0.7)); swish(t, 2000, 8000, 0.6, 0.06 * v, pan); break;
      case 'add': tone(t, 'triangle', 660, 990, 0.08, 0.1 * v, pan, 0.05); break;
      case 'remove': tone(t, 'triangle', 700, 440, 0.08, 0.1 * v, pan, 0.05); break;
      case 'save': tone(t, 'sine', hz(76), hz(76), 0.15, 0.1 * v, pan, 0.3); tone(t + 0.1, 'sine', hz(83), hz(83), 0.35, 0.1 * v, pan, 0.4); break;
      case 'tick': {                                     // cape progress: a bell that climbs as the goal gets closer
        const steps = [72, 74, 76, 79, 81, 84, 86, 88], m = steps[Math.max(0, Math.min(7, Math.round((o.n || 0) * 7)))] - (o.bot ? 12 : 0);
        I.bells(t, hz(m), 0.5, (o.bot ? 1.5 : 2.2) * v, sfxBus, pan);
        tone(t, 'sine', hz(m + 12), hz(m + 12), 0.25, 0.05 * v, pan, 0.4);
        if (o.wear) { I.bells(t + 0.09, hz(m + 7), 0.7, 1.8 * v, sfxBus, pan); I.timp(t, hz(m - 36), 0.6, 0.7 * v, sfxBus); }
        break;
      }
      case 'ready': (o.bot ? [64, 67, 71, 76] : [72, 76, 79, 84, 88]).forEach((m, k) => I.bells(t + k * 0.07, hz(m), 0.9, 1.6 * v, sfxBus, pan)); swish(t, 2000, 7000, 0.5, 0.05 * v, pan); break;
      case 'drain': tone(t, 'triangle', 520, 300, 0.16, 0.06 * v, pan, 0.1); break;
      case 'nope': tone(t, 'square', 140, 120, 0.12, 0.04 * v, pan, 0.05); break;
      default: break;
    }
  };

  // ---------------------------------------------------------------------------------
  // Recorded sounds: the casting sound of each spell that has one. web/arena_sounds.py packs them into
  // the page as window.ARENA_SOUNDS = { 'Air Bolt': 'data:audio/mpeg;base64,...' }, keyed by card name.
  // A clip is decoded the first time it is wanted (or by warm(), ahead of a duel) and kept. It plays
  // through the effects bus, so the Sound effects setting and the limiter treat it like any other effect.
  // ---------------------------------------------------------------------------------
  const CLIPS = (typeof window !== 'undefined' && window.ARENA_SOUNDS) || {};
  // One volume for all of them: they keep the balance they were recorded with (a Strike is softer than a Wave).
  // Set by measuring (RSAudio.measure, 2026-11-16): at 1.3 the eighteen average -28 dB through the mixer, beside
  // -30 for the hit that follows a spell and -36 for the synthesized spell sound they take the place of.
  // Ramon (2026-11-18): far too soft, no punch. Now twice as loud (+6 dB), with the lows lifted (CLIP_BODY) so a
  // cast lands with some weight; the master's limiter catches the peaks.
  const CLIP_GAIN = 2.6;
  const CLIP_BODY = 5;                     // dB of low shelf, below 250 Hz
  const clips = {};                        // name -> AudioBuffer once decoded, a Promise while decoding, false if it can't be
  const clipLog = [];                      // the last few that were started, for the tests: { n, name, at, secs }
  let clipCount = 0;                       // how many have been started in all (n counts up from 1)
  function clipBytes(uri) {
    const b = atob(uri.slice(uri.indexOf(',') + 1)), u = new Uint8Array(b.length);
    for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
    return u.buffer;
  }
  function loadClip(name) {
    if (clips[name] !== undefined) return clips[name];
    clips[name] = new Promise((res) => {
      const ok = (buf) => { clips[name] = buf; res(buf); }, no = () => { clips[name] = false; res(false); };
      // (old Safari only takes the callbacks; everything else also returns a promise, whose failure is the same failure)
      try { const p = ctx.decodeAudioData(clipBytes(CLIPS[name]), ok, no); if (p && p.catch) p.catch(no); } catch (e) { no(); }
    });
    return clips[name];
  }
  // Play a recorded sound. True if this name has one (it starts as soon as it is decoded: at once, when it has
  // been played or warmed before); false if it has none, and the caller makes its usual synthesized sound.
  function clip(name, o = {}) {
    if (!CLIPS[name]) return false;
    if (!ctx || vol.sfx <= 0) return true;                 // it has a sound; it just isn't to be heard right now
    const c = ctx, asked = c.currentTime;
    const go = (buf) => {
      if (!buf || c !== ctx || c.currentTime - asked > 0.6) return;        // can't be decoded, or too late to belong to its moment
      const s = c.createBufferSource(), g = gainNode((o.v == null ? 1 : o.v) * CLIP_GAIN);
      const body = c.createBiquadFilter(); body.type = 'lowshelf'; body.frequency.value = 250; body.gain.value = CLIP_BODY;
      s.buffer = buf; s.connect(body); body.connect(g);
      out(g, sfxBus, o.rev == null ? 0.06 : o.rev, o.pan || 0);
      s.start(c.currentTime + 0.01);
      if (c.startRendering) return;                       // (being measured, not played)
      clipLog.push({ n: ++clipCount, name, at: +c.currentTime.toFixed(3), secs: +buf.duration.toFixed(2) });
      if (clipLog.length > 40) clipLog.shift();
    };
    const have = loadClip(name);
    if (have && have.then) have.then(go); else go(have);
    return true;
  }
  // Decode clips ahead of need (all of them, or the ones named), one after another so nothing piles up.
  async function warm(names) {
    if (!ctx) return;
    for (const n of names || Object.keys(CLIPS)) if (CLIPS[n]) await loadClip(n);
  }

  // ---------------------------------------------------------------------------------
  // Public face
  // ---------------------------------------------------------------------------------
  // Audio can only start after the player's first click, key or button press (a browser rule).
  function start() {
    if (ctx) { if (ctx.state === 'suspended') ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    // Phones get a bigger output buffer: the music is all synthesized live, and a phone's audio thread that
    // misses a deadline crackles. Desktops keep the smallest buffer, for snappy effects.
    const touch = !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
    let c;
    try { c = new AC(touch ? { latencyHint: 'playback' } : undefined); } catch (e) { c = new AC(); }
    use(c, build(c));
    timer = setInterval(tick, 50);
    apply();
  }
  function sting(name) {
    if (!ctx || !SONGS[name]) return;
    const now = ctx.currentTime;
    if (current && (name === 'victory' || name === 'defeat')) { fadeOut(current, 0.8); current = null; }
    else if (current) {                               // dip the music under a short fanfare
      const g = current.g.gain; g.cancelScheduledValues(now); g.setValueAtTime(g.value, now); g.linearRampToValueAtTime(0.35, now + 0.2); g.linearRampToValueAtTime(0.9, now + 3.2);
    }
    player(SONGS[name], now + 0.05, 0.02);
  }
  function volumes(v) {
    if (v) Object.assign(vol, v);
    if (ctx) { musicBus.gain.setTargetAtTime(vol.music, ctx.currentTime, 0.1); sfxBus.gain.setTargetAtTime(vol.sfx, ctx.currentTime, 0.05); apply(); }
    return { ...vol };
  }
  // Render a stretch of a song offline (for level checks): peak and RMS of the result.
  async function analyse(name, secs = 20, intense = false) {
    const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    const c = new OAC(2, Math.floor(44100 * secs), 44100);
    const saved = { ctx, master, musicBus, sfxBus, revIn, fxRevIn, noiseBuf };
    const savedWI = wantIntense;
    use(c, build(c)); musicBus.gain.value = 1; wantIntense = intense;
    const p = player(SONGS[name], 0, 0.01); schedule(p, secs);
    players.splice(players.indexOf(p), 1);
    wantIntense = savedWI;
    ({ ctx, master, musicBus, sfxBus, revIn, fxRevIn, noiseBuf } = saved);
    const buf = await c.startRendering();
    let peak = 0, sum = 0; const d0 = buf.getChannelData(0), d1 = buf.getChannelData(1);
    for (let i = 0; i < d0.length; i++) { const a = Math.max(Math.abs(d0[i]), Math.abs(d1[i])); if (a > peak) peak = a; sum += d0[i] * d0[i]; }
    return { peak: +peak.toFixed(3), rms: +Math.sqrt(sum / d0.length).toFixed(4), nan: Number.isNaN(peak) };
  }
  // How loud is something? Renders what `make` schedules (effects, clips already warmed) through a copy of the
  // mixer, offline and unheard, at full Sound effects volume, and reports its peak and its loudness over the
  // part that sounds. For setting levels and for the tests.
  async function measure(make, secs = 4) {
    const OAC = window.OfflineAudioContext || window.webkitOfflineAudioContext;
    const c = new OAC(2, Math.floor(44100 * secs), 44100);
    const saved = { ctx, master, musicBus, sfxBus, revIn, fxRevIn, noiseBuf }, savedSfx = vol.sfx;
    use(c, build(c)); sfxBus.gain.value = 1; vol.sfx = 1;
    try { make(); } finally { ({ ctx, master, musicBus, sfxBus, revIn, fxRevIn, noiseBuf } = saved); vol.sfx = savedSfx; }
    const buf = await c.startRendering();
    const d0 = buf.getChannelData(0), d1 = buf.getChannelData(1);
    let peak = 0, sum = 0, n = 0;
    for (let i = 0; i < d0.length; i++) {
      const a = Math.max(Math.abs(d0[i]), Math.abs(d1[i]));
      if (a > peak) peak = a;
      if (a > 0.001) { sum += (d0[i] * d0[i] + d1[i] * d1[i]) / 2; n += 1; }
    }
    const db = (x) => +(20 * Math.log10(Math.max(x, 1e-6))).toFixed(1);
    return { peak: db(peak), rms: db(Math.sqrt(sum / Math.max(n, 1))), secs: +(n / 44100).toFixed(2) };
  }
  return {
    unlock: start,
    want(w) { if (w === wanted) return; wanted = w; if (w !== 'duel' && w !== 'boss') setIntense(false); apply(); },
    intense: setIntense,
    sting,
    sfx,
    clip,
    warm,
    get clips() { return Object.keys(CLIPS); },
    get clipLog() { return clipLog.slice(); },
    measure,
    volumes,
    get ready() { return !!ctx; },
    // How the sound is holding up on this device: glitches (the audio thread missed its deadline, where the
    // browser reports it: AudioContext.playbackStats) and notes the page was too busy to schedule in time.
    health() {
      if (!ctx) return null;
      const ps = ctx.playbackStats || ctx.playoutStats || null;
      const num = (x) => (typeof x === 'number' && isFinite(x) ? x : null);
      const out = { buffer: num(ctx.baseLatency) !== null ? Math.round(((ctx.baseLatency || 0) + (num(ctx.outputLatency) || 0)) * 1000) : null,
        glitches: null, played: Math.round(ctx.currentTime), late: stats.late, dropped: stats.dropped };
      if (ps) out.glitches = num(ps.underrunEvents) !== null ? ps.underrunEvents : num(ps.fallbackFramesEvents);
      return out;
    },
    get playing() { return current ? current.song.name : null; },
    get layers() { return current ? Object.fromEntries(Object.entries(current.layers).map(([k, g]) => [k, +g.gain.value.toFixed(3)])) : null; },
    songs: () => Object.fromEntries(Object.entries(SONGS).map(([k, s]) => [k, { name: s.name, kind: s.kind, seconds: +(s.length * 60 / s.bpm).toFixed(1), notes: s.events.length }])),
    analyse,
  };
})();
