/** Rótulo de cada lado que faz uma transição; o lado que o painel não conhece não mostra autor. */
const SIDE_LABELS: Readonly<Record<string, string>> = {
  STORE: 'Loja',
  MODERATION: 'Moderação',
};

/**
 * Quem fez a mudança do histórico, como o servidor informa: "Você" quando foi quem consulta, senão o lado (loja ou
 * moderação). A pessoa nunca aparece; `undefined` quando o servidor não informa o lado.
 */
export function transitionActorLabel(
  change:
    { readonly actorSide?: string | null; readonly byCaller?: boolean | null } | null | undefined,
): string | undefined {
  if (change?.byCaller) {
    return 'Você';
  }
  return change?.actorSide ? SIDE_LABELS[change.actorSide] : undefined;
}
