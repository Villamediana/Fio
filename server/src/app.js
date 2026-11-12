/**
 * O servidor do Novelo. O app não leva chave nenhuma: pede aqui, o servidor sabe o plano da pessoa,
 * conta o dia e a semana dela e só então chama o modelo do plano com a chave dele.
 *
 * App:
 *   POST /v1/session   {install, device?}             → passe + situação (plano, limites, uso)
 *   GET  /v1/me                                       → situação
 *   POST /v1/organize  {system, user, kind, turn}     → resposta do modelo + situação
 *   POST /v1/purchase  {store: play|test, token?}     → assinatura conferida + situação
 * Google Play:
 *   POST /play/rtdn?key=…                             → aviso de mudança numa assinatura (Pub/Sub)
 * Painel (/admin): login com chave + código do autenticador, e a API em /admin/api/…
 *
 * Planos: trial (teste, modelos grátis, começa no primeiro uso), plus (Novelo+, modelos pagos),
 * tester e owner (o painel dá), ended (o teste acabou sem assinatura), blocked.
 * Nada do que a pessoa fala ou escreve é guardado ou vai pro log.
 */
import { DEFAULTS, PLANS, KINDS, load, save, validate } from './config.js';
import { day, dayEnd, weekStart, weekEnd, days, DAY_MS } from './time.js';
import { hmac, same, INSTALL, sign, verify, ADMIN_COOKIE, adminSession, adminValid, cookie, totpOk } from './auth.js';
import * as play from './play.js';

/** Uma fala que procura algo antes de responder leva mais de um pedido; conta uma vez só, até este tanto. */
export const MAX_REQUESTS_PER_TURN = 3;

const DEVICE = /^[0-9a-f]{64}$/;
const MAX_SYSTEM = 40_000;
const MAX_USER = 60_000;
const MODEL_TIMEOUT_MS = 70_000;
const ADMIN_MAX_FAILS = 10;

// ——— Respostas ———

const SECURITY = {
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cache-control': 'no-store',
};

function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', ...SECURITY, ...headers } });
}

const fail = (status, error, extra = {}) => json(status, { error, ...extra });

