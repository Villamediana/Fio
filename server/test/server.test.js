import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { handle, day, resetAt, sign, verify, planOf, PLANS } from '../src/index.js';

/** D1 over node:sqlite: the few calls the worker makes (prepare/bind/first/run/all, batch). */
function d1() {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(new URL('../migrations/0001_init.sql', import.meta.url), 'utf8'));
  return {
    raw: db,
    prepare(sql) {
      const st = db.prepare(sql);
      let args = [];
      const s = {
        bind(...a) { args = a; return s; },
        async first(col) { const r = st.get(...args); return r ? (col ? r[col] : { ...r }) : null; },
        async run() { const r = st.run(...args); return { success: true, meta: { changes: Number(r.changes) } }; },
        async all() { return { results: st.all(...args).map((r) => ({ ...r })) }; },
      };
      return s;
    },
    async batch(stmts) { const out = []; for (const s of stmts) out.push(await s.run()); return out; },
  };
}

const PLAN = '{"reply":"Guardei.","create":[{"kind":"task","title":"Ligar pro Pedro"}]}';

/** The model at OpenRouter: answers [reply] (a plan by default), counting the calls and keeping what came. */
function model(reply = () => ({ status: 200, text: PLAN, cost: 0.0004 })) {
  const calls = [];
  const fetcher = async (url, init) => {
    const body = JSON.parse(init.body);
    calls.push({ url, auth: init.headers.authorization, body });
    const r = reply(body, calls.length);
    if (r.status !== 200) return new Response('{"error":{}}', { status: r.status });
    return new Response(JSON.stringify({ choices: [{ message: { content: r.text } }], usage: { cost: r.cost } }), { status: 200 });
  };
  return { calls, fetcher };
}

function env(db = d1()) {
  return { DB: db, OPENROUTER_KEY: 'sk-or-test', TOKEN_SECRET: 'segredo-de-teste-com-tamanho', ADMIN_KEY: 'admin-key-de-teste-bem-comprida', MODEL: 'openai/gpt-6-luna', FALLBACK_MODELS: 'google/gemini-x', DAILY_BUDGET_USD: '5', MAX_NEW_PER_IP: '20' };
}

// 2026-11-12 15:00 in Brasília.
const NOW = Date.UTC(2026, 10, 12, 18, 0, 0);
const INSTALL = '3f1c2a9e-8b7d-4c6e-9a1b-2c3d4e5f6a7b';

