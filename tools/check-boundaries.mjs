// WHY: limites arquiteturais por camada (ADR 002, 003 e 009); a tabela de regras está na ADR 010 em docs/decisoes.md.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const LAYERS = ['domain', 'application', 'service'];
const QUARKUS_BOM =
  '${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}';
const LIB_EXTERNAL_ALLOWED = {
  domain: { coordinates: [], platforms: [] },
  application: { coordinates: ['io.smallrye.reactive:mutiny'], platforms: [QUARKUS_BOM] },
};
const MAIN_CONFIGURATIONS = new Set([
  'api',
  'implementation',
  'compileOnly',
  'compileOnlyApi',
  'runtimeOnly',
  'annotationProcessor',
]);

export const packageOf = (context) => context.replaceAll('-', '');

/** Substitui comentários e literais Java por espaços, preservando quebras de linha (e números de linha). */
export function stripJava(source) {
  let out = '';
  let i = 0;
  const blank = (ch) => (ch === '\n' ? '\n' : ' ');
  while (i < source.length) {
    if (source.startsWith('//', i)) {
      while (i < source.length && source[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (source.startsWith('/*', i)) {
      out += '  ';
      i += 2;
      while (i < source.length && !source.startsWith('*/', i)) out += blank(source[i++]);
      if (i < source.length) {
        out += '  ';
        i += 2;
      }
    } else if (source.startsWith('"""', i)) {
      out += '   ';
      i += 3;
      while (i < source.length && !source.startsWith('"""', i)) {
        if (source[i] === '\\') {
          out += ' ';
          i++;
        }
        if (i < source.length) out += blank(source[i++]);
      }
      if (i < source.length) {
        out += '   ';
        i += 3;
      }
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      out += ' ';
      i++;
      while (i < source.length && source[i] !== quote && source[i] !== '\n') {
        if (source[i] === '\\') {
          out += ' ';
          i++;
        }
        if (i < source.length && source[i] !== '\n') {
          out += ' ';
          i++;
        }
      }
      if (source[i] === quote) {
        out += ' ';
        i++;
      }
    } else {
      out += source[i++];
    }
  }
  return out;
}

/** Remove comentários Groovy preservando literais (necessários para ler as coordenadas). */
export function stripGroovyComments(source) {
  let out = '';
  let i = 0;
  while (i < source.length) {
    if (source.startsWith('//', i)) {
      while (i < source.length && source[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (source.startsWith('/*', i)) {
      i += 2;
      while (i < source.length && !source.startsWith('*/', i))
        out += source[i++] === '\n' ? '\n' : ' ';
      i += 2;
    } else if (source.startsWith("'''", i) || source.startsWith('"""', i)) {
      const quote = source.slice(i, i + 3);
      const end = source.indexOf(quote, i + 3);
      const stop = end < 0 ? source.length : end + 3;
      out += source.slice(i, stop);
      i = stop;
    } else if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      let j = i + 1;
      while (j < source.length && source[j] !== quote && source[j] !== '\n')
        j += source[j] === '\\' ? 2 : 1;
      out += source.slice(i, j + 1);
      i = j + 1;
    } else {
      out += source[i++];
    }
  }
  return out;
}

const lineAt = (text, index) => text.slice(0, index).split('\n').length;

/** Referências qualificadas no código: imports (inclusive estáticos e curinga) e nomes totalmente qualificados. */
export function javaReferences(source) {
  const code = stripJava(source);
  const references = [];
  for (const m of code.matchAll(
    /^[ \t]*import[ \t]+(?:static[ \t]+)?([\w.]+?)(?:\.\*)?[ \t]*;/gm,
  )) {
    references.push({ name: m[1], line: lineAt(code, m.index) });
  }
  const withoutImports = code.replace(/^[ \t]*(?:import|package)[ \t][^;]*;/gm, (s) =>
    s.replace(/[^\n]/g, ' '),
  );
  for (const m of withoutImports.matchAll(
    /\b((?:java|javax|jakarta|io|org|com|net|dev)(?:\.[a-z_][a-z0-9_]*)+)\.[A-Z]/g,
  )) {
    references.push({ name: m[1], line: lineAt(withoutImports, m.index) });
  }
  const pkg = /^[ \t]*package[ \t]+([\w.]+)[ \t]*;/m.exec(code);
  return { packageName: pkg?.[1] ?? null, references };
}

const within = (name, prefix) => name === prefix || name.startsWith(prefix + '.');

/** Decide se uma referência é permitida na camada; retorna o motivo da recusa ou null. */
export function referenceViolation(layer, context, contexts, name) {
  const own = `dev.marketplace.${packageOf(context)}`;
  if (layer === 'service') {
    const other = contexts.find(
      (c) => c !== context && within(name, `dev.marketplace.${packageOf(c)}`),
    );
    return other
      ? `referência ao contexto ${other}; integre por contrato (dev.marketplace.contracts.*)`
      : null;
  }
  const denied = ['java.sql', 'io.smallrye.mutiny.vertx'];
  if (denied.some((p) => within(name, p)))
    return `${name} é infraestrutura e não pode aparecer em ${layer}`;
  const allowed = ['java', `${own}.domain`];
  if (layer === 'application') allowed.push(`${own}.application`, 'io.smallrye.mutiny');
  return allowed.some((p) => within(name, p)) ? null : `${name} não é permitido em ${layer}`;
}

/** Valida a declaração de pacote conforme a camada. */
export function packageViolation(layer, context, packageName) {
  const own = `dev.marketplace.${packageOf(context)}`;
  if (!packageName) return 'arquivo sem declaração de pacote';
  if (layer === 'service') {
    const ok =
      within(packageName, own) &&
      !within(packageName, `${own}.domain`) &&
      !within(packageName, `${own}.application`);
    return ok ? null : `pacote ${packageName} fora de ${own} (exceto .domain e .application)`;
  }
  return within(packageName, `${own}.${layer}`)
    ? null
    : `pacote ${packageName} deveria estar em ${own}.${layer}`;
}

/** Extrai dependências declaradas em build.gradle; formatos não suportados viram erro explícito. */
export function gradleDependencies(source) {
  const code = stripGroovyComments(source);
  const projects = [],
    externals = [],
    unsupported = [];
  for (const m of code.matchAll(
    /\b([A-Za-z]+)\s*\(?\s*project\s*\(\s*(?:path\s*:\s*)?['"]:([^'"]+)['"]\s*\)/g,
  )) {
    projects.push({ configuration: m[1], name: m[2], line: lineAt(code, m.index) });
  }
  for (const m of code.matchAll(
    /\b([A-Za-z]+)\s*\(?\s*(?:(platform|enforcedPlatform)\s*\(\s*)?(["'])([^"'\n]*:[^"'\n]*)\3/g,
  )) {
    if (m[1] === 'project') continue;
    externals.push({
      configuration: m[1],
      platform: Boolean(m[2]),
      coordinate: m[4],
      line: lineAt(code, m.index),
    });
  }
  for (const m of code.matchAll(/\bprojects\.[A-Za-z]\w*/g)) {
    unsupported.push({ text: m[0], line: lineAt(code, m.index) });
  }
  return { projects, externals, unsupported };
}

/** Acesso ao sistema de arquivos; os testes injetam uma implementação em memória. */
export const nodeFileSystem = {
  exists: (file) => existsSync(file),
  read: (file) => readFileSync(file, 'utf8'),
  list: (dir) =>
    readdirSync(dir, { withFileTypes: true }).map((e) => ({
      name: e.name,
      directory: e.isDirectory(),
    })),
};

function javaFiles(dir, fs) {
  if (!fs.exists(dir)) return [];
  return fs.list(dir).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.directory ? javaFiles(file, fs) : entry.name.endsWith('.java') ? [file] : [];
  });
}

/** Executa todas as regras e devolve a lista completa de violações (vazia quando tudo está conforme). */
export function checkWorkspace(root, fs = nodeFileSystem) {
  const violations = [];
  const relative = (file) => path.relative(root, file).split(path.sep).join('/');
  const report = (file, line, message) => violations.push({ file: relative(file), line, message });
  const contexts = JSON.parse(fs.read(path.join(root, 'tools/contexts.json')));
  const contractProjects = new Set(contexts.map((c) => `${c}-contracts`));
  let modules = 0;
  for (const context of contexts) {
    for (const layer of LAYERS) {
      const dir = path.join(
        root,
        layer === 'service' ? `apps/${context}-service` : `libs/${context}/${layer}`,
      );
      const required =
        layer === 'domain'
          ? []
          : layer === 'application'
            ? [`${context}-domain`]
            : [`${context}-application`, `${context}-domain`];
      const isAllowedExtra = (name) => layer === 'service' && contractProjects.has(name);
      const checkSet = (file, line, kind, actual) => {
        for (const name of required.filter((r) => !actual.includes(r)))
          report(file, line, `${kind}: falta ${name}`);
        for (const name of actual.filter((a) => !required.includes(a) && !isAllowedExtra(a)))
          report(file, line, `${kind}: ${name} não é permitido`);
      };
      modules++;

      const projectFile = path.join(dir, 'project.json');
      if (!fs.exists(projectFile)) {
        report(projectFile, 1, 'project.json ausente');
        continue;
      }
      // WHY: o @nx/gradle tira do Gradle as dependências entre módulos (ADR 025); repeti-las aqui recriaria um grafo
      // mantido à mão. Só o contrato de outro contexto, que o Gradle não enxerga, entra no project.json.
      for (const name of JSON.parse(fs.read(projectFile)).implicitDependencies ?? []) {
        if (!isAllowedExtra(name)) {
          report(
            projectFile,
            1,
            `implicitDependencies: ${name} não é permitido; dependências entre módulos vêm do Gradle (ADR 025)`,
          );
        }
      }

      const buildFile = path.join(dir, 'build.gradle');
      if (!fs.exists(buildFile)) {
        report(buildFile, 1, 'build.gradle ausente');
        continue;
      }
      const gradle = gradleDependencies(fs.read(buildFile));
      for (const u of gradle.unsupported)
        report(
          buildFile,
          u.line,
          `formato não suportado pelo verificador: ${u.text}; use project(':nome')`,
        );
      const mainProjects = gradle.projects.filter((p) => !p.configuration.startsWith('test'));
      checkSet(
        buildFile,
        mainProjects[0]?.line ?? 1,
        'dependências Gradle',
        mainProjects.map((p) => p.name),
      );
      if (layer !== 'service') {
        const allowed = LIB_EXTERNAL_ALLOWED[layer];
        for (const dep of gradle.externals) {
          if (dep.configuration.startsWith('test')) continue;
          if (!MAIN_CONFIGURATIONS.has(dep.configuration)) {
            report(
              buildFile,
              dep.line,
              `configuração Gradle não reconhecida: ${dep.configuration}`,
            );
            continue;
          }
          const ok = dep.platform
            ? allowed.platforms.includes(dep.coordinate)
            : allowed.coordinates.includes(dep.coordinate);
          if (!ok)
            report(
              buildFile,
              dep.line,
              `dependência externa não permitida em ${layer}: ${dep.coordinate}`,
            );
        }
      }

      for (const other of ['kotlin', 'groovy', 'scala']) {
        if (fs.exists(path.join(dir, 'src/main', other)))
          report(
            path.join(dir, 'src/main', other),
            1,
            `linguagem não suportada pelo verificador: ${other}`,
          );
      }
      for (const file of javaFiles(path.join(dir, 'src/main/java'), fs)) {
        const { packageName, references } = javaReferences(fs.read(file));
        const wrongPackage = packageViolation(layer, context, packageName);
        if (wrongPackage) report(file, 1, wrongPackage);
        for (const ref of references) {
          const problem = referenceViolation(layer, context, contexts, ref.name);
          if (problem) report(file, ref.line, problem);
        }
      }
    }
  }
  return { modules, contexts: contexts.length, violations };
}

function main() {
  const { modules, contexts, violations } = checkWorkspace(process.cwd());
  if (violations.length > 0) {
    console.error(`FALHA: ${violations.length} violação(ões) de limites arquiteturais:`);
    for (const v of violations) console.error(`  ${v.file}:${v.line}: ${v.message}`);
    process.exit(1);
  }
  console.log(
    `OK: ${modules} módulos Java em ${contexts} contextos; dependências Gradle, implicitDependencies, pacotes e imports verificados.`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main();
