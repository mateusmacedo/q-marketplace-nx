// Carga do catálogo e inventário com k6, por perfil. Rode por tools/load/run.sh <perfil>, que valida os parâmetros,
// aprova a loja pelo Kafka, define o TESTID e envia os vereditos do KYB; parâmetros e leitura em tools/load/README.md.
import { params, plan } from './config.js';
import { createMass } from './mass.js';
import { buildReport, renderMarkdown } from './lib/summary.js';
import { buildOptions } from './profiles.js';
import { verifyRun } from './verify.js';

export {
  catalogBrowse, categoryAdmin, hotSku, lastUnit, moderation, offerPricing, outboxProbe, productImport,
  productLifecycle, reservationExpiry, reservationLifecycle, spreadReserveRelease, stockAdjust, storefront,
} from './scenarios.js';

if (!params.testid) throw new Error('main: TESTID ausente; rode por tools/load/run.sh');

export const options = buildOptions(params);

export function setup() {
  return createMass();
}

export function teardown(data) {
  verifyRun(data);
}

export function handleSummary(data) {
  const report = buildReport(data, { params, plan });
  const markdown = renderMarkdown(report);
  const directory = `/results/${params.testid}`;
  return {
    stdout: markdown,
    [`${directory}/summary.md`]: markdown,
    [`${directory}/summary.json`]: JSON.stringify({ ...report, k6: data }, null, 2),
  };
}