function req(method, path, { body, token, ip = '1.2.3.4', admin } = {}) {
  const headers = { 'content-type': 'application/json', 'cf-connecting-ip': ip, 'user-agent': 'Novelo/1.26.0 (Android 16)' };
  if (token) headers.authorization = `Bearer ${token}`;
  if (admin) headers.authorization = `Bearer ${admin}`;
  return new Request(`https://novelo.test${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
}

async function call(e, method, path, opts = {}, now = NOW, fetcher) {
  const r = await handle(req(method, path, opts), e, now, fetcher);
  return { status: r.status, body: await r.json() };
}

async function start(e, install = INSTALL, now = NOW) {
  const r = await call(e, 'POST', '/v1/session', { body: { install } }, now);
  assert.equal(r.status, 200);
  return r.body.token;
}

const say = (turn, kind = 'talk') => ({ system: 'Você é o Novelo.', user: `O QUE ELA DISSE: fala ${turn}`, kind, turn: String(turn) });

test('the day turns at midnight in Brasília', () => {
  assert.equal(day(Date.UTC(2026, 10, 13, 2, 59)), '2026-11-12'); // 23:59 BRT
  assert.equal(day(Date.UTC(2026, 10, 13, 3, 0)), '2026-11-13'); // 00:00 BRT
  assert.equal(resetAt(NOW), Date.UTC(2026, 10, 13, 3, 0));
});

test('a pass is the install signed by the server, and only that', async () => {
  const t = await sign('s', INSTALL, NOW);
  assert.equal(await verify('s', t, NOW + 1000), INSTALL);
  assert.equal(await verify('outro', t, NOW), null);
  assert.equal(await verify('s', t.replace(INSTALL, INSTALL.replace('3f', '4f')), NOW), null);
  assert.equal(await verify('s', t, NOW + 31 * 86_400_000), null);
  assert.equal(await verify('s', 'v1.x.y.z', NOW), null);
});

test('a new install gets a pass and the free plan', async () => {
  const e = env();
  const r = await call(e, 'POST', '/v1/session', { body: { install: INSTALL } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.usage, { plan: 'free', reset_at: resetAt(NOW), talk: { used: 0, limit: 10 }, photo: { used: 0, limit: 3 } });
  assert.equal((await call(e, 'POST', '/v1/session', { body: { install: 'nope' } })).status, 400);
});

test('organizing calls the model with the server key, privately, and counts the day', async () => {
  const e = env();
  const token = await start(e);
  const m = model();
  const r = await call(e, 'POST', '/v1/organize', { token, body: say(1) }, NOW, m.fetcher);
  assert.equal(r.status, 200);
  assert.equal(r.body.text, PLAN);
  assert.equal(r.body.usage.talk.used, 1);
  assert.equal(m.calls.length, 1);
  assert.equal(m.calls[0].auth, 'Bearer sk-or-test');
  assert.equal(m.calls[0].body.model, 'openai/gpt-6-luna');
  assert.deepEqual(m.calls[0].body.provider, { data_collection: 'deny' });
  assert.deepEqual(m.calls[0].body.response_format, { type: 'json_object' });
  assert.equal(m.calls[0].body.messages[1].content, 'O QUE ELA DISSE: fala 1');
  // Nothing she said is kept: only the count.
  const kept = JSON.stringify(e.DB.raw.prepare('SELECT * FROM usage').all()) + JSON.stringify(e.DB.raw.prepare('SELECT * FROM installs').all());
  assert.ok(!kept.includes('fala 1'));
});

test('without a valid pass nothing goes to the model', async () => {
  const e = env();
  const m = model();
  assert.equal((await call(e, 'POST', '/v1/organize', { body: say(1) }, NOW, m.fetcher)).status, 401);
  assert.equal((await call(e, 'POST', '/v1/organize', { token: 'v1.abc', body: say(1) }, NOW, m.fetcher)).status, 401);
  assert.equal(m.calls.length, 0);
});

test('the free plan organizes 10 talks and 3 photos a day, then waits for midnight', async () => {
  const e = env();
  const token = await start(e);
  const m = model();
  for (let i = 1; i <= PLANS.free.talk; i++) assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(i) }, NOW, m.fetcher)).status, 200);
  const over = await call(e, 'POST', '/v1/organize', { token, body: say(11) }, NOW, m.fetcher);
  assert.equal(over.status, 429);
  assert.equal(over.body.error, 'daily_limit');
  assert.equal(over.body.kind, 'talk');
  assert.equal(over.body.usage.reset_at, resetAt(NOW));
  // Photos have their own count.
  for (let i = 1; i <= PLANS.free.photo; i++) assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(`p${i}`, 'photo') }, NOW, m.fetcher)).status, 200);
  assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say('p4', 'photo') }, NOW, m.fetcher)).status, 429);
  assert.equal(m.calls.length, PLANS.free.talk + PLANS.free.photo);
  // The next day, all of it again.
  const tomorrow = resetAt(NOW) + 1000;
  const next = await call(e, 'POST', '/v1/organize', { token, body: say(11) }, tomorrow, m.fetcher);
  assert.equal(next.status, 200);
  assert.equal(next.body.usage.talk.used, 1);
});

test('a talk that looks something up counts once (up to a few requests)', async () => {
  const e = env();
  const token = await start(e);
  const m = model();
  for (let i = 0; i < 3; i++) assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(7) }, NOW, m.fetcher)).status, 200);
  const me = await call(e, 'GET', '/v1/me', { token });
  assert.equal(me.body.usage.talk.used, 1);
  assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(7) }, NOW, m.fetcher)).body.error, 'turn_limit');
});

test('a failed answer does not count, and another model is tried first', async () => {
  const e = env();
  const token = await start(e);
  // The first model is down; the reserve answers.
  const m = model((body) => (body.model === 'openai/gpt-6-luna' ? { status: 503 } : { status: 200, text: PLAN, cost: 0.001 }));
  const r = await call(e, 'POST', '/v1/organize', { token, body: say(1) }, NOW, m.fetcher);
  assert.equal(r.status, 200);
  assert.deepEqual(m.calls.map((c) => c.body.model), ['openai/gpt-6-luna', 'google/gemini-x']);
  // Nobody answers with a plan: 502, and the day isn't spent.
  const bad = model(() => ({ status: 200, text: 'não sei', cost: 0 }));
  assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(2) }, NOW, bad.fetcher)).status, 502);
  assert.equal((await call(e, 'GET', '/v1/me', { token })).body.usage.talk.used, 1);
});

test('the day has a spending ceiling for everyone but the owner', async () => {
  const e = env();
  const token = await start(e);
  const owner = '0a0b0c0d-1111-4222-8333-444455556666';
  const ownerToken = await start(e, owner);
  assert.equal((await call(e, 'POST', '/admin/plan', { admin: e.ADMIN_KEY, body: { install: owner, plan: 'owner' } })).status, 200);
  const m = model(() => ({ status: 200, text: PLAN, cost: 6 }));
  assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(1) }, NOW, m.fetcher)).status, 200);
  const busy = await call(e, 'POST', '/v1/organize', { token, body: say(2) }, NOW, m.fetcher);
  assert.equal(busy.status, 503);
  assert.equal(busy.body.error, 'busy');
  assert.equal((await call(e, 'POST', '/v1/organize', { token: ownerToken, body: say(1) }, NOW, m.fetcher)).status, 200);
});

test('the owner and plus plans', async () => {
  const e = env();
  const token = await start(e);
  assert.equal((await call(e, 'POST', '/admin/plan', { admin: 'errada', body: { install: INSTALL, plan: 'owner' } })).status, 401);
  assert.equal((await call(e, 'POST', '/admin/plan', { admin: e.ADMIN_KEY, body: { install: INSTALL, plan: 'plus' } })).status, 200);
  assert.deepEqual((await call(e, 'GET', '/v1/me', { token })).body.usage.talk, { used: 0, limit: 50 });
  await call(e, 'POST', '/admin/plan', { admin: e.ADMIN_KEY, body: { install: INSTALL, plan: 'owner' } });
  const m = model();
  for (let i = 1; i <= 60; i++) assert.equal((await call(e, 'POST', '/v1/organize', { token, body: say(i) }, NOW, m.fetcher)).status, 200);
  assert.deepEqual((await call(e, 'GET', '/v1/me', { token })).body.usage.talk, { used: 60, limit: null });
  const list = await call(e, 'GET', '/admin/installs', { admin: e.ADMIN_KEY });
  assert.equal(list.body.installs[0].agent, 'Novelo/1.26.0 (Android 16)');
});

test('nobody makes installs by the hundred from one address', async () => {
  const e = { ...env(), MAX_NEW_PER_IP: '2' };
  await start(e, '00000000-0000-4000-8000-000000000001');
  await start(e, '00000000-0000-4000-8000-000000000002');
  const third = await call(e, 'POST', '/v1/session', { body: { install: '00000000-0000-4000-8000-000000000003' } });
  assert.equal(third.status, 429);
  // An install already there still gets its pass.
  assert.equal((await call(e, 'POST', '/v1/session', { body: { install: '00000000-0000-4000-8000-000000000001' } })).status, 200);
  // Another address can.
  assert.equal((await call(e, 'POST', '/v1/session', { body: { install: '00000000-0000-4000-8000-000000000003' }, ip: '5.6.7.8' })).status, 200);
});

test('a plan is the first JSON object of the answer', () => {
  assert.deepEqual(planOf('```json\n{"reply":"ok"}\n```'), { reply: 'ok' });
  assert.equal(planOf('não sei'), null);
  assert.equal(planOf('[1,2]'), null);
});
