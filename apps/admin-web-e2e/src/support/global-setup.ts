import type { FullConfig } from '@playwright/test';

import { CATALOG_API_URL, E2E_ENV, KEYCLOAK_REALM_URL } from './env';
import { ensureSellerStoreApproved } from './seller-approval';

interface Probe {
  readonly name: string;
  readonly url: string;
  /** Status que provam que o serviço certo respondeu; a API sem token responde 401, e o 502 do gateway, não. */
  readonly expected: readonly number[];
  readonly hint: string;
}

const STACK_HINT =
  'suba a stack com `docker compose -f infra/local/docker-compose.yml --profile catalog up -d --wait` (README.md)';

/** Falha antes do primeiro teste se a stack ou o dev server não respondem, e garante a loja do seller aprovada. */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL ?? 'http://localhost:4200';
  const probes: Probe[] = [
    { name: 'gateway', url: `${E2E_ENV.gatewayUrl}/healthz`, expected: [200], hint: STACK_HINT },
    {
      name: 'API do catálogo pelo gateway',
      url: `${CATALOG_API_URL}/me`,
      expected: [401],
      hint: STACK_HINT,
    },
    {
      name: 'Keycloak',
      url: `${KEYCLOAK_REALM_URL}/.well-known/openid-configuration`,
      expected: [200],
      hint: STACK_HINT,
    },
    {
      name: 'dev server do painel',
      url: baseURL,
      expected: [200],
      hint: 'rode `npm run dev:admin`',
    },
  ];
  const failures = (await Promise.all(probes.map(check))).filter(
    (failure): failure is string => failure !== undefined,
  );
  if (failures.length > 0) {
    throw new Error(
      `Ambiente do e2e fora do ar:\n${failures.map((failure) => `  - ${failure}`).join('\n')}`,
    );
  }
  await ensureSellerStoreApproved();
}

async function check(probe: Probe): Promise<string | undefined> {
  try {
    const response = await fetch(probe.url, {
      signal: AbortSignal.timeout(5_000),
      redirect: 'manual',
    });
    if (probe.expected.includes(response.status)) {
      return undefined;
    }
    return `${probe.name} (${probe.url}) respondeu ${response.status}, esperado ${probe.expected.join(' ou ')}; ${probe.hint}`;
  } catch (error) {
    return `${probe.name} (${probe.url}) não respondeu: ${rootMessage(error)}; ${probe.hint}`;
  }
}

/** Mensagem da causa de origem: o `fetch` do Node só diz "fetch failed" e põe o ECONNREFUSED na `cause`. */
function rootMessage(error: unknown): string {
  const cause = (error as { cause?: unknown } | null)?.cause;
  if (cause !== undefined) {
    return rootMessage(cause);
  }
  return error instanceof Error ? error.message : String(error);
}
