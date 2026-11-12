/**
 * O servidor do Novelo (Cloudflare Worker). O app não leva chave nenhuma: ele pede aqui, o servidor
 * conta quanto cada instalação usou no dia e só então chama o modelo com a chave dele.
 *
 *   POST /v1/session   {install}                    → um passe (token) e o uso de hoje
 *   GET  /v1/me                                     → o uso de hoje
 *   POST /v1/organize  {system, user, kind, turn}   → a resposta do modelo e o uso de hoje
 *   POST /admin/plan   {install, plan}              → muda o plano (chave de admin)
 *   GET  /admin/installs                            → as instalações mais recentes (chave de admin)
 *
 * Sem login: cada instalação do app gera um código aleatório (install). O passe é esse código
 * assinado pelo servidor (HMAC), sem guardar sessão. Nada do que ela fala ou escreve fica guardado.
 *
 * Segredos (wrangler secret): OPENROUTER_KEY, TOKEN_SECRET, ADMIN_KEY.
 * Variáveis (wrangler.toml): MODEL, FALLBACK_MODELS, DAILY_BUDGET_USD, MAX_NEW_PER_IP.
 */

/** O que cada plano organiza por dia. "talk" = fala ou texto escrito; "photo" = foto mandada sozinha. */
export const PLANS = {
  free: { talk: 10, photo: 3 },
  plus: { talk: 50, photo: 20 },
  owner: { talk: Infinity, photo: Infinity },
};

/** Uma fala que procura algo antes de responder leva mais de um pedido; conta uma vez só, até este tanto. */
export const MAX_REQUESTS_PER_TURN = 3;

const KINDS = ['talk', 'photo'];
const INSTALL = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOKEN_DAYS = 30;
const MAX_SYSTEM = 40_000;
const MAX_USER = 60_000;
const MODEL_TIMEOUT_MS = 70_000;

// ——— O dia, em Brasília (UTC−3, sem horário de verão desde 2019) ———

const BRT_MS = 3 * 60 * 60 * 1000;

/** O dia de [now] em Brasília: "2026-11-12". */
export function day(now) {
  return new Date(now - BRT_MS).toISOString().slice(0, 10);
}

/** Quando o dia de [now] acaba em Brasília (a meia-noite seguinte), em ms. */
export function resetAt(now) {
  const d = new Date(now - BRT_MS);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) + BRT_MS;
}

// ——— O passe ———

const enc = new TextEncoder();

function b64url(bytes) {
  let s = '';
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function hmac(secret, text) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64url(await crypto.subtle.sign('HMAC', key, enc.encode(text)));
}

/** O passe de [install]: "v1.<install>.<emitido em ms>.<assinatura>". */
export async function sign(secret, install, now) {
  const body = `v1.${install}.${now}`;
  return `${body}.${await hmac(secret, body)}`;
}

/** A instalação do passe, se a assinatura confere e ele não venceu; senão null. */
export async function verify(secret, token, now) {
  const parts = String(token || '').split('.');
  if (parts.length !== 4 || parts[0] !== 'v1' || !INSTALL.test(parts[1])) return null;
  const issued = Number(parts[2]);
  if (!Number.isFinite(issued) || issued > now + 60_000 || now - issued > TOKEN_DAYS * 86_400_000) return null;
  const want = await hmac(secret, `${parts[0]}.${parts[1]}.${parts[2]}`);
  if (want.length !== parts[3].length) return null;
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ parts[3].charCodeAt(i);
  return diff === 0 ? parts[1] : null;
}

// ——— Respostas ———

function json(status, body, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', ...headers } });
}

const fail = (status, error, extra = {}) => json(status, { error, ...extra });

