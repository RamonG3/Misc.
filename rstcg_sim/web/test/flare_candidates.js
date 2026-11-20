// Candidate "flare" sounds for the stranger's red flash. Each is make(ctx, out, t0): Web Audio only, ~3.5 s.
function noiseBuf(c, secs) { const b = c.createBuffer(1, c.sampleRate * secs, c.sampleRate), d = b.getChannelData(0); let s = 1; for (let i = 0; i < d.length; i++) { s = (s * 16807) % 2147483647; d[i] = s / 1073741823.5 - 1; } return b; }
function impulse(c, secs, decay) { const n = c.sampleRate * secs, b = c.createBuffer(2, n, c.sampleRate); let s = 7; for (let ch = 0; ch < 2; ch++) { const d = b.getChannelData(ch); for (let i = 0; i < n; i++) { s = (s * 16807) % 2147483647; d[i] = (s / 1073741823.5 - 1) * Math.pow(1 - i / n, decay); } } return b; }
function rev(c, out, secs, decay, wet) { const cv = c.createConvolver(); cv.buffer = impulse(c, secs, decay); const g = c.createGain(); g.gain.value = wet; cv.connect(g); g.connect(out); return cv; }
function env(g, t, pts) { g.setValueAtTime(pts[0][1], t + pts[0][0]); for (const [dt, v, k] of pts.slice(1)) (k === 'exp' ? g.exponentialRampToValueAtTime(Math.max(v, 1e-4), t + dt) : g.linearRampToValueAtTime(v, t + dt)); }
function osc(c, type, f, dest) { const o = c.createOscillator(); o.type = type; o.frequency.value = f; const g = c.createGain(); g.gain.value = 0; o.connect(g); if (dest) g.connect(dest); return { o, g: g.gain, f: o.frequency, node: g }; }

