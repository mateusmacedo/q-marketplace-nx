import { readFileSync } from 'node:fs';
import { execFileSync, spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
const contexts = JSON.parse(readFileSync('tools/contexts.json', 'utf8'));
const POSTGRES_IMAGE =
  'postgres:16.15-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea';
const KAFKA_IMAGE =
  'apache/kafka-native:4.2.0@sha256:777f2dddec6970003f1f27922a8c317d87140567b0537e801d35669ad9a81faf';

// Serviços com dependências de infraestrutura recebem recursos efêmeros e configuração explícita.
const requirements = {
  'catalog-inventory': async () => {
    const db = await startPostgres('catalog');
    const kafka = await startKafka().catch((error) => {
      db.stop();
      throw error;
    });
    return {
      env: {
        QUARKUS_DATASOURCE_REACTIVE_URL: `postgresql://127.0.0.1:${db.port}/catalog`,
        QUARKUS_DATASOURCE_JDBC_URL: `jdbc:postgresql://127.0.0.1:${db.port}/catalog`,
        QUARKUS_DATASOURCE_USERNAME: 'postgres',
        QUARKUS_DATASOURCE_PASSWORD: 'smoke',
        CATALOG_JWT_ISSUER: 'https://smoke.invalid',
        CATALOG_JWT_JWKS_URL: 'https://smoke.invalid/jwks',
        CATALOG_JWT_AUDIENCE: 'catalog-inventory',
        KAFKA_BOOTSTRAP_SERVERS: `127.0.0.1:${kafka.port}`,
        QUARKUS_MANAGEMENT_PORT: '19091',
      },
      // WHY: o catálogo publica saúde e métricas na porta de gestão (ADR 020).
      healthPort: 19091,
      stop: () => {
        kafka.stop();
        db.stop();
      },
    };
  },
};

async function startPostgres(database) {
  const id = execFileSync(
    'docker',
    [
      'run',
      '--rm',
      '-d',
      '-e',
      'POSTGRES_PASSWORD=smoke',
      '-e',
      `POSTGRES_DB=${database}`,
      '-p',
      '127.0.0.1::5432',
      POSTGRES_IMAGE,
    ],
    { encoding: 'utf8' },
  ).trim();
  const stop = () => execFileSync('docker', ['rm', '-f', id], { stdio: 'ignore' });
  try {
    const port = execFileSync('docker', ['port', id, '5432/tcp'], { encoding: 'utf8' })
      .trim()
      .split('\n')[0]
      .split(':')
      .pop();
    // WHY: durante a inicialização da imagem o servidor só escuta no socket local; TCP indica banco pronto.
    for (let attempt = 0; attempt < 120; attempt++) {
      try {
        execFileSync(
          'docker',
          ['exec', id, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', database],
          { stdio: 'ignore' },
        );
        return { port, stop };
      } catch {
        await delay(500);
      }
    }
    throw new Error(`PostgreSQL de smoke não ficou pronto (${id})`);
  } catch (error) {
    stop();
    throw error;
  }
}

// WHY: o broker anuncia o endereço que o cliente usa; a porta do host precisa ser conhecida antes de criar o contêiner.
async function freePort() {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function startKafka() {
  const port = await freePort();
  const env = {
    KAFKA_NODE_ID: '1',
    KAFKA_PROCESS_ROLES: 'broker,controller',
    KAFKA_LISTENERS: `PLAINTEXT://:${port},CONTROLLER://:9093`,
    KAFKA_ADVERTISED_LISTENERS: `PLAINTEXT://127.0.0.1:${port}`,
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT',
    KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
    KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:9093',
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1',
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1',
  };
  const args = ['run', '--rm', '-d', '-p', `127.0.0.1:${port}:${port}`];
  for (const [key, value] of Object.entries(env)) args.push('-e', `${key}=${value}`);
  const id = execFileSync('docker', [...args, KAFKA_IMAGE], { encoding: 'utf8' }).trim();
  const stop = () => execFileSync('docker', ['rm', '-f', id], { stdio: 'ignore' });
  // WHY: o proxy de portas do Docker aceita TCP antes de o broker ouvir; a linha de log confirma o broker no ar.
  let logs = '';
  for (let attempt = 0; attempt < 120; attempt++) {
    try {
      logs = execFileSync('docker', ['logs', id], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
      });
    } catch (error) {
      throw new Error(`Kafka de smoke encerrou antes de ficar pronto (${id}): ${error.message}`);
    }
    if (logs.includes('Kafka Server started')) return { port, stop };
    await delay(500);
  }
  stop();
  throw new Error(`Kafka de smoke não ficou pronto (${id}):\n${logs.slice(-2000)}`);
}

for (const context of contexts) {
  const requirement = requirements[context]
    ? await requirements[context]()
    : { env: {}, stop: () => {} };
  let output = '';
  const child = spawn(
    'java',
    ['-jar', `apps/${context}-service/build/quarkus-app/quarkus-run.jar`],
    {
      env: { ...process.env, ...requirement.env, QUARKUS_HTTP_PORT: '19081' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  child.stdout.on('data', (data) => {
    output += data;
  });
  child.stderr.on('data', (data) => {
    output += data;
  });
  const stopped = new Promise((resolve) => child.on('close', resolve));
  child.on('error', (error) => {
    output += error.message;
  });
  try {
    let ready = false;
    for (let attempt = 0; attempt < 120; attempt++) {
      if (child.exitCode !== null) break;
      try {
        const response = await fetch(
          `http://localhost:${requirement.healthPort ?? 19081}/q/health/ready`,
          { signal: AbortSignal.timeout(500) },
        );
        if (response.ok && (await response.json()).status === 'UP') {
          ready = true;
          break;
        }
      } catch {
        /* Inicialização ainda em andamento. */
      }
      await delay(250);
    }
    if (!ready) throw new Error(`${context} não iniciou: ${output}`);
    const info = await fetch('http://localhost:19081/internal/info').then((r) => r.json());
    if (info.context !== context) throw new Error(`Contexto inesperado: ${JSON.stringify(info)}`);
    console.log(`OK: ${context}`);
  } finally {
    child.kill('SIGTERM');
    const killer = setTimeout(() => child.kill('SIGKILL'), 5000);
    await stopped;
    clearTimeout(killer);
    requirement.stop();
  }
}
