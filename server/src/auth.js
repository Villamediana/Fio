/** Assinaturas, passes e o código do autenticador do painel (Web Crypto: roda em qualquer Node 20+). */

const enc = new TextEncoder();

export function b64url(bytes) {
  let s = '';
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export async function hmac(secret, text) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64url(await crypto.subtle.sign('HMAC', key, enc.encode(text)));
}

/** a == b sem deixar o tempo da comparação contar nada. */
export function same(a, b) {
  a = String(a ?? '');
  b = String(b ?? '');
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

export const INSTALL = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PASS_DAYS = 30;

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
  if (!Number.isFinite(issued) || issued > now + 60_000 || now - issued > PASS_DAYS * 86_400_000) return null;
  return same(await hmac(secret, `v1.${parts[1]}.${parts[2]}`), parts[3]) ? parts[1] : null;
}

// ——— Painel: a sessão (um cookie assinado) e o código de 6 dígitos (TOTP, RFC 6238) ———

export const ADMIN_COOKIE = 'nv_admin';
const ADMIN_HOURS = 12;

export async function adminSession(secret, now) {
  const exp = now + ADMIN_HOURS * 3_600_000;
  return `${exp}.${await hmac(secret, `admin.${exp}`)}`;
}

export async function adminValid(secret, value, now) {
  const [exp, sig] = String(value || '').split('.');
  if (!exp || !sig || !(Number(exp) > now)) return false;
  return same(await hmac(secret, `admin.${exp}`), sig);
}

export function cookie(request, name) {
  for (const part of (request.headers.get('cookie') || '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return v.join('=');
  }
  return null;
}

function base32(text) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = String(text || '').toUpperCase().replace(/[^A-Z2-7]/g, '');
  let bits = '';
  for (const c of clean) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const out = new Uint8Array(Math.floor(bits.length / 8));
  for (let i = 0; i < out.length; i++) out[i] = parseInt(bits.slice(i * 8, i * 8 + 8), 2);
  return out;
}

/** O código de 6 dígitos do autenticador pra [secret] (base32) no passo de 30 s de [now]. */
export async function totp(secret, now, step = 0) {
  const counter = Math.floor(now / 30_000) + step;
  const msg = new Uint8Array(8);
  new DataView(msg.buffer).setBigUint64(0, BigInt(counter));
  const key = await crypto.subtle.importKey('raw', base32(secret), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const h = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg));
  const o = h[h.length - 1] & 0x0f;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1_000_000).padStart(6, '0');
}

/** O código confere com o passo de agora ou um ao lado (relógios um pouco fora). */
export async function totpOk(secret, code, now) {
  if (!/^\d{6}$/.test(String(code || ''))) return false;
  for (const step of [-1, 0, 1]) if (same(await totp(secret, now, step), code)) return true;
  return false;
}