const V = {
  // A. Ruby Flare: a breath sucked in, a deep falling boom, and a high shimmer that rings out
  ruby(c, out, t) {
    const R = rev(c, out, 4.5, 2.2, 0.55);
    // in-breath: noise through a rising bandpass, swelling into the hit
    const n = c.createBufferSource(); n.buffer = noiseBuf(c, 1.2); const bp = c.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 2.5;
    const ng = c.createGain(); n.connect(bp); bp.connect(ng); ng.connect(out); ng.connect(R);
    bp.frequency.setValueAtTime(300, t); bp.frequency.exponentialRampToValueAtTime(5000, t + 0.42);
    env(ng.gain, t, [[0, 0], [0.38, 0.35], [0.46, 0.0001, 'exp']]); n.start(t);
    // the boom: sine falling 110 -> 32 Hz, with a soft click of a square on top
    const b = osc(c, 'sine', 110, out); b.f.setValueAtTime(110, t + 0.42); b.f.exponentialRampToValueAtTime(32, t + 2.2);
    env(b.g, t, [[0.42, 0], [0.45, 0.95], [2.6, 0.0001, 'exp']]); b.o.start(t + 0.4); b.o.stop(t + 3);
    const b2 = osc(c, 'triangle', 55, out); b2.f.setValueAtTime(55, t + 0.42); b2.f.exponentialRampToValueAtTime(27, t + 2.4);
    env(b2.g, t, [[0.42, 0], [0.46, 0.5], [2.8, 0.0001, 'exp']]); b2.o.start(t + 0.4); b2.o.stop(t + 3);
    // shimmer: a chord of detuned high sines with tremolo, all into the reverb
    [880, 1318.5, 1760, 2637, 3520].forEach((f, k) => { for (const d of [-4, 4]) {
      const s = osc(c, 'sine', f + d, R); const lfo = c.createOscillator(); lfo.frequency.value = 5 + k; const lg = c.createGain(); lg.gain.value = 0.04; lfo.connect(lg); lg.connect(s.g);
      env(s.g, t, [[0.42, 0], [0.5, 0.09 / (k + 1)], [3.4, 0.0001, 'exp']]); s.o.start(t + 0.4); s.o.stop(t + 3.6); lfo.start(t + 0.4); lfo.stop(t + 3.6); } });
  },
  // B. Rift: a slow swell from far below, a heavy thump, glassy inharmonic bells falling away
  rift(c, out, t) {
    const R = rev(c, out, 5, 2, 0.7);
    const sw = osc(c, 'sawtooth', 41, null); const lp = c.createBiquadFilter(); lp.type = 'lowpass'; lp.Q.value = 6; sw.node.connect(lp); sw.g.value = 1; const sg = c.createGain(); lp.connect(sg); sg.connect(out); sg.connect(R);
    sw.f.setValueAtTime(41, t); lp.frequency.setValueAtTime(80, t); lp.frequency.exponentialRampToValueAtTime(1800, t + 0.45); lp.frequency.exponentialRampToValueAtTime(60, t + 2.5);
    env(sg.gain, t, [[0, 0], [0.42, 0.45], [2.8, 0.0001, 'exp']]); sw.o.start(t); sw.o.stop(t + 3);
    const k = osc(c, 'sine', 70, out); k.f.setValueAtTime(140, t + 0.44); k.f.exponentialRampToValueAtTime(38, t + 0.9);
    env(k.g, t, [[0.44, 0], [0.45, 1], [1.6, 0.0001, 'exp']]); k.o.start(t + 0.43); k.o.stop(t + 2);
    // bells: inharmonic partials, a falling cascade
    [[1244, 0.46], [987, 0.62], [1567, 0.8], [740, 1.0], [1108, 1.25]].forEach(([f, dt], i) => { [1, 2.76, 5.4].forEach((m, j) => {
      const s = osc(c, 'sine', f * m, R); env(s.g, t, [[dt, 0], [dt + 0.005, 0.1 / (j + 1) / (1 + i * 0.3)], [dt + 2.2 / (j + 1), 0.0001, 'exp']]); s.o.start(t + dt); s.o.stop(t + dt + 2.5); }); });
  },
  // C. Eclipse: a reversed whoosh rushing in, a deep boom with a choir-like "ahh" chord that blooms and fades
  eclipse(c, out, t) {
    const R = rev(c, out, 5.5, 1.8, 0.65);
    const n = c.createBufferSource(); n.buffer = noiseBuf(c, 1); const hp = c.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 1500; const ng = c.createGain();
    n.connect(hp); hp.connect(ng); ng.connect(out); ng.connect(R); env(ng.gain, t, [[0, 0], [0.43, 0.3], [0.44, 0]]); n.start(t);
    const b = osc(c, 'sine', 48, out); b.f.setValueAtTime(75, t + 0.44); b.f.exponentialRampToValueAtTime(36, t + 1.8);
    env(b.g, t, [[0.44, 0], [0.47, 1], [3, 0.0001, 'exp']]); b.o.start(t + 0.43); b.o.stop(t + 3.2);
    // the "ahh": saws on a low A minor chord through two formant bandpasses (an "ah": ~730 and ~1090 Hz)
    const mix = c.createGain(); const f1 = c.createBiquadFilter(), f2 = c.createBiquadFilter(); f1.type = f2.type = 'bandpass'; f1.frequency.value = 730; f2.frequency.value = 1090; f1.Q.value = 8; f2.Q.value = 10;
    const vg = c.createGain(); mix.connect(f1); mix.connect(f2); f1.connect(vg); f2.connect(vg); vg.connect(out); vg.connect(R);
    env(vg.gain, t, [[0.44, 0], [0.9, 1.6], [3.4, 0.0001, 'exp']]);
    [110, 130.8, 164.8, 220, 261.6].forEach((f) => { for (const d of [-0.8, 0.8]) { const s = c.createOscillator(); s.type = 'sawtooth'; s.frequency.value = f + d; const g = c.createGain(); g.gain.value = 0.12; s.connect(g); g.connect(mix); s.start(t + 0.42); s.stop(t + 3.6); } });
  },
  // D. Ruby Flare, darker: A's boom and in-breath, with a low, slow-beating hum under a fainter shimmer
  ember(c, out, t) {
    V.ruby(c, out, t);
    const R = rev(c, out, 4, 2, 0.4);
    [[65.4, 65.9], [98, 97.3]].forEach(([a, b2]) => { for (const f of [a, b2]) { const s = osc(c, 'triangle', f, R); s.g.value = 0; env(s.g, t, [[0.44, 0], [0.9, 0.18], [3.3, 0.0001, 'exp']]); s.o.start(t + 0.4); s.o.stop(t + 3.6);
      const d = osc(c, 'triangle', f, out); env(d.g, t, [[0.44, 0], [0.9, 0.16], [3.3, 0.0001, 'exp']]); d.o.start(t + 0.4); d.o.stop(t + 3.6); } });
  },
};
async function render(name) {
  const c = new OfflineAudioContext(2, 44100 * 4.6, 44100);
  const out = c.createDynamicsCompressor(); out.threshold.value = -10; out.ratio.value = 6; out.connect(c.destination);
  const g = c.createGain(); g.gain.value = 0.8; g.connect(out);
  V[name](c, g, 0.05);
  const b = await c.startRendering();
  return [Array.from(b.getChannelData(0)), Array.from(b.getChannelData(1))];
}
