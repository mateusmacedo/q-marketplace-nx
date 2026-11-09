import { transitionActorLabel } from './transition-actor';

describe('autor da mudança no histórico', () => {
  it('mostra "Você" quando a mudança foi de quem consulta, mesmo do outro lado', () => {
    expect(transitionActorLabel({ actorSide: 'MODERATION', byCaller: true })).toBe('Você');
    expect(transitionActorLabel({ actorSide: 'STORE', byCaller: true })).toBe('Você');
  });

  it('mostra o lado quando a mudança foi de outra pessoa, sem identificá-la', () => {
    expect(transitionActorLabel({ actorSide: 'STORE', byCaller: false })).toBe('Loja');
    expect(transitionActorLabel({ actorSide: 'MODERATION', byCaller: false })).toBe('Moderação');
  });

  it('não mostra autor quando o servidor não informa o lado', () => {
    expect(transitionActorLabel({ byCaller: false })).toBeUndefined();
    expect(transitionActorLabel(null)).toBeUndefined();
  });
});