async function body(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

/** O endereço de quem pede (o main.js põe em x-novelo-ip), cifrado: nunca guardado como é. */
async function ipOf(env, request) {
  return hmac(env.TOKEN_SECRET, `ip:${request.headers.get('x-novelo-ip') || ''}`);
}

// ——— O plano e o uso ———

/** O plano que vale agora pra [acc]. */
export function planOf(acc, cfg, now) {
  if (acc.plan === 'blocked') return 'blocked';
  if (acc.plan === 'owner' || acc.plan === 'tester') return acc.plan;
  if (cfg.testers_mode) return 'tester';
  if ((acc.sub_until || 0) > now) return 'plus';
  if (!acc.trial_start || acc.trial_start + cfg.trial_days * DAY_MS > now) return 'trial';
  return 'ended';
}

/** Que modelos o plano usa: os grátis, os pagos, ou nenhum. */
function tierOf(plan, cfg) {
  if (plan === 'trial') return 'free';
  if (plan === 'tester') return cfg.tester_models === 'free' ? 'free' : 'paid';
  if (plan === 'plus' || plan === 'owner') return 'paid';
  return null;
}

/** Desde quando conta a "semana": no teste, o teste inteiro; senão, a semana (segunda 00:00 de Brasília). */
function windowStart(plan, acc, now) {
  return plan === 'trial' ? day(acc.trial_start || now) : weekStart(now);
}

/** Quando a "semana" vira: o fim do teste, ou a segunda-feira seguinte. */
function windowEnd(plan, acc, cfg, now) {
  return plan === 'trial' ? (acc.trial_start || now) + cfg.trial_days * DAY_MS : weekEnd(now);
}

async function used(env, account, since, now) {
  const today = day(now);
  const rows = await env.DB.prepare('SELECT kind, day, COUNT(*) AS n FROM usage WHERE account = ? AND day >= ? GROUP BY kind, day').bind(account, since).all();
  const out = { talk: { day: 0, week: 0 }, photo: { day: 0, week: 0 } };
  for (const r of rows.results || []) {
    if (!out[r.kind]) continue;
    out[r.kind].week += Number(r.n);
    if (r.day === today) out[r.kind].day += Number(r.n);
  }
  return out;
}

/** O que o app mostra: o plano, até quando, e cada limite com o que já foi usado. */
async function situation(env, acc, cfg, now) {
  const plan = planOf(acc, cfg, now);
  const lim = cfg.limits[plan] || null;
  const u = await used(env, acc.id, windowStart(plan, acc, now), now);
  const count = (kind, w) => ({ used: u[kind][w], limit: lim ? lim[kind][w] ?? null : 0 });
  return {
    plan,
    trial_days: cfg.trial_days,
    trial_ends: acc.trial_start ? acc.trial_start + cfg.trial_days * DAY_MS : null,
    sub_until: acc.sub_until || null,
    sub_store: acc.sub_store || null,
    talk: { day: count('talk', 'day'), week: count('talk', 'week') },
    photo: { day: count('photo', 'day'), week: count('photo', 'week') },
    day_resets: dayEnd(now),
    week_resets: windowEnd(plan, acc, cfg, now),
    offer: { product: cfg.play.product, base_plan: cfg.play.base_plan, test_purchases: cfg.test_purchases },
  };
}

async function accountOf(env, request, now) {
  const auth = request.headers.get('authorization') || '';
  const install = await verify(env.TOKEN_SECRET, auth.startsWith('Bearer ') ? auth.slice(7) : '', now);
  if (!install) return null;
  return env.DB.prepare('SELECT a.* FROM installs i JOIN accounts a ON a.id = i.account WHERE i.id = ?').bind(install).first();
}

// ——— O modelo ———

/** O primeiro objeto JSON da resposta, se é um (o plano que o app espera); senão null. */
export function planJson(text) {
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    const v = JSON.parse(text.slice(start, end + 1));
    return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

async function record(env, today, plan, model, r) {
  await env.DB.prepare(
    `INSERT INTO calls (day, tier, model, requests, ok, failed, tokens_in, tokens_out, usd, ms) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (day, tier, model) DO UPDATE SET requests = requests + 1, ok = ok + excluded.ok, failed = failed + excluded.failed,
       tokens_in = tokens_in + excluded.tokens_in, tokens_out = tokens_out + excluded.tokens_out, usd = usd + excluded.usd, ms = ms + excluded.ms`,
  ).bind(today, plan, model, r.ok ? 1 : 0, r.ok ? 0 : 1, r.tokensIn || 0, r.tokensOut || 0, r.usd || 0, Math.round(r.ms || 0)).run();
}

/**
 * A resposta pra [system]/[user] nos [models], em ordem: o que falhar (fora do ar, ocupado, lento,
 * sem devolver um plano) dá a vez ao próximo. Pago: só provedores que não guardam nem treinam com o
 * que recebem. Cada tentativa vai pra telemetria (dia, plano, modelo, tokens, custo, tempo).
 */
async function ask(env, { key, models, paid, plan, system, user, now, fetcher }) {
  const today = day(now);
  let last = new Error('no model');
  for (const model of models) {
    // Com o modo JSON e pouco raciocínio; quem recusa isso (400) recebe de novo sem.
    for (const plain of [false, true]) {
      const t0 = performance.now();
      try {
        const r = await fetcher('https://openrouter.ai/api/v1/chat/completions', {
          method: 'POST',
          headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json', 'x-title': 'Novelo' },
          body: JSON.stringify({
            model,
            messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
            ...(plain ? {} : { response_format: { type: 'json_object' }, reasoning: { effort: 'low' } }),
            max_tokens: 4000,
            ...(paid ? { provider: { data_collection: 'deny' } } : {}),
            usage: { include: true },
          }),
          signal: AbortSignal.timeout(MODEL_TIMEOUT_MS),
        });
        if (!r.ok) {
          await record(env, today, plan, model, { ok: false, ms: performance.now() - t0 });
          last = new Error(`${model}: ${r.status}`);
          if (r.status === 400 && !plain) continue;
          break;
        }
        const data = await r.json();
        const text = data?.choices?.[0]?.message?.content || '';
        const u = data?.usage || {};
        const good = !!planJson(text);
        await record(env, today, plan, model, { ok: good, tokensIn: u.prompt_tokens, tokensOut: u.completion_tokens, usd: Number(u.cost) || 0, ms: performance.now() - t0 });
        if (good) return { text, model };
        last = new Error(`${model}: not a plan`);
        break;
      } catch (e) {
        await record(env, today, plan, model, { ok: false, ms: performance.now() - t0 });
        last = e;
        break;
      }
    }
  }
  throw last;
}

// ——— As rotas do app ———

async function session(env, request, now) {
  const b = await body(request);
  const install = String(b?.install || '').toLowerCase();
  const device = b?.device == null ? null : String(b.device).toLowerCase();
  if (!INSTALL.test(install) || (device !== null && !DEVICE.test(device))) return fail(400, 'bad_request');
  const cfg = await load(env.DB);
  const agent = (request.headers.get('user-agent') || '').slice(0, 80);
  // A pessoa é o aparelho (o teste é um por celular, mesmo reinstalando); sem ele, a instalação.
  const id = device ? `d:${await hmac(env.TOKEN_SECRET, `device:${device}`)}` : `i:${install}`;
  let acc = await env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(id).first();
  if (!acc) {
    const today = day(now);
    const ip = await ipOf(env, request);
    const minted = await env.DB.prepare('SELECT n FROM mints WHERE day = ? AND ip = ?').bind(today, ip).first('n');
    if ((minted || 0) >= cfg.max_new_per_ip) return fail(429, 'too_many_installs');
    await env.DB.batch([
      env.DB.prepare('INSERT OR IGNORE INTO accounts (id, created_at, last_at, agent) VALUES (?, ?, ?, ?)').bind(id, now, now, agent),
      env.DB.prepare('INSERT INTO mints (day, ip, n) VALUES (?, ?, 1) ON CONFLICT (day, ip) DO UPDATE SET n = n + 1').bind(today, ip),
    ]);
    acc = await env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(id).first();
  } else {
    await env.DB.prepare('UPDATE accounts SET last_at = ?, agent = ? WHERE id = ?').bind(now, agent, id).run();
  }
  await env.DB.prepare('INSERT INTO installs (id, account, created_at, last_at) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET account = excluded.account, last_at = excluded.last_at').bind(install, id, now, now).run();
  return json(200, { token: await sign(env.TOKEN_SECRET, install, now), status: await situation(env, acc, cfg, now) });
}

async function me(env, request, now) {
  const acc = await accountOf(env, request, now);
  if (!acc) return fail(401, 'bad_token');
  return json(200, { status: await situation(env, acc, await load(env.DB), now) });
}

async function organize(env, request, now, fetcher) {
  const acc = await accountOf(env, request, now);
  if (!acc) return fail(401, 'bad_token');
  const cfg = await load(env.DB);
  const plan = planOf(acc, cfg, now);
  if (plan === 'blocked') return fail(403, 'blocked');
  if (plan === 'ended') return fail(402, 'no_plan', { status: await situation(env, acc, cfg, now) });

  const b = await body(request);
  const kind = b?.kind;
  const turn = String(b?.turn || '').slice(0, 64);
  const { system, user } = b || {};
  if (!KINDS.includes(kind) || !turn || typeof system !== 'string' || typeof user !== 'string' || !system || !user) return fail(400, 'bad_request');
  if (system.length > MAX_SYSTEM || user.length > MAX_USER) return fail(413, 'too_long');

  // O teste começa no primeiro uso da inteligência do Novelo.
  if (plan === 'trial' && !acc.trial_start) {
    await env.DB.prepare('UPDATE accounts SET trial_start = ? WHERE id = ? AND trial_start IS NULL').bind(now, acc.id).run();
    acc.trial_start = now;
  }

  const today = day(now);
  const seen = await env.DB.prepare('SELECT requests FROM usage WHERE account = ? AND day = ? AND kind = ? AND turn = ?').bind(acc.id, today, kind, turn).first('requests');
  if (seen != null) {
    // A mesma fala de novo (procurou algo e volta com o que achou): já contou.
    if (seen >= MAX_REQUESTS_PER_TURN) return fail(429, 'turn_limit', { status: await situation(env, acc, cfg, now) });
  } else {
    const u = await used(env, acc.id, windowStart(plan, acc, now), now);
    const lim = cfg.limits[plan][kind];
    for (const w of ['day', 'week']) {
      if (lim[w] != null && u[kind][w] >= lim[w]) {
        return fail(429, w === 'day' ? 'daily_limit' : 'weekly_limit', { kind, status: await situation(env, acc, cfg, now) });
      }
    }
  }

  const tier = tierOf(plan, cfg);
  const paid = tier === 'paid';
  if (paid && plan !== 'owner') {
    // O teto do dia nos modelos pagos: nenhum erro vira uma conta alta.
    const spent = await env.DB.prepare('SELECT COALESCE(SUM(usd), 0) AS usd FROM calls WHERE day = ?').bind(today).first('usd');
    if (Number(spent) >= cfg.daily_budget_usd) return fail(503, 'busy', { reset_at: dayEnd(now) });
  }
  const key = paid ? env.OPENROUTER_PAID_KEY : env.OPENROUTER_FREE_KEY;
  if (!key) return fail(503, 'not_configured');

  let answer;
  try {
    answer = await ask(env, { key, models: paid ? cfg.paid_models : cfg.free_models, paid, plan, system, user, now, fetcher });
  } catch {
    // Não conta: o pedido não deu em nada.
    return fail(502, 'model_unavailable');
  }
  await env.DB.batch([
    env.DB.prepare('INSERT INTO usage (account, day, kind, turn, requests) VALUES (?, ?, ?, ?, 1) ON CONFLICT (account, day, kind, turn) DO UPDATE SET requests = requests + 1').bind(acc.id, today, kind, turn),
    env.DB.prepare('UPDATE accounts SET last_at = ? WHERE id = ?').bind(now, acc.id),
  ]);
  return json(200, { text: answer.text, status: await situation(env, acc, cfg, now) });
}

/** O Novelo+ da conta: até o fim da assinatura que mais dura (as que não valem mais têm until 0). */
async function refreshSub(env, account) {
  const s = await env.DB.prepare('SELECT MAX(until) AS until, store FROM subscriptions WHERE account = ? AND until > 0 ORDER BY until DESC').bind(account).first();
  await env.DB.prepare('UPDATE accounts SET sub_until = ?, sub_store = ? WHERE id = ?').bind(s?.until || null, s?.until ? s.store : null, account).run();
}

async function purchase(env, request, now, fetcher) {
  const acc = await accountOf(env, request, now);
  if (!acc) return fail(401, 'bad_token');
  const cfg = await load(env.DB);
  const b = await body(request);
  if (b?.store === 'test') {
    // O app do GitHub: a assinatura é de mentira (dá pra testar tudo), enquanto o painel deixar.
    if (!cfg.test_purchases) return fail(403, 'test_purchases_off');
    const until = now + cfg.test_purchase_days * DAY_MS;
    await env.DB.prepare('INSERT INTO subscriptions (token, account, store, product, state, until, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)').bind(`test:${acc.id}:${now}`, acc.id, 'test', cfg.play.product, 'TEST', until, now).run();
  } else if (b?.store === 'play') {
    const token = String(b?.token || '');
    if (!token || token.length > 2000) return fail(400, 'bad_request');
    if (!env.GOOGLE_SERVICE_ACCOUNT_JSON || !env.PLAY_PACKAGE) return fail(503, 'billing_not_configured');
    let s;
    try {
      s = await play.subscription(env, env.PLAY_PACKAGE, token, now, fetcher);
    } catch {
      return fail(502, 'billing_unavailable');
    }
    if (!s.entitled) return fail(402, 'not_entitled', { state: s.state });
    // O comprovante é de quem o apresenta (a mesma conta Google, outro celular ou reinstalado).
    const before = await env.DB.prepare('SELECT account FROM subscriptions WHERE token = ?').bind(token).first('account');
    await env.DB.prepare(
      `INSERT INTO subscriptions (token, account, store, product, state, until, updated_at) VALUES (?, ?, 'play', ?, ?, ?, ?)
       ON CONFLICT (token) DO UPDATE SET account = excluded.account, product = excluded.product, state = excluded.state, until = excluded.until, updated_at = excluded.updated_at`,
    ).bind(token, acc.id, s.product, s.state, s.until, now).run();
    if (before && before !== acc.id) await refreshSub(env, before);
    if (s.ack === 'pending') {
      try {
        await play.acknowledge(env, env.PLAY_PACKAGE, s.product || cfg.play.product, token, now, fetcher);
      } catch {
        // Tenta de novo no próximo aviso da Google ou na próxima conferência.
      }
    }
  } else {
    return fail(400, 'bad_request');
  }
  await refreshSub(env, acc.id);
  const fresh = await env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(acc.id).first();
  return json(200, { status: await situation(env, fresh, cfg, now) });
}

/** A Google avisa (Pub/Sub, push) que uma assinatura mudou: renovou, cancelou, venceu, voltou. */
async function rtdn(env, request, url, now, fetcher) {
  if (!env.RTDN_KEY || !same(url.searchParams.get('key'), env.RTDN_KEY)) return fail(401, 'bad_key');
  const b = await body(request);
  let note = null;
  try {
    note = JSON.parse(atob(b?.message?.data || ''));
  } catch {
    return new Response(null, { status: 204 });
  }
  const token = note?.subscriptionNotification?.purchaseToken;
  if (!token || !env.GOOGLE_SERVICE_ACCOUNT_JSON || !env.PLAY_PACKAGE) return new Response(null, { status: 204 });
  const account = await env.DB.prepare('SELECT account FROM subscriptions WHERE token = ?').bind(token).first('account');
  if (!account) return new Response(null, { status: 204 });
  try {
    const s = await play.subscription(env, env.PLAY_PACKAGE, token, now, fetcher);
    await env.DB.prepare('UPDATE subscriptions SET state = ?, until = ?, updated_at = ? WHERE token = ?').bind(s.state, s.entitled ? s.until : 0, now, token).run();
    await refreshSub(env, account);
  } catch {
    // A Google manda de novo se não respondermos 2xx: deixa ela tentar.
    return fail(502, 'billing_unavailable');
  }
  return new Response(null, { status: 204 });
}

// ——— O painel ———

const PANEL_HEADERS = {
  'content-security-policy': "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  'x-frame-options': 'DENY',
  ...SECURITY,
};

function asset(env, name) {
  const a = env.ASSETS?.[name];
  if (!a) return fail(404, 'not_found');
  return new Response(a.body, { status: 200, headers: { 'content-type': a.type, ...PANEL_HEADERS } });
}

async function isAdmin(env, request, now, mutating) {
  if (!env.ADMIN_KEY || env.ADMIN_KEY.length < 24) return false;
  if (mutating && request.headers.get('x-novelo-admin') !== '1') return false;
  return adminValid(`${env.TOKEN_SECRET}:admin`, cookie(request, ADMIN_COOKIE), now);
}

async function audit(env, now, action, detail) {
  await env.DB.prepare('INSERT INTO audit (at, action, detail) VALUES (?, ?, ?)').bind(now, action, JSON.stringify(detail ?? null).slice(0, 1000)).run();
}

async function login(env, request, now) {
  if (!env.ADMIN_KEY || env.ADMIN_KEY.length < 24) return fail(503, 'admin_not_configured');
  if (request.headers.get('x-novelo-admin') !== '1') return fail(400, 'bad_request');
  const today = day(now);
  const ip = await ipOf(env, request);
  const fails = await env.DB.prepare('SELECT n FROM admin_fails WHERE day = ? AND ip = ?').bind(today, ip).first('n');
  if ((fails || 0) >= ADMIN_MAX_FAILS) return fail(429, 'too_many_tries');
  const b = await body(request);
  const keyOk = same(b?.key, env.ADMIN_KEY);
  const codeOk = env.ADMIN_TOTP_SECRET ? await totpOk(env.ADMIN_TOTP_SECRET, b?.code, now) : true;
  if (!keyOk || !codeOk) {
    await env.DB.prepare('INSERT INTO admin_fails (day, ip, n) VALUES (?, ?, 1) ON CONFLICT (day, ip) DO UPDATE SET n = n + 1').bind(today, ip).run();
    return fail(401, 'wrong');
  }
  await audit(env, now, 'login', null);
  const value = await adminSession(`${env.TOKEN_SECRET}:admin`, now);
  return json(200, { ok: true }, { 'set-cookie': `${ADMIN_COOKIE}=${value}; Path=/admin; HttpOnly; Secure; SameSite=Strict; Max-Age=43200` });
}

function logout() {
  return json(200, { ok: true }, { 'set-cookie': `${ADMIN_COOKIE}=; Path=/admin; HttpOnly; Secure; SameSite=Strict; Max-Age=0` });
}

async function overview(env, url, now) {
  const span = Math.min(90, Math.max(1, Number(url.searchParams.get('days')) || 14));
  const today = day(now);
  const from = day(now - (span - 1) * DAY_MS);
  const cfg = await load(env.DB);
  const perDay = await env.DB.prepare('SELECT day, SUM(requests) AS requests, SUM(ok) AS ok, SUM(failed) AS failed, SUM(usd) AS usd, SUM(tokens_in) AS tokens_in, SUM(tokens_out) AS tokens_out FROM calls WHERE day >= ? GROUP BY day').bind(from).all();
  const byDay = Object.fromEntries((perDay.results || []).map((r) => [r.day, r]));
  const models = await env.DB.prepare(
    'SELECT model, tier, SUM(requests) AS requests, SUM(ok) AS ok, SUM(failed) AS failed, SUM(usd) AS usd, SUM(tokens_in) AS tokens_in, SUM(tokens_out) AS tokens_out, SUM(ms) AS ms FROM calls WHERE day >= ? GROUP BY model, tier ORDER BY requests DESC',
  ).bind(from).all();
  const usage = await env.DB.prepare('SELECT day, kind, COUNT(*) AS n, COUNT(DISTINCT account) AS people FROM usage WHERE day >= ? GROUP BY day, kind').bind(from).all();
  const activeToday = await env.DB.prepare('SELECT COUNT(DISTINCT account) AS n FROM usage WHERE day = ?').bind(today).first('n');
  const activeSpan = await env.DB.prepare('SELECT COUNT(DISTINCT account) AS n FROM usage WHERE day >= ?').bind(from).first('n');
  const accounts = await env.DB.prepare('SELECT * FROM accounts').all();
  const plans = Object.fromEntries([...PLANS, 'ended', 'blocked'].map((p) => [p, 0]));
  for (const a of accounts.results || []) plans[planOf(a, cfg, now)]++;
  const usageByDay = {};
  for (const r of usage.results || []) (usageByDay[r.day] ||= { talk: 0, photo: 0 })[r.kind] = Number(r.n);
  return json(200, {
    today,
    span,
    budget: { usd_today: Number(byDay[today]?.usd || 0), limit: cfg.daily_budget_usd },
    days: days(from, today).map((d) => ({
      day: d,
      requests: Number(byDay[d]?.requests || 0),
      ok: Number(byDay[d]?.ok || 0),
      failed: Number(byDay[d]?.failed || 0),
      usd: Number(byDay[d]?.usd || 0),
      talk: usageByDay[d]?.talk || 0,
      photo: usageByDay[d]?.photo || 0,
    })),
    models: (models.results || []).map((m) => ({ ...m, avg_ms: m.requests ? Math.round(m.ms / m.requests) : 0 })),
    people: { today: Number(activeToday || 0), span: Number(activeSpan || 0), total: (accounts.results || []).length, plans },
    flags: { testers_mode: cfg.testers_mode, test_purchases: cfg.test_purchases },
  });
}

async function listAccounts(env, url, now) {
  const cfg = await load(env.DB);
  const limit = Math.min(500, Math.max(1, Number(url.searchParams.get('limit')) || 100));
  const rows = await env.DB.prepare('SELECT * FROM accounts ORDER BY last_at DESC LIMIT ?').bind(limit).all();
  const out = [];
  for (const a of rows.results || []) {
    const plan = planOf(a, cfg, now);
    const u = await used(env, a.id, windowStart(plan, a, now), now);
    out.push({
      id: a.id,
      plan,
      override: a.plan || null,
      trial_ends: a.trial_start ? a.trial_start + cfg.trial_days * DAY_MS : null,
      sub_until: a.sub_until || null,
      sub_store: a.sub_store || null,
      created_at: a.created_at,
      last_at: a.last_at,
      agent: a.agent,
      used: u,
    });
  }
  return json(200, { accounts: out });
}

async function changeAccount(env, request, now) {
  const b = await body(request);
  const id = String(b?.id || '');
  const acc = await env.DB.prepare('SELECT * FROM accounts WHERE id = ?').bind(id).first();
  if (!acc) return fail(404, 'no_account');
  switch (b?.action) {
    case 'plan':
      if (![null, 'tester', 'owner', 'blocked'].includes(b.plan ?? null)) return fail(400, 'bad_plan');
      await env.DB.prepare('UPDATE accounts SET plan = ? WHERE id = ?').bind(b.plan ?? null, id).run();
      break;
    case 'trial_reset':
      await env.DB.prepare('UPDATE accounts SET trial_start = NULL WHERE id = ?').bind(id).run();
      break;
    case 'trial_end':
      await env.DB.prepare('UPDATE accounts SET trial_start = ? WHERE id = ?').bind(1, id).run();
      break;
    case 'grant': {
      const n = Number(b.days);
      if (!Number.isInteger(n) || n < 1 || n > 400) return fail(400, 'bad_days');
      await env.DB.prepare('INSERT INTO subscriptions (token, account, store, product, state, until, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)').bind(`grant:${id}:${now}`, id, 'grant', null, 'GRANT', now + n * DAY_MS, now).run();
      await refreshSub(env, id);
      break;
    }
    case 'revoke':
      await env.DB.prepare("UPDATE subscriptions SET until = 0, updated_at = ? WHERE account = ? AND store IN ('test', 'grant')").bind(now, id).run();
      await refreshSub(env, id);
      break;
    default:
      return fail(400, 'bad_action');
  }
  await audit(env, now, `account.${b.action}`, { id, plan: b.plan, days: b.days });
  return json(200, { ok: true });
}

function secrets(env) {
  return {
    free_key: !!env.OPENROUTER_FREE_KEY,
    paid_key: !!env.OPENROUTER_PAID_KEY,
    play: !!(env.GOOGLE_SERVICE_ACCOUNT_JSON && env.PLAY_PACKAGE),
    rtdn: !!env.RTDN_KEY,
    totp: !!env.ADMIN_TOTP_SECRET,
  };
}

async function getConfig(env) {
  return json(200, { config: await load(env.DB), defaults: DEFAULTS, secrets: secrets(env) });
}

async function putConfig(env, request, now) {
  const b = await body(request);
  const v = validate(b?.patch);
  if (!v.ok) return fail(400, 'bad_config', { field: v.error });
  const config = await save(env.DB, v.value, now);
  await audit(env, now, 'config', v.value);
  return json(200, { config });
}

async function listAudit(env) {
  const rows = await env.DB.prepare('SELECT at, action, detail FROM audit ORDER BY at DESC LIMIT 100').all();
  return json(200, { audit: rows.results || [] });
}

async function adminApi(env, request, url, now) {
  const path = url.pathname.slice('/admin/api'.length);
  const route = `${request.method} ${path}`;
  if (route === 'POST /login') return login(env, request, now);
  const mutating = request.method !== 'GET';
  if (!(await isAdmin(env, request, now, mutating))) return fail(401, 'not_admin');
  switch (route) {
    case 'POST /logout':
      return logout();
    case 'GET /me':
      return json(200, { ok: true });
    case 'GET /overview':
      return overview(env, url, now);
    case 'GET /accounts':
      return listAccounts(env, url, now);
    case 'POST /accounts':
      return changeAccount(env, request, now);
    case 'GET /config':
      return getConfig(env);
    case 'PUT /config':
      return putConfig(env, request, now);
    case 'GET /audit':
      return listAudit(env);
    default:
      return fail(404, 'not_found');
  }
}

// ——— Entrada ———

export async function handle(request, env, now = Date.now(), fetcher = fetch) {
  const url = new URL(request.url);
  const route = `${request.method} ${url.pathname}`;
  try {
    if (url.pathname.startsWith('/admin/api/')) return await adminApi(env, request, url, now);
    switch (route) {
      case 'POST /v1/session':
        return await session(env, request, now);
      case 'GET /v1/me':
        return await me(env, request, now);
      case 'POST /v1/organize':
        return await organize(env, request, now, fetcher);
      case 'POST /v1/purchase':
        return await purchase(env, request, now, fetcher);
      case 'POST /play/rtdn':
        return await rtdn(env, request, url, now, fetcher);
      case 'GET /admin':
      case 'GET /admin/':
        return asset(env, 'index.html');
      case 'GET /admin/panel.js':
        return asset(env, 'panel.js');
      case 'GET /admin/panel.css':
        return asset(env, 'panel.css');
      case 'GET /admin/fonts/fraunces.ttf':
        return asset(env, 'fraunces.ttf');
      case 'GET /admin/fonts/nunito.ttf':
        return asset(env, 'nunito.ttf');
      case 'GET /health':
        return json(200, { ok: true });
      default:
        return fail(404, 'not_found');
    }
  } catch {
    // Nada do pedido vai pro log (pode ter o que ela disse).
    return fail(500, 'server_error');
  }
}
