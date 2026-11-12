/**
 * O que o painel pode mudar, com o padrão do código por baixo. Fica na tabela config (chave
 * "settings", JSON); o que não está lá vale o padrão. Limites null = sem limite.
 */
export const DEFAULTS = {
  // O teste da inteligência do Novelo: começa no primeiro uso, dura isto, com modelos grátis.
  trial_days: 7,
  // Por plano: falas/textos ("talk") e fotos mandadas sozinhas ("photo"), por dia e por semana.
  // No teste, "semana" é o teste inteiro.
  limits: {
    trial: { talk: { day: 15, week: 60 }, photo: { day: 5, week: 20 } },
    plus: { talk: { day: 60, week: 250 }, photo: { day: 20, week: 80 } },
    tester: { talk: { day: 60, week: 250 }, photo: { day: 20, week: 80 } },
    owner: { talk: { day: null, week: null }, photo: { day: null, week: null } },
  },
  // Modelos grátis (chave OPENROUTER_FREE_KEY, sem crédito): o teste. Em ordem; falhou, vai o próximo.
  free_models: [
    'baidu/ernie-5.5-flash-preview:free',
    'nvidia/nemotron-3-ultra-550b-a55b:free',
    'nvidia/nemotron-3.5-super-vl:free',
    'dots-studio/dots-3-note-preview:free',
  ],
  // Modelos pagos (chave OPENROUTER_PAID_KEY): Novelo+, testadores e o dono.
  paid_models: ['openai/gpt-6-luna'],
  // Os testadores usam os modelos pagos (o produto de verdade) ou os grátis.
  tester_models: 'paid',
  // O teto de gasto do dia nos modelos pagos (dólares), pra todo mundo menos o dono.
  daily_budget_usd: 5,
  // Modo testadores: todo mundo é testador (nada de plano ou cobrança no app).
  testers_mode: false,
  // Compra de teste (o app do GitHub simula a assinatura): vale por tantos dias.
  test_purchases: true,
  test_purchase_days: 30,
  // Pessoas novas por endereço de internet por dia.
  max_new_per_ip: 20,
  // A assinatura na Google Play.
  play: { product: 'novelo_plus', base_plan: 'monthly' },
};

export const PLANS = ['trial', 'plus', 'tester', 'owner'];
export const KINDS = ['talk', 'photo'];
export const WINDOWS = ['day', 'week'];

function isObj(v) {
  return v && typeof v === 'object' && !Array.isArray(v);
}

/** [base] com [over] por cima, campo a campo (listas são trocadas inteiras). */
export function merge(base, over) {
  if (!isObj(base) || !isObj(over)) return over === undefined ? base : over;
  const out = { ...base };
  for (const [k, v] of Object.entries(over)) out[k] = k in base ? merge(base[k], v) : v;
  return out;
}

const limit = (v) => v === null || (Number.isInteger(v) && v >= 0 && v <= 100_000);
const models = (v) => Array.isArray(v) && v.length > 0 && v.length <= 12 && v.every((m) => typeof m === 'string' && /^[\w.:/-]{3,100}$/.test(m));

/**
 * [patch] conferido campo a campo: só o que existe no padrão, com o tipo certo. Devolve
 * {ok, value} (o patch limpo) ou {ok:false, error} com o primeiro problema.
 */
export function validate(patch) {
  if (!isObj(patch)) return { ok: false, error: 'not_an_object' };
  const out = {};
  for (const [k, v] of Object.entries(patch)) {
    switch (k) {
      case 'trial_days':
      case 'test_purchase_days':
        if (!Number.isInteger(v) || v < 0 || v > 90) return { ok: false, error: k };
        out[k] = v;
        break;
      case 'max_new_per_ip':
        if (!Number.isInteger(v) || v < 1 || v > 10_000) return { ok: false, error: k };
        out[k] = v;
        break;
      case 'daily_budget_usd':
        if (typeof v !== 'number' || !(v >= 0) || v > 10_000) return { ok: false, error: k };
        out[k] = v;
        break;
      case 'testers_mode':
      case 'test_purchases':
        if (typeof v !== 'boolean') return { ok: false, error: k };
        out[k] = v;
        break;
      case 'tester_models':
        if (v !== 'paid' && v !== 'free') return { ok: false, error: k };
        out[k] = v;
        break;
      case 'free_models':
      case 'paid_models':
        if (!models(v)) return { ok: false, error: k };
        out[k] = v;
        break;
      case 'limits': {
        if (!isObj(v)) return { ok: false, error: 'limits' };
        out.limits = {};
        for (const [plan, kinds] of Object.entries(v)) {
          if (!PLANS.includes(plan) || !isObj(kinds)) return { ok: false, error: `limits.${plan}` };
          out.limits[plan] = {};
          for (const [kind, windows] of Object.entries(kinds)) {
            if (!KINDS.includes(kind) || !isObj(windows)) return { ok: false, error: `limits.${plan}.${kind}` };
            out.limits[plan][kind] = {};
            for (const [w, n] of Object.entries(windows)) {
              if (!WINDOWS.includes(w) || !limit(n)) return { ok: false, error: `limits.${plan}.${kind}.${w}` };
              out.limits[plan][kind][w] = n;
            }
          }
        }
        break;
      }
      case 'play':
        if (!isObj(v) || Object.entries(v).some(([pk, pv]) => !['product', 'base_plan'].includes(pk) || typeof pv !== 'string' || !/^[a-z0-9_.-]{1,60}$/.test(pv))) return { ok: false, error: 'play' };
        out.play = { ...v };
        break;
      default:
        return { ok: false, error: `unknown:${k}` };
    }
  }
  return { ok: true, value: out };
}

/** O que vale agora: o padrão com o que o painel guardou por cima. */
export async function load(db) {
  const raw = await db.prepare('SELECT value FROM config WHERE key = ?').bind('settings').first('value');
  if (!raw) return DEFAULTS;
  try {
    return merge(DEFAULTS, JSON.parse(raw));
  } catch {
    return DEFAULTS;
  }
}

/** Guarda [patch] (já conferido) por cima do que o painel tinha guardado. */
export async function save(db, patch, now) {
  const raw = await db.prepare('SELECT value FROM config WHERE key = ?').bind('settings').first('value');
  let saved = {};
  try {
    saved = raw ? JSON.parse(raw) : {};
  } catch {
    saved = {};
  }
  const next = merge(saved, patch);
  await db.prepare('INSERT INTO config (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at').bind('settings', JSON.stringify(next), now).run();
  return merge(DEFAULTS, next);
}