async function body(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

// ——— O uso ———

function limits(plan) {
  return PLANS[plan] || PLANS.free;
}

/** O que a instalação usou hoje, contra o que o plano dá ("Infinity" vira null no JSON: sem limite). */
async function usage(env, install, plan, now) {
  const today = day(now);
  const rows = await env.DB.prepare('SELECT kind, COUNT(*) AS n FROM usage WHERE install = ? AND day = ? GROUP BY kind').bind(install, today).all();
  const used = Object.fromEntries((rows.results || []).map((r) => [r.kind, Number(r.n)]));
  const lim = limits(plan);
  const out = { plan, reset_at: resetAt(now) };
  for (const k of KINDS) out[k] = { used: used[k] || 0, limit: Number.isFinite(lim[k]) ? lim[k] : null };
  return out;
}

async function installOf(env, request, now) {
  const auth = request.headers.get('authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  const id = await verify(env.TOKEN_SECRET, token, now);
  if (!id) return null;
  return env.DB.prepare('SELECT id, plan FROM installs WHERE id = ?').bind(id).first();
}

// ——— O modelo ———

/** O primeiro objeto JSON da resposta, se é um (o plano que o app espera); senão null. */
export function planOf(text) {
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

/**
 * A resposta do modelo pra [system]/[user]: o MODEL e, se ele falhar (fora do ar, ocupado, lento
 * ou sem devolver um plano), os FALLBACK_MODELS na ordem. Só provedores que não guardam nem
 * treinam com o que recebem (data_collection: deny). Devolve {text, cost} ou lança.
 */
async function ask(env, system, user, fetcher) {
  const models = [env.MODEL, ...String(env.FALLBACK_MODELS || '').split(',')].map((m) => (m || '').trim()).filter(Boolean);
  let last = new Error('no model');
  for (const model of models) {
    try {
      const r = await fetcher('https://openrouter.ai/api/v1/chat/completions', {
        method: 'POST',
        headers: {
          authorization: `Bearer ${env.OPENROUTER_KEY}`,
          'content-type': 'application/json',
          'x-title': 'Novelo',
        },
        body: JSON.stringify({
          model,
          messages: [
            { role: 'system', content: system },
            { role: 'user', content: user },
          ],
          response_format: { type: 'json_object' },
          reasoning: { effort: 'low' },
          max_tokens: 4000,
          provider: { data_collection: 'deny' },
          usage: { include: true },
        }),
        signal: AbortSignal.timeout(MODEL_TIMEOUT_MS),
      });
      if (!r.ok) {
        last = new Error(`${model}: ${r.status}`);
        continue;
      }
      const data = await r.json();
      const text = data?.choices?.[0]?.message?.content || '';
      if (!planOf(text)) {
        last = new Error(`${model}: not a plan`);
        continue;
      }
      return { text, cost: Number(data?.usage?.cost) || 0, model };
    } catch (e) {
      last = e;
    }
  }
  throw last;
}

// ——— As rotas ———

async function session(env, request, now) {
  const b = await body(request);
  const install = String(b?.install || '').toLowerCase();
  if (!INSTALL.test(install)) return fail(400, 'bad_install');
  const agent = (request.headers.get('user-agent') || '').slice(0, 80);
  let row = await env.DB.prepare('SELECT id, plan FROM installs WHERE id = ?').bind(install).first();
  if (!row) {
    // Instalações novas por endereço por dia: o bastante pra uma família, pouco pra quem fabrica códigos.
    const today = day(now);
    const ip = await hmac(env.TOKEN_SECRET, `ip:${request.headers.get('cf-connecting-ip') || ''}`);
    const minted = await env.DB.prepare('SELECT n FROM mints WHERE day = ? AND ip = ?').bind(today, ip).first('n');
    if ((minted || 0) >= Number(env.MAX_NEW_PER_IP || 20)) return fail(429, 'too_many_installs');
    await env.DB.batch([
      env.DB.prepare('INSERT OR IGNORE INTO installs (id, plan, created_at, last_at, agent) VALUES (?, ?, ?, ?, ?)').bind(install, 'free', now, now, agent),
      env.DB.prepare('INSERT INTO mints (day, ip, n) VALUES (?, ?, 1) ON CONFLICT (day, ip) DO UPDATE SET n = n + 1').bind(today, ip),
    ]);
    row = { id: install, plan: 'free' };
  } else {
    await env.DB.prepare('UPDATE installs SET last_at = ?, agent = ? WHERE id = ?').bind(now, agent, install).run();
  }
  return json(200, { token: await sign(env.TOKEN_SECRET, install, now), usage: await usage(env, row.id, row.plan, now) });
}

async function me(env, request, now) {
  const row = await installOf(env, request, now);
  if (!row) return fail(401, 'bad_token');
  return json(200, { usage: await usage(env, row.id, row.plan, now) });
}

async function organize(env, request, now, fetcher) {
  const row = await installOf(env, request, now);
  if (!row) return fail(401, 'bad_token');
  const b = await body(request);
  const kind = b?.kind;
  const turn = String(b?.turn || '').slice(0, 64);
  const system = b?.system;
  const user = b?.user;
  if (!KINDS.includes(kind) || !turn || typeof system !== 'string' || typeof user !== 'string' || !system || !user) return fail(400, 'bad_request');
  if (system.length > MAX_SYSTEM || user.length > MAX_USER) return fail(413, 'too_long');

  const today = day(now);
  const lim = limits(row.plan)[kind];
  const seen = await env.DB.prepare('SELECT requests FROM usage WHERE install = ? AND day = ? AND kind = ? AND turn = ?').bind(row.id, today, kind, turn).first('requests');
  if (seen != null) {
    // A mesma fala de novo (procurou algo e volta com o que achou): já contou.
    if (seen >= MAX_REQUESTS_PER_TURN) return fail(429, 'turn_limit', { usage: await usage(env, row.id, row.plan, now) });
  } else if (Number.isFinite(lim)) {
    const n = await env.DB.prepare('SELECT COUNT(*) AS n FROM usage WHERE install = ? AND day = ? AND kind = ?').bind(row.id, today, kind).first('n');
    if (Number(n) >= lim) return fail(429, 'daily_limit', { kind, usage: await usage(env, row.id, row.plan, now) });
  }

  // O teto do dia pra todo mundo (menos o dono): nenhum erro vira uma conta alta.
  if (row.plan !== 'owner') {
    const spent = await env.DB.prepare('SELECT usd FROM spend WHERE day = ?').bind(today).first('usd');
    if (Number(spent || 0) >= Number(env.DAILY_BUDGET_USD || 5)) return fail(503, 'busy', { reset_at: resetAt(now) });
  }

  let answer;
  try {
    answer = await ask(env, system, user, fetcher);
  } catch {
    // Não conta: o pedido não deu em nada.
    return fail(502, 'model_unavailable');
  }

  await env.DB.batch([
    env.DB.prepare('INSERT INTO usage (install, day, kind, turn, requests) VALUES (?, ?, ?, ?, 1) ON CONFLICT (install, day, kind, turn) DO UPDATE SET requests = requests + 1').bind(row.id, today, kind, turn),
    env.DB.prepare('INSERT INTO spend (day, usd, requests) VALUES (?, ?, 1) ON CONFLICT (day) DO UPDATE SET usd = usd + excluded.usd, requests = requests + 1').bind(today, answer.cost),
    env.DB.prepare('UPDATE installs SET last_at = ? WHERE id = ?').bind(now, row.id),
  ]);
  return json(200, { text: answer.text, usage: await usage(env, row.id, row.plan, now) });
}

function admin(env, request) {
  const key = env.ADMIN_KEY || '';
  return key.length >= 24 && request.headers.get('authorization') === `Bearer ${key}`;
}

async function setPlan(env, request) {
  if (!admin(env, request)) return fail(401, 'not_admin');
  const b = await body(request);
  const install = String(b?.install || '').toLowerCase();
  if (!INSTALL.test(install) || !(b?.plan in PLANS)) return fail(400, 'bad_request');
  const r = await env.DB.prepare('UPDATE installs SET plan = ? WHERE id = ?').bind(b.plan, install).run();
  return r.meta?.changes ? json(200, { install, plan: b.plan }) : fail(404, 'no_install');
}

async function listInstalls(env, request, now) {
  if (!admin(env, request)) return fail(401, 'not_admin');
  const rows = await env.DB.prepare('SELECT id, plan, created_at, last_at, agent FROM installs ORDER BY last_at DESC LIMIT 50').all();
  const today = day(now);
  const spent = await env.DB.prepare('SELECT usd, requests FROM spend WHERE day = ?').bind(today).first();
  return json(200, { today, spent: spent || { usd: 0, requests: 0 }, installs: rows.results || [] });
}

export async function handle(request, env, now = Date.now(), fetcher = fetch) {
  const url = new URL(request.url);
  const route = `${request.method} ${url.pathname}`;
  try {
    switch (route) {
      case 'POST /v1/session':
        return await session(env, request, now);
      case 'GET /v1/me':
        return await me(env, request, now);
      case 'POST /v1/organize':
        return await organize(env, request, now, fetcher);
      case 'POST /admin/plan':
        return await setPlan(env, request);
      case 'GET /admin/installs':
        return await listInstalls(env, request, now);
      case 'GET /':
        return json(200, { name: 'novelo', ok: true });
      default:
        return fail(404, 'not_found');
    }
  } catch {
    // Nada do pedido vai pro log (pode ter o que ela disse).
    return fail(500, 'server_error');
  }
}

export default {
  fetch: (request, env) => handle(request, env),
};
