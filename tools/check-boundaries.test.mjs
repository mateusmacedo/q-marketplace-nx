import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {
  stripJava, stripGroovyComments, javaReferences, referenceViolation, packageViolation, gradleDependencies, checkWorkspace,
} from './check-boundaries.mjs';

const CONTEXTS = ['catalog-inventory', 'order-management'];
const allowed = (layer, name) => referenceViolation(layer, 'catalog-inventory', CONTEXTS, name) === null;

describe('stripJava', () => {
  test('ignora comentários de linha, de bloco e Javadoc', () => {
    const code = stripJava('int a; // io.vertx\n/* jakarta.inject */ int b; /** io.quarkus */');
    assert.ok(!/io\.vertx|jakarta|io\.quarkus/.test(code));
    assert.match(code, /int a;[\s\S]*int b;/);
  });
  test('ignora literais, text blocks e preserva as linhas', () => {
    const source = 'String s = "io.vertx // x";\nchar c = \'"\';\nString t = """\n  jakarta.x\n  """;\nint z;';
    const code = stripJava(source);
    assert.ok(!/io\.vertx|jakarta/.test(code));
    assert.equal(code.split('\n').length, source.split('\n').length);
    assert.match(code, /int z;/);
  });
  test('não confunde aspas escapadas com fim de literal', () => {
    assert.ok(!stripJava('String s = "a\\"io.vertx.X\\"b"; int ok;').includes('io.vertx'));
  });
});

describe('javaReferences', () => {
  test('encontra imports normais, estáticos e curinga, com número de linha', () => {
    const { references, packageName } = javaReferences(
      'package dev.marketplace.cataloginventory.domain;\nimport java.util.List;\nimport static org.junit.Assert.assertTrue;\nimport io.quarkus.*;');
    assert.equal(packageName, 'dev.marketplace.cataloginventory.domain');
    assert.deepEqual(references.map(r => [r.name, r.line]),
      [['java.util.List', 2], ['org.junit.Assert.assertTrue', 3], ['io.quarkus', 4]]);
  });
  test('encontra nome totalmente qualificado no código e em anotações', () => {
    const names = javaReferences('class A { @jakarta.inject.Inject java.util.List<io.vertx.core.Vertx> v; }')
      .references.map(r => r.name);
    assert.deepEqual(names, ['jakarta.inject', 'java.util', 'io.vertx.core']);
  });
  test('não acusa pacote citado em comentário', () => {
    assert.deepEqual(javaReferences('// WHY: não usar io.vertx.core.Vertx aqui\nclass A {}').references, []);
  });
  test('não confunde acesso a campo com pacote', () => {
    assert.deepEqual(javaReferences('class A { int x = product.price.SCALE + Product.SKU_MAX_LENGTH; }').references, []);
  });
});

describe('referenceViolation', () => {
  test('domínio aceita apenas JDK e o próprio domínio', () => {
    assert.ok(allowed('domain', 'java.math.BigDecimal'));
    assert.ok(allowed('domain', 'dev.marketplace.cataloginventory.domain.Product'));
    for (const name of ['dev.marketplace.cataloginventory.application.CatalogService', 'io.smallrye.mutiny.Uni',
      'jakarta.inject.Inject', 'javax.persistence.Entity', 'com.fasterxml.jackson.annotation.JsonProperty',
      'org.eclipse.microprofile.config.inject.ConfigProperty', 'java.sql.Connection', 'io.quarkus.runtime.Startup']) {
      assert.ok(!allowed('domain', name), name);
    }
  });
  test('aplicação aceita Mutiny, mas não seus bindings Vert.x nem outra infraestrutura', () => {
    for (const name of ['io.smallrye.mutiny.Uni', 'io.smallrye.mutiny.Multi', 'io.smallrye.mutiny.tuples.Tuple2',
      'dev.marketplace.cataloginventory.domain.Product', 'dev.marketplace.cataloginventory.application.Port',
      'java.util.concurrent.CompletionStage']) {
      assert.ok(allowed('application', name), name);
    }
    for (const name of ['io.smallrye.mutiny.vertx.core.AbstractVerticle', 'io.smallrye.config.ConfigMapping',
      'io.smallrye.mutinyx.Foo', 'io.vertx.mutiny.sqlclient.Pool', 'jakarta.enterprise.context.ApplicationScoped',
      'org.hibernate.reactive.mutiny.Mutiny', 'java.sql.Connection', 'dev.marketplace.cataloginventory.persistence.X']) {
      assert.ok(!allowed('application', name), name);
    }
  });
  test('serviço aceita qualquer biblioteca e contratos, mas não outro contexto', () => {
    for (const name of ['io.quarkus.runtime.Startup', 'io.vertx.mutiny.sqlclient.Pool',
      'dev.marketplace.cataloginventory.domain.Product', 'dev.marketplace.contracts.ordermanagement.OrderCreated']) {
      assert.ok(allowed('service', name), name);
    }
    assert.ok(!allowed('service', 'dev.marketplace.ordermanagement.domain.Order'));
  });
});

