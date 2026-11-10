// FNV-1a de 32 bits sobre as partes, para derivar uma semente por cenário e iteração a partir de SEED.
export function hashSeed(...parts) {
  let hash = 2166136261;
  for (const char of parts.join('|')) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return hash;
}

// mulberry32: rápido, sem estado global e com a mesma sequência no k6 e no Node.
export function createRandom(seed) {
  let state = seed >>> 0;
  const next = () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
  return {
    fraction: next,
    int: (min, max) => min + Math.floor(next() * (max - min + 1)),
    pickDistinct(pool, count) {
      const copy = pool.slice();
      for (let i = 0; i < count; i += 1) {
        const j = i + Math.floor(next() * (copy.length - i));
        [copy[i], copy[j]] = [copy[j], copy[i]];
      }
      return copy.slice(0, count);
    },
  };
}
