// O painel do Novelo: tudo montado com textContent (nada vindo do servidor vira HTML).
'use strict';

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    // Pelo CSSOM: a política de segurança (CSP) não deixa atributo style escrito.
    else if (k === 'style') n.style.cssText = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (k === 'checked' || k === 'value' || k === 'disabled' || k === 'selected') n[k] = v;
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return n;
}

function svg(tag, attrs = {}, ...children) {
  const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
  for (const c of children.flat()) if (c) n.append(c);
  return n;
}

async function api(path, { method = 'GET', body } = {}) {
  const r = await fetch(`/admin/api${path}`, {
    method,
    credentials: 'same-origin',
    headers: { 'x-novelo-admin': '1', ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await r.json().catch(() => ({}));
  if (r.status === 401 && path !== '/login') {
    show('login');
    throw new Error('not_admin');
  }
  if (!r.ok) throw Object.assign(new Error(data.error || String(r.status)), { data });
  return data;
}

function toast(text) {
  const t = el('div', { class: 'toast' }, text);
  document.body.append(t);
  setTimeout(() => t.remove(), 2600);
}

const usd = (n) => { const v = Number(n || 0); const d = v < 1 ? 4 : 2; return `US$ ${v.toLocaleString('pt-BR', { minimumFractionDigits: d, maximumFractionDigits: d })}`; };
const int = (n) => Number(n || 0).toLocaleString('pt-BR');
const pct = (a, b) => (b ? `${Math.round((100 * a) / b)}%` : '—');
const when = (ms) => (ms ? new Date(ms).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—');
const date = (ms) => (ms ? new Date(ms).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit', year: '2-digit' }) : '—');
const PLAN_NAMES = { trial: 'Teste', plus: 'Novelo+', tester: 'Testador', owner: 'Dono', ended: 'Teste acabou', blocked: 'Bloqueado' };

function card(label, value, sub, bar) {
  return el('div', { class: 'card' },
    el('div', { class: 'label' }, label),
    el('div', { class: 'value' }, value),
    sub ? el('div', { class: 'sub' }, sub) : null,
    bar != null ? el('div', { class: `bar${bar > 0.85 ? ' warn' : ''}` }, el('span', { style: `width:${Math.min(100, Math.round(bar * 100))}%` })) : null);
}

// ——— Visão geral ———

function chart(days, series) {
  const W = 1000;
  const H = 180;
  const pad = 22;
  const max = Math.max(1e-9, ...days.map((d) => Math.max(...series.map((s) => s.value(d)))));
  const bw = (W - pad * 2) / days.length;
  const g = svg('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', role: 'img' });
  g.append(svg('line', { x1: pad, x2: W - pad, y1: H - pad, y2: H - pad, stroke: 'currentColor', 'stroke-opacity': '0.15' }));
  days.forEach((d, i) => {
    series.forEach((s, j) => {
      const h = ((H - pad * 2) * s.value(d)) / max;
      const w = (bw * 0.7) / series.length;
      const x = pad + i * bw + bw * 0.15 + j * w;
      const r = svg('rect', { x, y: H - pad - h, width: Math.max(1, w - 2), height: Math.max(0, h), rx: 3, fill: s.color });
      r.append(svg('title', {}, document.createTextNode(`${d.day}: ${s.label(d)}`)));
      g.append(r);
    });
  });
  return g;
}

async function overview() {
  const box = $('overview');
  const o = await api('/overview?days=14');
  const today = o.days[o.days.length - 1] || {};
  const p = o.people;
  const cs = getComputedStyle(document.documentElement);
  const accent = cs.getPropertyValue('--accent').trim();
  const soft = cs.getPropertyValue('--warn').trim();
  box.replaceChildren(
    el('h2', {}, 'Hoje'),
    el('div', { class: 'cards' },
      card('Gasto nos modelos pagos', usd(o.budget.usd_today), `teto do dia ${usd(o.budget.limit)}`, o.budget.limit ? o.budget.usd_today / o.budget.limit : 0),
      card('Pedidos ao modelo', int(today.requests), `${int(today.failed)} falharam · ${pct(today.ok, today.requests)} certos`),
      card('Organizado', `${int(today.talk)} falas`, `${int(today.photo)} fotos`),
      card('Pessoas usando', int(p.today), `${int(p.span)} nos últimos ${o.span} dias · ${int(p.total)} no total`)),
    el('div', { class: 'row', style: 'margin-top:12px' },
      o.flags.testers_mode ? el('span', { class: 'chip' }, 'Modo testadores ligado') : null,
      o.flags.test_purchases ? el('span', { class: 'chip' }, 'Compras de teste ligadas') : null),
    el('h2', {}, `Últimos ${o.span} dias`),
    el('div', { class: 'grid2' },
      el('div', { class: 'chart' },
        chart(o.days, [{ value: (d) => d.usd, label: (d) => usd(d.usd), color: accent }]),
        el('div', { class: 'legend' }, el('span', {}, el('i', { style: `background:${accent}` }), 'Gasto por dia (dólares)'))),
      el('div', { class: 'chart' },
        chart(o.days, [
          { value: (d) => d.talk, label: (d) => `${d.talk} falas`, color: accent },
          { value: (d) => d.photo, label: (d) => `${d.photo} fotos`, color: soft },
        ]),
        el('div', { class: 'legend' },
          el('span', {}, el('i', { style: `background:${accent}` }), 'Falas e textos'),
          el('span', {}, el('i', { style: `background:${soft}` }), 'Fotos')))),
    el('h2', {}, 'Planos'),
    el('div', { class: 'cards' }, ...Object.entries(p.plans).map(([k, n]) => card(PLAN_NAMES[k] || k, int(n)))),
    el('h2', {}, 'Modelos'),
    el('div', { class: 'scroll' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ...['Modelo', 'Plano', 'Pedidos', 'Certos', 'Custo', 'Tokens entrada', 'Tokens saída', 'Tempo médio'].map((h) => el('th', {}, h)))),
      el('tbody', {}, ...(o.models.length ? o.models.map((m) => el('tr', {},
        el('td', { class: 'mono' }, m.model),
        el('td', {}, PLAN_NAMES[m.tier] || m.tier),
        el('td', { class: 'num' }, int(m.requests)),
        el('td', { class: 'num' }, pct(m.ok, m.requests)),
        el('td', { class: 'num' }, usd(m.usd)),
        el('td', { class: 'num' }, int(m.tokens_in)),
        el('td', { class: 'num' }, int(m.tokens_out)),
        el('td', { class: 'num' }, `${(m.avg_ms / 1000).toFixed(1)} s`))) : [el('tr', {}, el('td', { colspan: 8, class: 'muted' }, 'Nenhum pedido ainda.'))])))),
  );
}

// ——— Pessoas ———

async function act(id, action, extra = {}) {
  await api('/accounts', { method: 'POST', body: { id, action, ...extra } });
  toast('Feito');
  people();
}

function actions(a) {
  const pick = el('select', {
    onchange: async (e) => {
      const v = e.target.value;
      e.target.value = '';
      if (!v) return;
      if (v === 'grant') {
        const days = Number(prompt('Dar o Novelo+ por quantos dias?', '30'));
        if (days > 0) await act(a.id, 'grant', { days });
      } else if (v.startsWith('plan:')) {
        const plan = v.slice(5) || null;
        await act(a.id, 'plan', { plan });
      } else {
        await act(a.id, v);
      }
    },
  },
  el('option', { value: '' }, 'Mudar…'),
  el('option', { value: 'plan:tester' }, 'Testador'),
  el('option', { value: 'plan:owner' }, 'Dono (sem limite)'),
  el('option', { value: 'plan:blocked' }, 'Bloquear'),
  el('option', { value: 'plan:' }, 'Tirar o plano do painel'),
  el('option', { value: 'grant' }, 'Dar Novelo+ por uns dias'),
  el('option', { value: 'revoke' }, 'Tirar Novelo+ de teste/dado'),
  el('option', { value: 'trial_reset' }, 'Recomeçar o teste'),
  el('option', { value: 'trial_end' }, 'Encerrar o teste'));
  return pick;
}

async function people() {
  const box = $('people');
  const { accounts } = await api('/accounts?limit=200');
  box.replaceChildren(
    el('h2', {}, 'Pessoas'),
    el('p', { class: 'muted' }, 'Cada pessoa é um celular (o código dele, cifrado). Nada do que ela fala fica aqui: só quanto usou.'),
    el('div', { class: 'scroll' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ...['Pessoa', 'Plano', 'Teste até', 'Novelo+ até', 'Hoje', 'Semana', 'Último uso', 'Aparelho', ''].map((h) => el('th', {}, h)))),
      el('tbody', {}, ...(accounts.length ? accounts.map((a) => el('tr', {},
        el('td', { class: 'mono', title: a.id }, a.id.slice(0, 10)),
        el('td', {}, el('span', { class: `chip${a.plan === 'plus' || a.plan === 'owner' ? ' ok' : a.plan === 'ended' || a.plan === 'blocked' ? ' off' : ''}` }, PLAN_NAMES[a.plan] || a.plan), a.override ? el('span', { class: 'muted' }, ' · painel') : null),
        el('td', { class: 'nowrap' }, date(a.trial_ends)),
        el('td', {}, a.sub_until ? `${date(a.sub_until)} · ${a.sub_store}` : '—'),
        el('td', { class: 'num nowrap' }, `${a.used.talk.day} falas · ${a.used.photo.day} fotos`),
        el('td', { class: 'num nowrap' }, `${a.used.talk.week} falas · ${a.used.photo.week} fotos`),
        el('td', { class: 'nowrap' }, when(a.last_at)),
        el('td', { class: 'muted' }, a.agent || '—'),
        el('td', {}, actions(a)))) : [el('tr', {}, el('td', { colspan: 9, class: 'muted' }, 'Ninguém ainda.'))])))),
  );
}