describe('packageViolation', () => {
  test('exige pacote coerente com a camada', () => {
    assert.equal(packageViolation('domain', 'catalog-inventory', 'dev.marketplace.cataloginventory.domain.stock'), null);
    assert.equal(packageViolation('service', 'catalog-inventory', 'dev.marketplace.cataloginventory.api'), null);
    assert.notEqual(packageViolation('application', 'catalog-inventory', 'dev.marketplace.cataloginventory.domain'), null);
    assert.notEqual(packageViolation('service', 'catalog-inventory', 'dev.marketplace.cataloginventory.domain.X'), null);
    assert.notEqual(packageViolation('domain', 'catalog-inventory', null), null);
  });
});

describe('gradleDependencies', () => {
  test('lê projetos, externas e plataformas, ignorando comentários', () => {
    const deps = gradleDependencies(`dependencies {
      api project(':a-domain') // implementation project(':b-domain')
      /* implementation 'x:y' */
      api platform("\${quarkusPlatformGroupId}:\${quarkusPlatformArtifactId}:\${quarkusPlatformVersion}")
      implementation 'io.smallrye.reactive:mutiny'
      testImplementation 'org.assertj:assertj-core:3.0.0'
      implementation project(path: ':c-domain')
    }`);
    assert.deepEqual(deps.projects.map(p => p.name), ['a-domain', 'c-domain']);
    assert.deepEqual(deps.externals.map(e => [e.configuration, e.platform, e.coordinate]), [
      ['api', true, '${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}'],
      ['implementation', false, 'io.smallrye.reactive:mutiny'],
      ['testImplementation', false, 'org.assertj:assertj-core:3.0.0'],
    ]);
  });
  test('sinaliza acessor tipado em vez de ignorá-lo', () => {
    assert.equal(gradleDependencies('dependencies { implementation projects.catalogInventoryDomain }').unsupported.length, 1);
  });
  test('preserva // dentro de literal', () => {
    assert.match(stripGroovyComments("maven { url 'https://repo.example/x' } // c"), /https:\/\/repo\.example\/x/);
  });
});

