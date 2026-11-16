// The page's account calls (web/online.js) against the stand-in for Supabase (server/fake_supabase.mjs), from node:
// node web/test/accounts_test.js      ends "all passed" (about 5 s)
//
// Each "device" is its own copy of online.js with its own saved sign-in. They sign in with Google (the trip there
// and back is followed by hand, since node has no address bar), with emailed codes, and sign out; a guest who signs
// in keeps their id and name; a second device lands on the same account; and the cloud save keeps to its rules.
const path = require('path');
const { spawn } = require('child_process');
const O = require('../online.js');

const PORT = 55400 + Math.floor(Math.random() * 400);
const SERVER = `http://127.0.0.1:${PORT}`;
const BACK = 'http://127.0.0.1:9/arena.html';        // where "Google" sends the player back to (nothing is there: we only read the address)
let failed = 0;
const check = (name, ok, detail) => { console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`); if (!ok) failed++; };
const post = (p, b) => fetch(SERVER + p, { method: 'POST', body: JSON.stringify(b || {}) }).then((r) => r.json());
const get = (p) => fetch(SERVER + p).then((r) => r.json());
const inbox = async (to) => get('/__test/mail?to=' + encodeURIComponent(to));
const lastCode = async (to) => { const m = await inbox(to); return m.length ? m[m.length - 1] : null; };
const minute = () => post('/__test/clock', { add: 61e3 });       // the mailer writes to an address once a minute
const code = async (p) => { try { await p; return 'no error'; } catch (e) { return e.code || String(e); } };

let went = null;                                     // where a device last sent its "browser"
globalThis.location = { assign: (url) => { went = url; } };
function device() {
  const d = { auth: null, flow: null, events: [] };
  d.ON = O.make({ server: { url: SERVER, key: 'sb_publishable_fake' }, load: () => d.auth, save: (a) => { d.auth = a; },
    loadFlow: () => d.flow, saveFlow: (f) => { d.flow = f; }, changed: (why) => d.events.push(why) });
  return d;
}
// to Google and back: `as` is the Google account picked there (null: they press Cancel)
async function trip(d, as, opt) {
  went = null;
  await d.ON.google(BACK, opt);
  let at = went;
  const first = await fetch(at, { redirect: 'manual' });
  if (first.status === 302) at = first.headers.get('location');          // the sign-in address hops on to Google's page
  const state = new URL(at).searchParams.get('state');
  const r = await fetch(`${SERVER}/auth/v1/callback?state=${state}${as ? '&as=' + encodeURIComponent(as) : '&error=access_denied'}`, { redirect: 'manual' });
  const home = r.headers.get('location');
  const l = d.ON.landing(home);
  if (!l) throw new Error('came back to an address the page makes nothing of: ' + home);
  return Object.assign(await d.ON.land(l), { home });
}

(async () => {
  const fake = spawn('node', [path.join(__dirname, '..', '..', 'server', 'fake_supabase.mjs'), String(PORT)], { stdio: ['ignore', 'pipe', 'inherit'] });
  await new Promise((res, rej) => { fake.stdout.on('data', (d) => { if (/listening/.test(String(d))) res(); }); fake.on('exit', () => rej(new Error('the fake server stopped'))); });
  try {
    // ---------- what's switched on ----------
    check('the server says which ways to sign in are on', JSON.stringify(await device().ON.ways()) === '{"google":true,"email":true}');
    await post('/__test/auth', { google: false });
    check('and when Google is switched off', JSON.stringify(await device().ON.ways()) === '{"google":false,"email":true}');
    await post('/__test/auth', { google: true });

    // ---------- a guest signs in with Google and stays who they were ----------
    const pc = device();
    check('before anything: nobody', pc.ON.account() === null);
    const guest = await pc.ON.user();
    await pc.ON.setName('Ann');
    check('a guest is a guest', pc.ON.account().guest === true && pc.ON.account().uid === guest);
    check('a guest has no cloud save, and asking makes nobody new', (await code(pc.ON.progress.load(0))) === 'auth' && (await code(pc.ON.progress.save({ gp: 1 }, null))) === 'auth' && pc.ON.account().uid === guest);
    let r = await trip(pc, 'ann@gmail.test');
    const ann = pc.ON.account();
    check('Google is tied to the guest: the same id, now an account', r.linked === true && ann.uid === guest && ann.guest === false && ann.email === 'ann@gmail.test' && ann.provider === 'google', JSON.stringify(ann));
    check('the address they came back to has only the code', /\?code=[0-9a-f-]+$/.test(r.home), r.home);
    check('the name came along', (await pc.ON.myName()) === 'Ann');
    check('the trip leaves nothing behind', pc.flow === null);

    // ---------- the cloud save ----------
    check('nothing saved yet', (await pc.ON.progress.load(0)) === null);
    r = await pc.ON.progress.save({ gp: 300, coll: { 'Rune Scimitar': 1 } }, null);
    check('the first save', r.ok === true && r.rev === 1 && !!r.at);
    r = await pc.ON.progress.load(0);
    check('and reading it back', r && r.rev === 1 && r.data.gp === 300 && r.data.coll['Rune Scimitar'] === 1);
    check('nothing newer than what we have', (await pc.ON.progress.load(1)) === null);
    r = await pc.ON.progress.save({ gp: 400 }, 1);
    check('saving from the revision we had', r.ok === true && r.rev === 2);
    r = await pc.ON.progress.save({ gp: 999 }, 1);
    check('saving from an old revision is refused, with the newer copy', r.ok === false && r.rev === 2 && r.data.gp === 400);
    r = await pc.ON.progress.save({ gp: 1 }, null);
    check('"nothing saved yet" is refused once something is', r.ok === false && r.rev === 2);

    // ---------- a second device: just sign in ----------
    const phone = device();
    r = await trip(phone, 'ann@gmail.test');
    check('a device with no sign-in lands on the same account', r.linked === false && phone.ON.account().uid === guest && phone.ON.account().guest === false);
    check('with its name', (await phone.ON.myName()) === 'Ann');
    r = await phone.ON.progress.load(0);
    check('and its save', r && r.rev === 2 && r.data.gp === 400);

    // ---------- a third device that already has a guest of its own ----------
    const tablet = device();
    const tabGuest = await tablet.ON.user();
    await tablet.ON.setName('AnnTablet');
    r = await trip(tablet, 'ann@gmail.test');
    check('Google is already another player\'s: the page is told to sign in instead', r.again === true && tablet.ON.account().uid === tabGuest && tablet.ON.account().guest === true);
    r = await trip(tablet, 'ann@gmail.test', { fresh: true });
    check('and then it is the account, the guest left behind', tablet.ON.account().uid === guest && tablet.ON.account().guest === false && (await tablet.ON.myName()) === 'Ann');

    // ---------- things going wrong on the way ----------
    const d1 = device();
    await d1.ON.user();
    check('pressing Cancel at Google', (await code(trip(d1, null))) === 'access_denied' && d1.ON.account().guest === true && d1.flow === null);
    await post('/__test/auth', { linking: false });
    check('"manual linking" switched off at the server', (await code(trip(d1, 'bob@gmail.test'))) === 'manual_linking_disabled');
    await post('/__test/auth', { linking: true });
    check('an address that is not a sign-in coming back', d1.ON.landing(BACK + '#duel-K7MQX') === null && d1.ON.landing(BACK + '?code=abc') === null && d1.ON.landing(BACK) === null);
    d1.flow = { verifier: 'x'.repeat(64), link: false, at: Date.now() };
    check('a code from someone else\'s trip is refused', (await code(d1.ON.land(d1.ON.landing(BACK + '?code=' + '0'.repeat(8))))) === 'flow_state_not_found');
    {
      const thief = device(), owner = device();
      went = null; await owner.ON.google(BACK);
      const hop = await fetch(went, { redirect: 'manual' });
      const state = new URL(hop.headers.get('location')).searchParams.get('state');
      const back = (await fetch(`${SERVER}/auth/v1/callback?state=${state}&as=bob%40gmail.test`, { redirect: 'manual' })).headers.get('location');
      thief.flow = { verifier: 'y'.repeat(64), link: false, at: Date.now() };
      check('a stolen code is no use without the secret the page kept', (await code(thief.ON.land(thief.ON.landing(back)))) === 'bad_code_verifier' && thief.ON.account() === null);
      r = await owner.ON.land(owner.ON.landing(back));
      check('and the page that kept it still signs in', owner.ON.account().email === 'bob@gmail.test' && r.linked === false);
    }

    // ---------- emailed codes ----------
    const e1 = device();
    r = await e1.ON.emailCode('cat@mail.test');
    let m = await lastCode('cat@mail.test');
    check('a new player is emailed a code', r.type === 'email' && m && m.kind === 'signup' && /^\d{6}$/.test(m.code));
    check('a wrong code is refused', (await code(e1.ON.emailVerify('cat@mail.test', m.code === '000000' ? '111111' : '000000', r.type))) === 'otp_expired' && e1.ON.account() === null);
    const cat = await e1.ON.emailVerify('cat@mail.test', m.code, r.type);
    check('the right code signs them in', cat.guest === false && cat.email === 'cat@mail.test' && cat.provider === 'email' && e1.ON.account().uid === cat.uid);
    check('a code works once', (await code(device().ON.emailVerify('cat@mail.test', m.code, 'email'))) === 'otp_expired');
    check('asking again inside a minute is refused', (await code(device().ON.emailCode('cat@mail.test'))) === 'over_email_send_rate_limit');
    await minute();
    const e2 = device();
    r = await e2.ON.emailCode('Cat@Mail.test');
    m = await lastCode('cat@mail.test');
    check('coming back on another device: a code again', r.type === 'email' && m.kind === 'magiclink');
    check('and the same account', (await e2.ON.emailVerify('Cat@Mail.test', m.code, r.type)).uid === cat.uid);

    const e3 = device();
    const g3 = await e3.ON.user();
    await e3.ON.setName('Dan');
    r = await e3.ON.emailCode('dan@mail.test');
    m = await lastCode('dan@mail.test');
    check('a guest is emailed a code for their own id', r.type === 'email_change' && m.kind === 'email_change' && e3.ON.account().guest === true);
    const dan = await e3.ON.emailVerify('dan@mail.test', m.code, r.type);
    check('and stays who they were', dan.uid === g3 && dan.guest === false && dan.email === 'dan@mail.test' && (await e3.ON.myName()) === 'Dan');
    r = await e3.ON.progress.save({ gp: 50 }, null);
    check('with a cloud save of their own', r.ok === true && (await e3.ON.progress.load(0)).data.gp === 50 && (await e1.ON.progress.load(0)) === null);

    await minute();
    const e4 = device();
    const g4 = await e4.ON.user();
    r = await e4.ON.emailCode('dan@mail.test');
    m = await lastCode('dan@mail.test');
    check('a guest who types an address that has an account is signing in to it', r.type === 'email' && m.kind === 'magiclink');
    check('and lands on it', (await e4.ON.emailVerify('dan@mail.test', m.code, r.type)).uid === g3 && e4.ON.account().uid !== g4);

    check('nonsense for an address', (await code(device().ON.emailCode('not an address'))) === 'validation_failed');
    await post('/__test/auth', { team: ['ramon@team.test'] });
    const users = (await get('/__test/db')).users.length;
    check('the built-in mailer refuses a stranger', (await code(device().ON.emailCode('friend@mail.test'))) === 'email_address_not_authorized' && (await get('/__test/db')).users.length === users);
    r = await device().ON.emailCode('ramon@team.test');
    check('but writes to the team', r.type === 'email' && (await lastCode('ramon@team.test')).kind === 'signup');
    await post('/__test/auth', { team: null, confirm: false });
    const e5 = device();
    const g5 = await e5.ON.user();
    r = await e5.ON.emailCode('eve@mail.test');
    check('"Confirm email" switched off: a guest needs no code', r.done === true && e5.ON.account().uid === g5 && e5.ON.account().guest === false && e5.ON.account().email === 'eve@mail.test');
    check('and can save at once (their token says "account")', (await e5.ON.progress.save({ gp: 7 }, null)).ok === true);
    await post('/__test/auth', { confirm: true });
    await post('/__test/auth', { email: false });
    check('the Email provider switched off', (await code(device().ON.emailCode('zed@mail.test'))) === 'email_provider_disabled' && (await device().ON.ways()).email === false);
    await post('/__test/auth', { email: true });

    // a link pressed in a sign-in email brings the sign-in itself back in the address
    const e6 = device();
    r = await e6.ON.land(e6.ON.landing(`${BACK}#access_token=${e2.auth.access_token}&refresh_token=${e2.auth.refresh_token}&expires_in=3600&token_type=bearer&type=magiclink`));
    check('a link from an email signs in too', e6.ON.account().uid === cat.uid && e6.ON.account().guest === false && e6.ON.account().email === 'cat@mail.test');
    check('an error in the address is passed on', (await code(e6.ON.land(e6.ON.landing(`${BACK}#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired`)))) === 'otp_expired');

    // ---------- staying signed in, signing out, being signed out ----------
    const hour = () => post('/__test/clock', { add: 3700e3 });             // every token handed out so far is now out of date
    await hour();
    const before = pc.auth.refresh_token;
    r = await pc.ON.progress.load(0);
    check('an out-of-date sign-in renews itself and is still the account', r.rev === 2 && pc.auth.refresh_token !== before && pc.ON.account().uid === guest && pc.ON.account().guest === false && pc.ON.account().email === 'ann@gmail.test' && pc.events.length === 0);
    check('so does looking up a name', (await pc.ON.myName()) === 'Ann' && (await e3.ON.myName()) === 'Dan');
    const kept = Object.assign({}, phone.auth);
    await phone.ON.signOut();
    check('signing out: this device is nobody', phone.ON.account() === null && phone.auth === null);
    const ghost = device(); ghost.auth = kept;
    check('and its old sign-in is no use to anyone', (await code(ghost.ON.progress.load(0))) === 'auth' && ghost.events.join() === 'signedout' && ghost.ON.account() === null);
    check('the other devices stay signed in', (await pc.ON.progress.load(0)).rev === 2 && (await tablet.ON.progress.load(0)).rev === 2);
    const again = await phone.ON.user();
    check('a signed-out device that goes online is a new guest', again !== guest && phone.ON.account().guest === true);

    await post('/__test/revoke', { uid: guest });                          // the server forgets Ann's sign-ins...
    await hour();                                                          // ...and the tokens already out there run out
    check('a device the server has signed out says so, once', (await code(tablet.ON.progress.save({ gp: 5 }, 2))) === 'auth' && tablet.events.join() === 'signedout' && tablet.ON.account() === null);
    check('and nothing was saved', (await get('/__test/db')).progress.find((p) => p.user_id === guest).data.gp === 400);
    check('asking again changes nothing, and signs nobody in', (await code(tablet.ON.progress.load(0))) === 'auth' && tablet.events.length === 1 && tablet.ON.account() === null);
    const old = device(); old.auth = { access_token: 'x.x.x', refresh_token: 'gone', expires_at: 0, uid: 'x' };   // kept from before accounts: no "guest" field
    const renewed = await old.ON.user();
    check('a guest whose sign-in is gone quietly becomes a new guest', old.events.length === 0 && old.ON.account().guest === true && renewed !== 'x');

    const db = await get('/__test/db');
    check('the server ends with the players we made', db.users.filter((u) => !u.is_anonymous).length === 6 && db.progress.length === 3, `${db.users.filter((u) => !u.is_anonymous).length} accounts, ${db.progress.length} saves`);
  } finally { fake.kill(); }
  console.log(failed ? `${failed} FAILED` : 'all passed');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