// ——— Ajustes ———

const LIMIT_PLANS = [['trial', 'Teste'], ['plus', 'Novelo+'], ['tester', 'Testador'], ['owner', 'Dono']];

async function settings() {
  const box = $('settings');
  const { config: c, secrets: s } = await api('/config');
  const num = (v) => el('input', { type: 'number', min: 0, step: 1, value: v ?? '', placeholder: 'sem' });
  const inputs = {};
  const limitRows = LIMIT_PLANS.map(([plan, name]) => {
    inputs[plan] = {};
    const cells = [];
    for (const kind of ['talk', 'photo']) for (const w of ['day', 'week']) {
      const i = num(c.limits[plan][kind][w]);
      inputs[plan][`${kind}.${w}`] = i;
      cells.push(el('td', {}, i));
    }
    return el('tr', {}, el('td', {}, name), ...cells);
  });
  const trialDays = el('input', { type: 'number', min: 0, max: 90, value: c.trial_days });
  const budget = el('input', { type: 'number', min: 0, step: '0.5', value: c.daily_budget_usd });
  const testers = el('input', { type: 'checkbox', checked: c.testers_mode });
  const tests = el('input', { type: 'checkbox', checked: c.test_purchases });
  const testDays = el('input', { type: 'number', min: 1, max: 90, value: c.test_purchase_days });
  const perIp = el('input', { type: 'number', min: 1, value: c.max_new_per_ip });
  const free = el('textarea', { spellcheck: 'false', class: 'mono' }, c.free_models.join('\n'));
  const paid = el('textarea', { spellcheck: 'false', class: 'mono' }, c.paid_models.join('\n'));
  const testerModels = el('select', {}, el('option', { value: 'paid', selected: c.tester_models === 'paid' }, 'os pagos (o produto de verdade)'), el('option', { value: 'free', selected: c.tester_models === 'free' }, 'os grátis'));
  const product = el('input', { value: c.play.product });
  const basePlan = el('input', { value: c.play.base_plan });

  const secret = (ok, name) => el('span', { class: `chip${ok ? ' ok' : ' off'}` }, `${name}: ${ok ? 'configurado' : 'falta'}`);
  const save = el('button', {
    onclick: async () => {
      const read = (i) => (i.value === '' ? null : Number(i.value));
      const limits = {};
      for (const [plan] of LIMIT_PLANS) {
        limits[plan] = { talk: {}, photo: {} };
        for (const [k, i] of Object.entries(inputs[plan])) {
          const [kind, w] = k.split('.');
          limits[plan][kind][w] = read(i);
        }
      }
      const lines = (t) => t.value.split('\n').map((x) => x.trim()).filter(Boolean);
      const patch = {
        trial_days: Number(trialDays.value),
        daily_budget_usd: Number(budget.value),
        testers_mode: testers.checked,
        test_purchases: tests.checked,
        test_purchase_days: Number(testDays.value),
        max_new_per_ip: Number(perIp.value),
        free_models: lines(free),
        paid_models: lines(paid),
        tester_models: testerModels.value,
        play: { product: product.value.trim(), base_plan: basePlan.value.trim() },
        limits,
      };
      try {
        await api('/config', { method: 'PUT', body: { patch } });
        toast('Guardado');
      } catch (e) {
        toast(`Não guardou: ${e.data?.field || e.message}`);
      }
    },
  }, 'Guardar');

  box.replaceChildren(
    el('h2', {}, 'Limites'),
    el('p', { class: 'muted' }, 'Falas (e textos) e fotos mandadas sozinhas. "Semana" vai de segunda a domingo; no teste, é o teste inteiro. Vazio = sem limite.'),
    el('div', { class: 'scroll' }, el('table', { class: 'table limits' },
      el('thead', {}, el('tr', {}, ...['Plano', 'Falas por dia', 'Falas por semana', 'Fotos por dia', 'Fotos por semana'].map((h) => el('th', {}, h)))),
      el('tbody', {}, ...limitRows))),
    el('h2', {}, 'Teste e cobrança'),
    el('div', { class: 'grid2' },
      el('div', { class: 'card form' },
        el('label', {}, 'Dias de teste (a partir do primeiro uso)', trialDays),
        el('label', { class: 'switch' }, testers, 'Modo testadores: todo mundo é testador (sem plano nem cobrança no app)'),
        el('label', {}, 'Os testadores usam', testerModels)),
      el('div', { class: 'card form' },
        el('label', { class: 'switch' }, tests, 'Compras de teste (o app do GitHub simula a assinatura)'),
        el('label', {}, 'Uma compra de teste vale (dias)', testDays),
        el('label', {}, 'Produto na Google Play', product),
        el('label', {}, 'Plano base (mensal)', basePlan))),
    el('h2', {}, 'Modelos'),
    el('div', { class: 'grid2' },
      el('div', { class: 'card form' }, el('label', {}, 'Grátis, pro teste (um por linha, em ordem)', free)),
      el('div', { class: 'card form' }, el('label', {}, 'Pagos, pro Novelo+ (um por linha, em ordem)', paid))),
    el('h2', {}, 'Segurança'),
    el('div', { class: 'grid2' },
      el('div', { class: 'card form' },
        el('label', {}, 'Teto de gasto por dia nos modelos pagos (dólares)', budget),
        el('label', {}, 'Pessoas novas por endereço de internet por dia', perIp)),
      el('div', { class: 'card form' },
        el('div', { class: 'label muted' }, 'Chaves e acessos (ficam no servidor, nunca aqui)'),
        el('div', { class: 'row' }, secret(s.free_key, 'Chave grátis'), secret(s.paid_key, 'Chave paga'), secret(s.play, 'Google Play'), secret(s.rtdn, 'Avisos da Play'), secret(s.totp, 'Autenticador')))),
    el('div', { class: 'row', style: 'margin-top:20px' }, save),
  );
}