describe('checkWorkspace', () => {
  // WHY: sistema de arquivos em memória; o teste não cria diretórios e, portanto, não precisa apagar nada.
  function memoryFileSystem(files) {
    const children = dir => [...files.keys()].filter(f => f.startsWith(dir + path.sep));
    return {
      exists: p => files.has(p) || children(p).length > 0,
      read: p => {
        if (!files.has(p)) throw new Error(`arquivo inexistente: ${p}`);
        return files.get(p);
      },
      list: dir => [...new Map(children(dir).map(f => {
        const [name, ...rest] = path.relative(dir, f).split(path.sep);
        return [name, { name, directory: rest.length > 0 }];
      })).values()],
    };
  }
  function workspace() {
    const root = path.resolve(path.sep, 'workspace-virtual');
    const files = new Map();
    const write = (file, content) => files.set(path.join(root, file), content);
    const remove = file => files.delete(path.join(root, file));
    const check = () => checkWorkspace(root, memoryFileSystem(files));
    write('tools/contexts.json', JSON.stringify(CONTEXTS));
    for (const c of CONTEXTS) {
      const pkg = c.replaceAll('-', '');
      write(`libs/${c}/domain/project.json`, JSON.stringify({}));
      write(`libs/${c}/domain/build.gradle`, '');
      write(`libs/${c}/domain/src/main/java/D.java`, `package dev.marketplace.${pkg}.domain;\nimport java.util.List;\nclass D {}`);
      write(`libs/${c}/domain/src/test/java/DTest.java`, 'package x;\nimport org.junit.jupiter.api.Test;\nimport io.quarkus.Foo;');
      write(`libs/${c}/application/project.json`, JSON.stringify({}));
      write(`libs/${c}/application/build.gradle`, `dependencies {\n  api project(':${c}-domain')\n  api 'io.smallrye.reactive:mutiny'\n}`);
      write(`libs/${c}/application/src/main/java/A.java`,
        `package dev.marketplace.${pkg}.application;\nimport io.smallrye.mutiny.Uni;\n// WHY: nunca io.vertx aqui\nclass A {}`);
      write(`apps/${c}-service/project.json`, JSON.stringify({}));
      write(`apps/${c}-service/build.gradle`,
        `dependencies {\n  implementation project(':${c}-application')\n  implementation project(':${c}-domain')\n  implementation 'io.quarkus:quarkus-arc'\n}`);
      write(`apps/${c}-service/src/main/java/S.java`, `package dev.marketplace.${pkg};\nimport io.quarkus.runtime.Startup;\nclass S {}`);
    }
    return { write, remove, check };
  }

  test('workspace conforme não tem violações; testes e comentários são ignorados', () => {
    const { check } = workspace();
    const result = check();
    assert.deepEqual(result.violations, []);
    assert.equal(result.modules, 6);
  });
  test('relata todas as violações de uma vez, com arquivo e linha', () => {
    const { write, check } = workspace();
    write('libs/catalog-inventory/domain/src/main/java/D.java',
      'package dev.marketplace.cataloginventory.domain;\nimport jakarta.inject.Inject;\nclass D { com.fasterxml.jackson.databind.ObjectMapper m; }');
    write('libs/catalog-inventory/application/build.gradle',
      "dependencies {\n  api project(':catalog-inventory-domain')\n  implementation 'io.quarkus:quarkus-arc'\n}");
    write('apps/order-management-service/src/main/java/S.java',
      'package dev.marketplace.ordermanagement;\nimport dev.marketplace.cataloginventory.domain.Product;\nclass S {}');
    const messages = check().violations.map(v => `${v.file}:${v.line}: ${v.message}`);
    assert.equal(messages.length, 4, messages.join('\n'));
    assert.ok(messages.some(m => m.startsWith('libs/catalog-inventory/domain/src/main/java/D.java:2:') && m.includes('jakarta.inject')));
    assert.ok(messages.some(m => m.startsWith('libs/catalog-inventory/domain/src/main/java/D.java:3:') && m.includes('com.fasterxml')));
    assert.ok(messages.some(m => m.startsWith('libs/catalog-inventory/application/build.gradle:3:') && m.includes('io.quarkus:quarkus-arc')));
    assert.ok(messages.some(m => m.startsWith('apps/order-management-service/src/main/java/S.java:2:') && m.includes('catalog-inventory')));
  });
  test('implicitDependencies só aceita contratos; dependência entre módulos vem do Gradle', () => {
    const { write, check } = workspace();
    write('apps/order-management-service/project.json', JSON.stringify({ implicitDependencies: ['catalog-inventory-contracts'] }));
    assert.deepEqual(check().violations, []);
    write('apps/order-management-service/project.json', JSON.stringify({ implicitDependencies: ['order-management-domain'] }));
    const [violation] = check().violations;
    assert.match(violation.message, /order-management-domain não é permitido; dependências entre módulos vêm do Gradle/);
  });
  test('falta de dependência obrigatória no Gradle é relatada', () => {
    const { write, check } = workspace();
    write('libs/catalog-inventory/application/build.gradle', "dependencies {\n  api 'io.smallrye.reactive:mutiny'\n}");
    const [violation] = check().violations;
    assert.match(violation.message, /dependências Gradle: falta catalog-inventory-domain/);
  });
  test('contexto sem uma das três camadas é relatado', () => {
    const { remove, check } = workspace();
    remove(`apps/${CONTEXTS[1]}-service/project.json`);
    assert.deepEqual(check().violations.map(v => [v.file, v.message]),
      [[`apps/${CONTEXTS[1]}-service/project.json`, 'project.json ausente']]);
  });
  test('linguagem não suportada não passa em silêncio', () => {
    const { write, check } = workspace();
    write('libs/catalog-inventory/domain/src/main/kotlin/K.kt', 'package x');
    assert.match(check().violations[0].message, /kotlin/);
  });
});
