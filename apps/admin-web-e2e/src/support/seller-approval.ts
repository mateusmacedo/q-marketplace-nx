import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';

import { getStoreEligibility } from './catalog-data';
import { E2E_ENV } from './env';

const SELLER_TOPIC = 'kyb-kyc.seller.v1';

/**
 * Garante a loja do `seller-demo` aprovada, sem a qual o moderador não aprova nem reativa produtos. Se a API não a
 * mostra aprovada, publica o veredito `SellerApproved` no tópico do KYB/KYC, como o roteiro do README, e espera o
 * worker do catálogo projetá-lo. Idempotente: com a loja já aprovada, só consulta.
 */
export async function ensureSellerStoreApproved(timeoutMs = 60_000): Promise<void> {
  const before = await getStoreEligibility();
  if (before.status === 'APPROVED') {
    return;
  }
  publishSellerApproved(E2E_ENV.sellerStoreId);
  const deadline = Date.now() + timeoutMs;
  let current = before;
  while (Date.now() < deadline) {
    await sleep(500);
    current = await getStoreEligibility();
    if (current.status === 'APPROVED') {
      return;
    }
  }
  throw new Error(
    `A loja ${E2E_ENV.sellerStoreId} continua ${current.status} ${timeoutMs / 1000} s depois do SellerApproved em ` +
      `${SELLER_TOPIC}. Confira se o catalog-worker está no ar e consumindo o tópico (Redpanda Console em ` +
      `http://localhost:8090, grupo de consumo do catálogo).`
  );
}

/** Publica o veredito `SellerApproved` da loja no tópico do KYB/KYC pelo `kafka-console-producer` do contêiner do Kafka. */
export function publishSellerApproved(storeId: string): void {
  const event = {
    eventId: randomUUID(),
    eventType: 'SellerApproved',
    eventVersion: 1,
    source: 'kyb-kyc',
    tenantId: E2E_ENV.tenantId,
    occurredAt: new Date().toISOString(),
    data: { storeId }
  };
  const args = [
    'compose',
    '-f',
    E2E_ENV.composeFile,
    '--profile',
    'infra',
    'exec',
    '-T',
    'kafka',
    '/opt/kafka/bin/kafka-console-producer.sh',
    '--bootstrap-server',
    'localhost:9092',
    '--topic',
    SELLER_TOPIC,
    '--reader-property',
    'parse.key=true',
    '--reader-property',
    'key.separator=|'
  ];
  const result = spawnSync('docker', args, { input: `${storeId}|${JSON.stringify(event)}\n`, encoding: 'utf8', timeout: 60_000 });
  if (result.error || result.status !== 0) {
    throw new Error(
      `Não foi possível publicar o SellerApproved da loja ${storeId} em ${SELLER_TOPIC} ` +
        `(docker ${args.slice(0, 8).join(' ')} ...): ${result.error?.message ?? result.stderr}`
    );
  }
}
