/** O tempo do Novelo é o de Brasília (UTC−3, sem horário de verão desde 2019). */
const BRT_MS = 3 * 60 * 60 * 1000;
export const DAY_MS = 86_400_000;

/** O dia de [now] em Brasília: "2026-11-12". */
export function day(now) {
  return new Date(now - BRT_MS).toISOString().slice(0, 10);
}

/** Quando o dia de [now] acaba (a meia-noite seguinte, em Brasília), em ms. */
export function dayEnd(now) {
  const d = new Date(now - BRT_MS);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) + BRT_MS;
}

/** A segunda-feira (00:00 de Brasília) da semana de [now], como dia: "2026-11-09". */
export function weekStart(now) {
  const d = new Date(now - BRT_MS);
  const back = (d.getUTCDay() + 6) % 7; // segunda = 0
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - back)).toISOString().slice(0, 10);
}

/** Quando a semana de [now] acaba (a segunda-feira seguinte, 00:00 de Brasília), em ms. */
export function weekEnd(now) {
  const start = Date.parse(`${weekStart(now)}T00:00:00Z`);
  return start + 7 * DAY_MS + BRT_MS;
}

/** Os dias de [from] a [to] (inclusive), "AAAA-MM-DD". */
export function days(from, to) {
  const out = [];
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= Date.parse(`${to}T00:00:00Z`); t += DAY_MS) out.push(new Date(t).toISOString().slice(0, 10));
  return out;
}
