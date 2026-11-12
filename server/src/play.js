/**
 * A Google Play: confere uma assinatura pelo comprovante (purchaseToken) com a Google Play Developer
 * API, com a conta de serviço do Play Console (GOOGLE_SERVICE_ACCOUNT_JSON). Sem ela, não confere
 * nada (o servidor responde "billing_not_configured").
 */
import { b64url } from './auth.js';

const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';

let cached = null; // { token, until, email }

function pemToDer(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

/** Um token de acesso da Google pra conta de serviço (guardado até quase vencer). */
export async function accessToken(account, now, fetcher) {
  if (cached && cached.email === account.client_email && cached.until > now + 60_000) return cached.token;
  const header = b64url(new TextEncoder().encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const iat = Math.floor(now / 1000);
  const claims = b64url(new TextEncoder().encode(JSON.stringify({
    iss: account.client_email,
    scope: SCOPE,
    aud: account.token_uri || 'https://oauth2.googleapis.com/token',
    iat,
    exp: iat + 3600,
  })));
  const key = await crypto.subtle.importKey('pkcs8', pemToDer(account.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = b64url(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claims}`)));
  const r = await fetcher(account.token_uri || 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${header}.${claims}.${sig}` }),
  });
  if (!r.ok) throw new Error(`google token ${r.status}`);
  const data = await r.json();
  cached = { token: data.access_token, until: now + (Number(data.expires_in) || 3600) * 1000, email: account.client_email };
  return cached.token;
}

const ENTITLED = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED']);

/**
 * O que a Google diz do comprovante [token]: {state, until, product, account (o código da instalação
 * que comprou), entitled, ack}. entitled: a pessoa tem o Novelo+ agora (ativa, em carência, ou
 * cancelada mas ainda dentro do período pago).
 */
export async function subscription(env, pkg, token, now, fetcher) {
  const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON);
  const auth = await accessToken(account, now, fetcher);
  const r = await fetcher(`${API}/${encodeURIComponent(pkg)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(token)}`, {
    headers: { authorization: `Bearer ${auth}` },
  });
  if (r.status === 404 || r.status === 410) return { state: 'NOT_FOUND', entitled: false, until: 0 };
  if (!r.ok) throw new Error(`google subscription ${r.status}`);
  const s = await r.json();
  const items = Array.isArray(s.lineItems) ? s.lineItems : [];
  const until = Math.max(0, ...items.map((i) => Date.parse(i.expiryTime) || 0));
  return {
    state: s.subscriptionState || 'UNKNOWN',
    until,
    product: items[0]?.productId || null,
    install: s.externalAccountIdentifiers?.obfuscatedExternalAccountId || null,
    entitled: ENTITLED.has(s.subscriptionState) && until > now,
    ack: s.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_PENDING' ? 'pending' : 'done',
  };
}

/** Confirma a compra na Google (sem isso ela devolve o dinheiro em 3 dias). */
export async function acknowledge(env, pkg, product, token, now, fetcher) {
  const auth = await accessToken(JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON), now, fetcher);
  const r = await fetcher(`${API}/${encodeURIComponent(pkg)}/purchases/subscriptions/${encodeURIComponent(product)}/tokens/${encodeURIComponent(token)}:acknowledge`, {
    method: 'POST',
    headers: { authorization: `Bearer ${auth}`, 'content-type': 'application/json' },
    body: '{}',
  });
  if (!r.ok && r.status !== 400) throw new Error(`google acknowledge ${r.status}`);
}

/** Pra os testes: esquece o token guardado. */
export function forget() {
  cached = null;
}
