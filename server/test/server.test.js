import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { handle, MAX_REQUESTS_PER_TURN } from '../src/app.js';
import { open } from '../src/db.js';
import { day, dayEnd, weekStart, weekEnd } from '../src/time.js';
import { sign, verify, totp } from '../src/auth.js';
import { DEFAULTS, validate } from '../src/config.js';
import { forget } from '../src/play.js';

const MIGRATIONS = join(dirname(fileURLToPath(import.meta.url)), '..', 'migrations');
const DAY = 86_400_000;
// Thursday 2026-11-12, 15:00 in Brasília.
const NOW = Date.UTC(2026, 10, 12, 18, 0, 0);
const INSTALL = '3f1c2a9e-8b7d-4c6e-9a1b-2c3d4e5f6a7b';
const DEVICE = 'a'.repeat(64);
const PLAN = '{"reply":"Guardei.","create":[{"kind":"task","title":"Ligar pro Pedro"}]}';
const TOTP = 'JBSWY3DPEHPK3PXP';

const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const SERVICE = JSON.stringify({ client_email: 'novelo@play.iam.gserviceaccount.com', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }), token_uri: 'https://oauth2.googleapis.com/token' });

function env(extra = {}) {
  return {
    DB: open(':memory:', MIGRATIONS),
    TOKEN_SECRET: 'segredo-de-teste-com-bem-mais-de-32-caracteres',
    ADMIN_KEY: 'chave-de-admin-de-teste-bem-comprida',
    ADMIN_TOTP_SECRET: TOTP,
    OPENROUTER_FREE_KEY: 'sk-or-free',
    OPENROUTER_PAID_KEY: 'sk-or-paid',
    ASSETS: { 'index.html': { type: 'text/html', body: '<html>painel</html>' } },
    ...extra,
  };
}

/** OpenRouter (and Google, when asked): answers by [reply], keeping every call. */
function net(reply = () => ({ status: 200, text: PLAN, cost: 0.0008, inTok: 4000, outTok: 600 }), google = {}) {
  const calls = [];
  const fetcher = async (url, init = {}) => {
    const u = String(url);
    if (u.startsWith('https://oauth2.googleapis.com/token')) {
      calls.push({ google: 'token' });
      return new Response(JSON.stringify({ access_token: 'ya29.test', expires_in: 3600 }), { status: 200 });
    }
    if (u.includes('androidpublisher')) {
      const ack = u.endsWith(':acknowledge');
      calls.push({ google: ack ? 'ack' : 'get', url: u, auth: init.headers?.authorization });
      if (ack) return new Response('{}', { status: 200 });
      const g = typeof google.sub === 'function' ? google.sub() : google.sub;
      if (!g) return new Response('{}', { status: 404 });
      return new Response(JSON.stringify(g), { status: 200 });
    }
    const body = JSON.parse(init.body);
    calls.push({ url: u, auth: init.headers.authorization, body });
    const r = reply(body, calls.length);
    if (r.status !== 200) return new Response('{"error":{}}', { status: r.status });
    return new Response(JSON.stringify({ choices: [{ message: { content: r.text } }], usage: { cost: r.cost, prompt_tokens: r.inTok, completion_tokens: r.outTok } }), { status: 200 });
  };
  return { calls, fetcher, models: () => calls.filter((c) => c.body).map((c) => c.body.model) };
}

