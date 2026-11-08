// Validação dos parâmetros no Node, antes do Docker. "plan" descreve o perfil; "env" imprime NOME=valor de cada
// parâmetro efetivo, que o run.sh exporta para não repetir os padrões; "kyb" imprime uma linha por rodada de
// vereditos, com pares loja=evento, que o run.sh envia pelo Kafka.
import { kybSchedule } from './kyb.js';
import { parseParams } from './params.js';
import { buildOptions, describePlan, planFor } from './profiles.js';

const command = process.argv[2];
try {
  const params = parseParams(process.env);
  buildOptions(params);
  if (command === 'env') {
    for (const [name, value] of Object.entries(params.effective)) console.log(`${name}=${value}`);
  } else if (command === 'plan') {
    console.log(describePlan(params).join('\n'));
  } else if (command === 'kyb') {
    for (const round of kybSchedule(params, planFor(params))) console.log(round.map((e) => `${e.storeId}=${e.eventType}`).join(' '));
  } else {
    throw new Error(`comando desconhecido ${command}; use plan, env ou kyb`);
  }
} catch (error) {
  console.error(`run.sh: ${error.message}`);
  process.exit(2);
}
