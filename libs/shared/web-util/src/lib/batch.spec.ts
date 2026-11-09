import { batchRunCounts, batchSummary, chunk, plural, runBatch } from './batch';
import { GENERIC_REASON_MESSAGE } from './reasons';

describe('lote', () => {
  const paused = { one: 'pausado', many: 'pausados' };

  it('resume o resultado sem as partes zeradas', () => {
    expect(batchSummary({ succeeded: 6, failed: 2, ignored: 1 }, paused)).toBe('6 pausados, 2 com falha, 1 ignorado');
    expect(batchSummary({ succeeded: 1, failed: 0, ignored: 3 }, paused)).toBe('1 pausado, 3 ignorados');
    expect(batchSummary({ succeeded: 0, failed: 0, ignored: 0 }, paused)).toBe('0 pausados');
  });

  it('concorda o ignorado com o item de nome feminino', () => {
    const deleted = { one: 'excluída', many: 'excluídas', feminine: true };
    expect(batchSummary({ succeeded: 1, failed: 0, ignored: 1 }, deleted)).toBe('1 excluída, 1 ignorada');
    expect(batchSummary({ succeeded: 0, failed: 1, ignored: 2 }, deleted)).toBe('1 com falha, 2 ignoradas');
  });

  it('flexiona pela quantidade', () => {
    expect(plural(1, 'produto', 'produtos')).toBe('1 produto');
    expect(plural(0, 'produto', 'produtos')).toBe('0 produtos');
  });

  it('divide os itens em grupos de até 100', () => {
    const ids = Array.from({ length: 230 }, (_, index) => index);
    expect(chunk(ids).map(group => group.length)).toEqual([100, 100, 30]);
    expect(chunk([], 10)).toEqual([]);
    expect(() => chunk(ids, 0)).toThrow(RangeError);
  });
});

describe('execução do lote', () => {
  interface Item {
    readonly id: string;
    readonly version: number;
  }
  const TYPE = 'https://marketplace.dev/problems/';
  const items: Item[] = ['p1', 'p2', 'p3', 'p4', 'p5'].map((id, index) => ({ id, version: index }));
  const refusal = {
    type: `${TYPE}seller-not-eligible`,
    status: 422,
    reasonDetails: [{ code: 'seller-not-eligible', message: 'A loja ainda não foi aprovada.' }]
  };

  it('envia um grupo por vez e separa os gravados das recusas', async () => {
    const groups: string[][] = [];
    const run = await runBatch({
      items,
      id: item => item.id,
      size: 2,
      send: async group => {
        groups.push(group.map(item => item.id));
        return group.map(item =>
          item.id === 'p3'
            ? { id: item.id, outcome: 'FAILED', problem: refusal }
            : { id: item.id, outcome: 'SUCCEEDED', version: item.version + 1 }
        );
      }
    });

    expect(groups).toEqual([['p1', 'p2'], ['p3', 'p4'], ['p5']]);
    expect(run.succeeded.map(success => [success.item.id, success.result.version])).toEqual([
      ['p1', 1],
      ['p2', 2],
      ['p4', 4],
      ['p5', 5]
    ]);
    expect(run.failed).toEqual([{ item: items[2], problem: refusal }]);
    expect(run.unanswered).toEqual([]);
    expect(run.interrupted).toBe(false);
    expect(batchRunCounts(run, 1)).toEqual({ succeeded: 4, failed: 1, ignored: 1 });
  });

  it('casa o resultado pelo id e, sem id, pela posição do pedido', async () => {
    const byPosition = await runBatch({
      items: items.slice(0, 2),
      id: item => item.id,
      size: 10,
      send: async () => [{ outcome: 'FAILED' }, { outcome: 'SUCCEEDED' }]
    });
    expect(byPosition.failed.map(failure => failure.item.id)).toEqual(['p1']);
    expect(byPosition.failed[0].problem).toEqual({ title: GENERIC_REASON_MESSAGE });
    expect(byPosition.succeeded.map(success => success.item.id)).toEqual(['p2']);

    const outOfOrder = await runBatch({
      items: items.slice(0, 3),
      id: item => item.id,
      size: 10,
      send: async () => [
        { id: 'p2', outcome: 'SUCCEEDED' },
        { id: 'p1', outcome: 'SUCCEEDED' }
      ]
    });
    expect(outOfOrder.succeeded.map(success => success.item.id)).toEqual(['p1', 'p2']);
    expect(outOfOrder.unanswered).toEqual([items[2]]);
  });

  it('para na chamada que falha inteira e devolve os itens sem resposta', async () => {
    let calls = 0;
    const run = await runBatch({
      items,
      id: item => item.id,
      size: 2,
      send: async group => {
        calls++;
        if (calls === 2) {
          throw { status: 0, error: {} };
        }
        return group.map(item => ({ id: item.id, outcome: 'SUCCEEDED' }));
      }
    });

    expect(calls).toBe(2);
    expect(run.succeeded.map(success => success.item.id)).toEqual(['p1', 'p2']);
    expect(run.unanswered.map(item => item.id)).toEqual(['p3', 'p4', 'p5']);
    expect(run.error).toEqual({ type: 'network-error', status: 0 });
    expect(run.interrupted).toBe(true);
    expect(batchRunCounts(run)).toEqual({ succeeded: 2, failed: 3, ignored: 0 });
  });

  it('interrompe antes do próximo grupo quando o sinal é abortado', async () => {
    const controller = new AbortController();
    const run = await runBatch({
      items,
      id: item => item.id,
      size: 2,
      signal: controller.signal,
      send: async group => {
        controller.abort();
        return group.map(item => ({ id: item.id, outcome: 'SUCCEEDED' }));
      }
    });

    expect(run.succeeded.map(success => success.item.id)).toEqual(['p1', 'p2']);
    expect(run.unanswered.map(item => item.id)).toEqual(['p3', 'p4', 'p5']);
    expect(run.error).toBeUndefined();
    expect(run.interrupted).toBe(true);
  });
});