function req(method, path, { body, token, ip = '1.2.3.4', admin, cookie, header = true } = {}) {
  const headers = { 'content-type': 'application/json', 'x-novelo-ip': ip, 'user-agent': 'Novelo/1.26.0 (Android 16)' };
  if (token) headers.authorization = `Bearer ${token}`;
  if (cookie) headers.cookie = cookie;
  if (admin && header) headers['x-novelo-admin'] = '1';
  return new Request(`https://novelo.test${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
}

async function call(e, method, path, opts = {}, now = NOW, fetcher = net().fetcher) {
  const r = await handle(req(method, path, opts), e, now, fetcher);
  const text = await r.text();
  let body = null;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: r.status, body, headers: r.headers };
}

async function start(e, { install = INSTALL, device = DEVICE, now = NOW, ip } = {}) {
  const r = await call(e, 'POST', '/v1/session', { body: { install, device }, ip }, now);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.body;
}

const say = (turn, kind = 'talk') => ({ system: 'Você é o Novelo.', user: `O QUE ELA DISSE: fala ${turn}`, kind, turn: String(turn) });
const organize = (e, token, turn, kind, now = NOW, n = net()) => call(e, 'POST', '/v1/organize', { token, body: say(turn, kind) }, now, n.fetcher);

async function adminLogin(e, now = NOW) {
  const r = await call(e, 'POST', '/admin/api/login', { admin: true, body: { key: e.ADMIN_KEY, code: await totp(TOTP, now) } }, now);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  return r.headers.get('set-cookie').split(';')[0];
}

const adminCall = (e, c, method, path, body, now = NOW) => call(e, method, path, { admin: true, cookie: c, body }, now);

beforeEach(() => forget());

// ——— Time ———

test('the day and the week turn at midnight in Brasília (weeks start on Monday)', () => {
  assert.equal(day(Date.UTC(2026, 10, 13, 2, 59)), '2026-11-12');
  assert.equal(day(Date.UTC(2026, 10, 13, 3, 0)), '2026-11-13');
  assert.equal(dayEnd(NOW), Date.UTC(2026, 10, 13, 3, 0));
  assert.equal(weekStart(NOW), '2026-11-09');
  assert.equal(weekEnd(NOW), Date.UTC(2026, 10, 16, 3, 0));
  // Sunday night is still the same week.
  assert.equal(weekStart(Date.UTC(2026, 10, 16, 2, 0)), '2026-11-09');
});

test('a pass is the install signed by the server, and only that', async () => {
  const t = await sign('s', INSTALL, NOW);
  assert.equal(await verify('s', t, NOW + 1000), INSTALL);
  assert.equal(await verify('outro', t, NOW), null);
  assert.equal(await verify('s', t, NOW + 31 * DAY), null);
  assert.equal(await verify('s', 'v1.x.y.z', NOW), null);
});

// ——— The trial ———

test('a new phone is on the trial, which starts at the first use, with the free models and no key in the app', async () => {
  const e = env();
  const s = await start(e);
  assert.equal(s.status.plan, 'trial');
  assert.equal(s.status.trial_ends, null);
  assert.deepEqual(s.status.talk, { day: { used: 0, limit: 15 }, week: { used: 0, limit: 60 } });
  assert.deepEqual(s.status.photo, { day: { used: 0, limit: 5 }, week: { used: 0, limit: 20 } });
  const n = net();
  const r = await organize(e, s.token, 1, 'talk', NOW, n);
  assert.equal(r.status, 200);
  assert.equal(r.body.text, PLAN);
  assert.equal(r.body.status.trial_ends, NOW + 7 * DAY);
  assert.equal(r.body.status.talk.day.used, 1);
  assert.equal(n.calls[0].auth, 'Bearer sk-or-free');
  assert.equal(n.calls[0].body.model, DEFAULTS.free_models[0]);
  assert.equal(n.calls[0].body.provider, undefined);
});

test('the trial is one per phone, reinstalled or not', async () => {
  const e = env();
  const a = await start(e);
  await organize(e, a.token, 1);
  const again = await start(e, { install: '00000000-0000-4000-8000-000000000009' });
  assert.equal(again.status.trial_ends, NOW + 7 * DAY);
  assert.equal(again.status.talk.day.used, 1);
});

test('the trial gives 15 talks a day and 60 in all; then it ends, and asks for Novelo+', async () => {
  const e = env();
  const { token } = await start(e);
  for (let i = 1; i <= 15; i++) assert.equal((await organize(e, token, i)).status, 200);
  const over = await organize(e, token, 16);
  assert.equal(over.status, 429);
  assert.equal(over.body.error, 'daily_limit');
  assert.equal(over.body.status.day_resets, dayEnd(NOW));
  // Over the next days, up to 60 in the whole trial.
  let n = 15;
  for (let d = 1; n < 60; d++) for (let i = 0; i < 15 && n < 60; i++, n++) assert.equal((await organize(e, token, `d${d}-${i}`, 'talk', NOW + d * DAY)).status, 200);
  const spent = await organize(e, token, 'x', 'talk', NOW + 4 * DAY);
  assert.equal(spent.status, 429);
  assert.equal(spent.body.error, 'weekly_limit');
  assert.equal(spent.body.status.week_resets, NOW + 7 * DAY);
  // After 7 days: no plan.
  const ended = await organize(e, token, 'y', 'talk', NOW + 7 * DAY + 1);
  assert.equal(ended.status, 402);
  assert.equal(ended.body.error, 'no_plan');
  assert.equal(ended.body.status.plan, 'ended');
});

test('a talk that looks something up counts once (up to a few requests)', async () => {
  const e = env();
  const { token } = await start(e);
  for (let i = 0; i < MAX_REQUESTS_PER_TURN; i++) assert.equal((await organize(e, token, 7)).status, 200);
  assert.equal((await call(e, 'GET', '/v1/me', { token })).body.status.talk.day.used, 1);
  assert.equal((await organize(e, token, 7)).body.error, 'turn_limit');
});

// ——— Novelo+ ———

test('a test purchase (the GitHub app) gives Novelo+: the paid models, privately, and the bigger limits', async () => {
  const e = env();
  const { token } = await start(e);
  const p = await call(e, 'POST', '/v1/purchase', { token, body: { store: 'test' } });
  assert.equal(p.status, 200);
  assert.equal(p.body.status.plan, 'plus');
  assert.equal(p.body.status.sub_until, NOW + 30 * DAY);
  assert.deepEqual(p.body.status.talk.day, { used: 0, limit: 60 });
  const n = net();
  await organize(e, token, 1, 'talk', NOW, n);
  assert.equal(n.calls[0].auth, 'Bearer sk-or-paid');
  assert.equal(n.calls[0].body.model, 'openai/gpt-6-luna');
  assert.deepEqual(n.calls[0].body.provider, { data_collection: 'deny' });
  // The week (Monday to Sunday) has its own ceiling.
  const st = (await call(e, 'GET', '/v1/me', { token })).body.status;
  assert.deepEqual(st.talk.week, { used: 1, limit: 250 });
  assert.equal(st.week_resets, weekEnd(NOW));
});

test('test purchases can be turned off in the panel', async () => {
  const e = env();
  const { token } = await start(e);
  const c = await adminLogin(e);
  assert.equal((await adminCall(e, c, 'PUT', '/admin/api/config', { patch: { test_purchases: false } })).status, 200);
  assert.equal((await call(e, 'POST', '/v1/purchase', { token, body: { store: 'test' } })).status, 403);
});

test('a Google Play purchase is checked with Google, acknowledged, and follows its renewals', async () => {
  const e = env({ GOOGLE_SERVICE_ACCOUNT_JSON: SERVICE, PLAY_PACKAGE: 'com.villamediana.novelo', RTDN_KEY: 'rtdn-chave' });
  const { token } = await start(e);
  let sub = {
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
    acknowledgementState: 'ACKNOWLEDGEMENT_STATE_PENDING',
    lineItems: [{ productId: 'novelo_plus', expiryTime: new Date(NOW + 30 * DAY).toISOString() }],
    externalAccountIdentifiers: { obfuscatedExternalAccountId: INSTALL },
  };
  const n = net(undefined, { sub: () => sub });
  const r = await call(e, 'POST', '/v1/purchase', { token, body: { store: 'play', token: 'play-token-1' } }, NOW, n.fetcher);
  assert.equal(r.status, 200, JSON.stringify(r.body));
  assert.equal(r.body.status.plan, 'plus');
  assert.equal(r.body.status.sub_store, 'play');
  assert.deepEqual(n.calls.map((c) => c.google), ['token', 'get', 'ack']);
  assert.ok(n.calls[1].url.includes('/applications/com.villamediana.novelo/purchases/subscriptionsv2/tokens/play-token-1'));
  assert.equal(n.calls[1].auth, 'Bearer ya29.test');
  // Google says it expired (RTDN): no Novelo+ (here the trial was never used, so it's still there).
  sub = { ...sub, subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED', lineItems: [{ productId: 'novelo_plus', expiryTime: new Date(NOW + 30 * DAY).toISOString() }] };
  const later = NOW + 31 * DAY;
  const note = { message: { data: btoa(JSON.stringify({ subscriptionNotification: { purchaseToken: 'play-token-1', subscriptionId: 'novelo_plus', notificationType: 13 } })) } };
  assert.equal((await call(e, 'POST', '/play/rtdn?key=errada', { body: note }, later, n.fetcher)).status, 401);
  assert.equal((await call(e, 'POST', '/play/rtdn?key=rtdn-chave', { body: note }, later, n.fetcher)).status, 204);
  const fresh = await start(e, { now: later });
  assert.equal(fresh.status.plan, 'trial');
  assert.equal(fresh.status.sub_until, null);
});

test('a purchase Google doesn\'t vouch for gives nothing; without Google set up, it says so', async () => {
  const e = env({ GOOGLE_SERVICE_ACCOUNT_JSON: SERVICE, PLAY_PACKAGE: 'com.villamediana.novelo' });
  const { token } = await start(e);
  const n = net(undefined, { sub: { subscriptionState: 'SUBSCRIPTION_STATE_PENDING', lineItems: [] } });
  const r = await call(e, 'POST', '/v1/purchase', { token, body: { store: 'play', token: 'x' } }, NOW, n.fetcher);
  assert.equal(r.status, 402);
  assert.equal(r.body.error, 'not_entitled');
  const bare = env();
  const t2 = (await start(bare)).token;
  assert.equal((await call(bare, 'POST', '/v1/purchase', { token: t2, body: { store: 'play', token: 'x' } })).body.error, 'billing_not_configured');
});

// ——— Money and models ———

test('every attempt goes to the telemetry: model, plan, tokens, cost; a failing model gives way to the next', async () => {
  const e = env();
  const { token } = await start(e);
  const n = net((body) => (body.model === DEFAULTS.free_models[0] ? { status: 503 } : { status: 200, text: PLAN, cost: 0, inTok: 3000, outTok: 500 }));
  assert.equal((await organize(e, token, 1, 'talk', NOW, n)).status, 200);
  assert.deepEqual(n.models(), DEFAULTS.free_models.slice(0, 2));
  const rows = e.DB.raw.prepare('SELECT model, tier, requests, ok, failed, tokens_in, tokens_out FROM calls ORDER BY model').all().map((r) => ({ ...r }));
  assert.deepEqual(rows.find((r) => r.model === DEFAULTS.free_models[0]), { model: DEFAULTS.free_models[0], tier: 'trial', requests: 1, ok: 0, failed: 1, tokens_in: 0, tokens_out: 0 });
  assert.deepEqual(rows.find((r) => r.model === DEFAULTS.free_models[1]), { model: DEFAULTS.free_models[1], tier: 'trial', requests: 1, ok: 1, failed: 0, tokens_in: 3000, tokens_out: 500 });
});

test('a model that refuses the JSON mode is asked again plainly', async () => {
  const e = env();
  const { token } = await start(e);
  const n = net((body) => (body.response_format ? { status: 400 } : { status: 200, text: PLAN, cost: 0 }));
  assert.equal((await organize(e, token, 1, 'talk', NOW, n)).status, 200);
  assert.equal(n.calls.length, 2);
  assert.equal(n.calls[1].body.reasoning, undefined);
});

test('nobody answering with a plan is a 502, and the day isn\'t spent', async () => {
  const e = env();
  const { token } = await start(e);
  const n = net(() => ({ status: 200, text: 'não sei', cost: 0 }));
  assert.equal((await organize(e, token, 1, 'talk', NOW, n)).status, 502);
  assert.equal((await call(e, 'GET', '/v1/me', { token })).body.status.talk.day.used, 0);
});

test('the paid models have a ceiling a day, for everyone but the owner', async () => {
  const e = env();
  const { token } = await start(e);
  await call(e, 'POST', '/v1/purchase', { token, body: { store: 'test' } });
  const expensive = net(() => ({ status: 200, text: PLAN, cost: 6 }));
  assert.equal((await organize(e, token, 1, 'talk', NOW, expensive)).status, 200);
  const busy = await organize(e, token, 2, 'talk', NOW, expensive);
  assert.equal(busy.status, 503);
  assert.equal(busy.body.error, 'busy');
  // The owner (marked in the panel) goes on, without limits.
  const owner = await start(e, { install: '0a0b0c0d-1111-4222-8333-444455556666', device: 'b'.repeat(64) });
  const c = await adminLogin(e);
  const id = e.DB.raw.prepare('SELECT account FROM installs WHERE id = ?').get('0a0b0c0d-1111-4222-8333-444455556666').account;
  assert.equal((await adminCall(e, c, 'POST', '/admin/api/accounts', { id, action: 'plan', plan: 'owner' })).status, 200);
  for (let i = 0; i < 70; i++) assert.equal((await organize(e, owner.token, `o${i}`, 'talk', NOW, expensive)).status, 200);
  assert.deepEqual((await call(e, 'GET', '/v1/me', { token: owner.token })).body.status.talk.day, { used: 70, limit: null });
});

test('nothing she says is kept on the server', async () => {
  const e = env();
  const { token } = await start(e);
  await organize(e, token, 'segredo-123');
  const all = ['accounts', 'installs', 'usage', 'calls', 'audit'].map((t) => JSON.stringify(e.DB.raw.prepare(`SELECT * FROM ${t}`).all())).join();
  assert.ok(!all.includes('fala segredo-123'));
  assert.ok(!all.includes('Ligar pro Pedro'));
});

test('nobody makes trials by the hundred from one address', async () => {
  const e = env();
  const c = await adminLogin(e);
  await adminCall(e, c, 'PUT', '/admin/api/config', { patch: { max_new_per_ip: 2 } });
  await start(e, { device: '1'.repeat(64) });
  await start(e, { device: '2'.repeat(64) });
  assert.equal((await call(e, 'POST', '/v1/session', { body: { install: INSTALL, device: '3'.repeat(64) } })).status, 429);
  assert.equal((await call(e, 'POST', '/v1/session', { body: { install: INSTALL, device: '3'.repeat(64) }, ip: '5.6.7.8' })).status, 200);
});

// ——— The panel ———

test('the panel takes the key and the authenticator code, and counts wrong tries', async () => {
  const e = env();
  assert.equal((await call(e, 'GET', '/admin/api/overview', { admin: true })).status, 401);
  assert.equal((await call(e, 'POST', '/admin/api/login', { admin: true, body: { key: e.ADMIN_KEY, code: '000000' } })).status, 401);
  assert.equal((await call(e, 'POST', '/admin/api/login', { admin: true, body: { key: 'errada', code: await totp(TOTP, NOW) } })).status, 401);
  const c = await adminLogin(e);
  assert.match(c, /^nv_admin=/);
  assert.equal((await adminCall(e, c, 'GET', '/admin/api/overview')).status, 200);
  // A change without the panel's header (another site posting) is refused.
  assert.equal((await call(e, 'PUT', '/admin/api/config', { cookie: c, admin: true, header: false, body: { patch: { trial_days: 3 } } })).status, 401);
  // Ten wrong tries in a day from one address: no more tries today.
  for (let i = 0; i < 8; i++) await call(e, 'POST', '/admin/api/login', { admin: true, body: { key: 'x', code: '1' } });
  assert.equal((await call(e, 'POST', '/admin/api/login', { admin: true, body: { key: e.ADMIN_KEY, code: await totp(TOTP, NOW) } })).status, 429);
  // The session ends.
  assert.equal((await adminCall(e, c, 'GET', '/admin/api/me', undefined, NOW + 13 * 3_600_000)).status, 401);
});

test('the panel sees spending, models and people, and changes limits and plans', async () => {
  const e = env();
  const { token } = await start(e);
  await organize(e, token, 1, 'talk', NOW, net(() => ({ status: 200, text: PLAN, cost: 0, inTok: 10, outTok: 5 })));
  await call(e, 'POST', '/v1/purchase', { token, body: { store: 'test' } });
  await organize(e, token, 2, 'talk', NOW, net(() => ({ status: 200, text: PLAN, cost: 0.002, inTok: 10, outTok: 5 })));
  const c = await adminLogin(e);
  const o = (await adminCall(e, c, 'GET', '/admin/api/overview')).body;
  assert.equal(o.budget.usd_today, 0.002);
  assert.equal(o.days.at(-1).talk, 2);
  assert.equal(o.people.plans.plus, 1);
  assert.deepEqual(o.models.map((m) => m.tier).sort(), ['plus', 'trial']);
  const people = (await adminCall(e, c, 'GET', '/admin/api/accounts')).body.accounts;
  assert.equal(people[0].plan, 'plus');
  assert.equal(people[0].agent, 'Novelo/1.26.0 (Android 16)');
  // New limits, right away.
  assert.equal((await adminCall(e, c, 'PUT', '/admin/api/config', { patch: { limits: { plus: { talk: { day: 2 } } } } })).status, 200);
  const st = (await call(e, 'GET', '/v1/me', { token })).body.status;
  assert.deepEqual(st.talk.day, { used: 2, limit: 2 });
  assert.equal(st.talk.week.limit, 250);
  assert.equal((await organize(e, token, 3)).body.error, 'daily_limit');
  // A wrong config is refused, field named.
  const bad = await adminCall(e, c, 'PUT', '/admin/api/config', { patch: { limits: { plus: { talk: { day: -1 } } } } });
  assert.equal(bad.status, 400);
  assert.equal(bad.body.field, 'limits.plus.talk.day');
  // The testers' mode: everyone is a tester (no plan or payment in the app).
  await adminCall(e, c, 'PUT', '/admin/api/config', { patch: { testers_mode: true } });
  const other = await start(e, { install: '00000000-0000-4000-8000-000000000002', device: 'c'.repeat(64) });
  assert.equal(other.status.plan, 'tester');
  // Every change is in the record.
  const log = (await adminCall(e, c, 'GET', '/admin/api/audit')).body.audit.map((a) => a.action);
  assert.ok(log.includes('config') && log.includes('login'));
});

test('the panel page goes with a strict security policy', async () => {
  const e = env();
  const r = await call(e, 'GET', '/admin');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-security-policy'), /script-src 'self'/);
  assert.equal(r.headers.get('x-frame-options'), 'DENY');
});

test('the config is checked field by field', () => {
  assert.equal(validate({ trial_days: 7 }).ok, true);
  assert.equal(validate({ trial_days: -1 }).ok, false);
  assert.equal(validate({ nope: 1 }).error, 'unknown:nope');
  assert.equal(validate({ paid_models: ['openai/gpt-6-luna; rm -rf'] }).ok, false);
  assert.equal(validate({ limits: { trial: { talk: { day: null } } } }).ok, true);
});
