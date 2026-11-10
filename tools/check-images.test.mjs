import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { extractRefs, findIssues, isOwnImage, parseRef } from './check-images.mjs';

const DIGEST = `sha256:${'a'.repeat(64)}`;
const OTHER = `sha256:${'b'.repeat(64)}`;

describe('parseRef', () => {
  test('separa nome, tag e digest, com registro e porta', () => {
    assert.deepEqual(parseRef(`registry:5000/org/app:1.2@${DIGEST}`), {
      name: 'registry:5000/org/app',
      tag: '1.2',
      digest: DIGEST,
    });
    assert.deepEqual(parseRef('"postgres:16-alpine"'), {
      name: 'postgres',
      tag: '16-alpine',
      digest: null,
    });
  });

  test('recusa referência sem tag ou com variável', () => {
    assert.equal(parseRef('postgres'), null);
    assert.equal(parseRef('marketplace/catalog-inventory:$TAG'), null);
  });
});

describe('isOwnImage', () => {
  test('reconhece a imagem construída aqui, com ou sem registro', () => {
    assert.ok(isOwnImage('marketplace-nx/catalog-inventory'));
    assert.ok(isOwnImage('registry.example.com/marketplace/catalog-inventory'));
    assert.ok(isOwnImage('marketplace-nx/admin-web'));
    assert.ok(!isOwnImage('postgres'));
  });
});

describe('extractRefs', () => {
  test('lê image do compose resolvendo âncoras', () => {
    const text = `x-pg: &pg-image postgres:16@${DIGEST}\nservices:\n  db:\n    image: *pg-image\n  kv:\n    image: redis:7\n`;
    const refs = extractRefs('compose.yml', text).map(
      (ref) => `${ref.name}:${ref.tag}:${ref.line}`,
    );
    assert.deepEqual(refs, ['postgres:16:1', 'postgres:16:4', 'redis:7:6']);
  });

  test('lê bloco repository, tag e digest dos values, pulando comentários', () => {
    const text = `job:\n  image:\n    repository: apache/kafka\n    tag: 4.2.0\n    # comentário\n    digest: ${DIGEST}\n    pullPolicy: IfNotPresent\n`;
    assert.deepEqual(
      extractRefs('values.yaml', text).map((ref) => ref.digest),
      [DIGEST],
    );
  });

  test('lê FROM, image-name, variável de script e lista de imagens', () => {
    const text = [
      `FROM base:1@${DIGEST}`,
      'quarkus.datasource.devservices.image-name=postgres:16',
      `K6_IMAGE=\${K6_IMAGE:-grafana/k6:2@${DIGEST}}`,
      'DEP_IMAGES=(apache/kafka:4 postgres:16)',
      "const KAFKA_IMAGE = 'apache/kafka-native:4';",
    ].join('\n');
    assert.deepEqual(
      extractRefs('f', text).map((ref) => ref.name),
      ['base', 'postgres', 'grafana/k6', 'apache/kafka', 'postgres', 'apache/kafka-native'],
    );
  });

  test('ignora a imagem construída no repositório', () => {
    assert.deepEqual(extractRefs('f', 'image: marketplace-nx/catalog-inventory:local\n'), []);
  });
});

describe('findIssues', () => {
  const ref = (name, tag, digest, line = 1) => ({ name, tag, digest, file: 'f', line });

  test('aprova digest presente e versão única', () => {
    assert.deepEqual(
      findIssues([ref('postgres', '16', DIGEST), ref('postgres', '16', DIGEST, 2)]),
      [],
    );
  });

  test('acusa imagem sem digest', () => {
    assert.match(findIssues([ref('postgres', '16', null)])[0], /f:1: postgres:16 sem digest/);
  });

  test('acusa o mesmo repositório com tag ou digest diferente', () => {
    const issues = findIssues([ref('postgres', '16', DIGEST), ref('postgres', '16', OTHER, 2)]);
    assert.equal(issues.length, 1);
    assert.match(issues[0], /postgres com versões diferentes/);
  });
});