// ——— Registro ———

async function auditLog() {
  const box = $('audit');
  const { audit } = await api('/audit');
  box.replaceChildren(
    el('h2', {}, 'Registro'),
    el('div', { class: 'scroll' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, el('th', {}, 'Quando'), el('th', {}, 'O quê'), el('th', {}, 'Detalhe'))),
      el('tbody', {}, ...(audit.length ? audit.map((a) => el('tr', {}, el('td', {}, when(a.at)), el('td', {}, a.action), el('td', { class: 'mono' }, a.detail || ''))) : [el('tr', {}, el('td', { colspan: 3, class: 'muted' }, 'Nada ainda.'))])))),
  );
}

// ——— Navegação e entrada ———

const TABS = { overview, people, settings, audit: auditLog };

async function open(tab) {
  for (const b of document.querySelectorAll('#tabs button')) b.toggleAttribute('aria-current', b.dataset.tab === tab);
  for (const s of document.querySelectorAll('.tab')) s.hidden = s.id !== tab;
  try {
    await TABS[tab]();
  } catch (e) {
    if (e.message !== 'not_admin') toast(`Erro: ${e.message}`);
  }
}

function show(view) {
  $('login').hidden = view !== 'login';
  $('app').hidden = view !== 'app';
}

document.addEventListener('DOMContentLoaded', async () => {
  for (const b of document.querySelectorAll('#tabs button')) b.addEventListener('click', () => open(b.dataset.tab));
  $('logout').addEventListener('click', async () => {
    await api('/logout', { method: 'POST' }).catch(() => {});
    show('login');
  });
  $('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('login-error').hidden = true;
    try {
      await api('/login', { method: 'POST', body: { key: $('login-key').value, code: $('login-code').value } });
      $('login-key').value = '';
      $('login-code').value = '';
      show('app');
      open('overview');
    } catch (err) {
      $('login-error').textContent = err.message === 'too_many_tries' ? 'Muitas tentativas hoje. Tente amanhã.' : 'Chave ou código errado.';
      $('login-error').hidden = false;
    }
  });
  try {
    await api('/me');
    show('app');
    open('overview');
  } catch {
    show('login');
  }
});
