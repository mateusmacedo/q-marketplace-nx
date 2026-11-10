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

const blankOut = (text) => text.replace(/[^\n]/g, ' ');

const lineEnd = (source, from) => {
  const newline = source.indexOf('\n', from);
  return newline < 0 ? source.length : newline;
};

/** Reescreve o fonte trocando cada trecho que `replacement` reconhece e copiando o resto. */
function rewrite(source, replacement) {
  let out = '';
  let i = 0;
  while (i < source.length) {
    const piece = replacement(source, i);
    if (piece) {
      out += piece.text;
      i = piece.end;
    } else {
      out += source[i++];
    }
  }
  return out;
}

function textBlockEnd(source, from) {
  let i = from + 3;
  while (i < source.length && !source.startsWith('"""', i)) i += source[i] === '\\' ? 2 : 1;
  return i < source.length ? i + 3 : source.length;
}

/** Fim de uma string Java; a barra invertida não escapa a quebra de linha, e a string não passa dela. */
function javaStringEnd(source, from) {
  const quote = source[from];
  let i = from + 1;
  while (i < source.length && source[i] !== quote && source[i] !== '\n') {
    const escapes = source[i] === '\\' && i + 1 < source.length && source[i + 1] !== '\n';
    i += escapes ? 2 : 1;
  }
  return source[i] === quote ? i + 1 : i;
}

/** Fim do comentário ou literal Java que começa em `from`; `from` quando ali não começa nenhum. */
function javaSkippedEnd(source, from) {
  if (source.startsWith('//', from)) return lineEnd(source, from);
  if (source.startsWith('/*', from)) {
    const close = source.indexOf('*/', from + 2);
    return close < 0 ? source.length : close + 2;
  }
  if (source.startsWith('"""', from)) return textBlockEnd(source, from);
  if (source[from] === '"' || source[from] === "'") return javaStringEnd(source, from);
  return from;
}

const javaReplacement = (source, from) => {
  const end = javaSkippedEnd(source, from);
  return end > from ? { text: blankOut(source.slice(from, end)), end } : null;
};

/** Substitui comentários e literais Java por espaços, preservando quebras de linha (e números de linha). */
export function stripJava(source) {
  return rewrite(source, javaReplacement);
}

const GROOVY_TRIPLE_QUOTES = ["'''", '"""'];

/** Fim de uma string Groovy (simples, dupla ou tripla), que o parser copia como está. */
function groovyLiteralEnd(source, from) {
  const triple = GROOVY_TRIPLE_QUOTES.find((quote) => source.startsWith(quote, from));
  if (triple) {
    const close = source.indexOf(triple, from + 3);
    return close < 0 ? source.length : close + 3;
  }
  if (source[from] !== '"' && source[from] !== "'") return from;
  const quote = source[from];
  let i = from + 1;
  while (i < source.length && source[i] !== quote && source[i] !== '\n')
    i += source[i] === '\\' ? 2 : 1;
  return Math.min(i + 1, source.length);
}

// NOTE: o comentário de bloco perde os delimitadores (só o miolo vira espaço), o que mantém as quebras de linha.
const groovyReplacement = (source, from) => {
  if (source.startsWith('//', from)) {
    const end = lineEnd(source, from);
    return { text: blankOut(source.slice(from, end)), end };
  }
  if (source.startsWith('/*', from)) {
    const close = source.indexOf('*/', from + 2);
    const bodyEnd = close < 0 ? source.length : close;
    return {
      text: blankOut(source.slice(from + 2, bodyEnd)),
      end: close < 0 ? bodyEnd : close + 2,
    };
  }
  const end = groovyLiteralEnd(source, from);
  return end > from ? { text: source.slice(from, end), end } : null;
};

/** Remove comentários Groovy preservando literais (necessários para ler as coordenadas). */
export function stripGroovyComments(source) {
  return rewrite(source, groovyReplacement);
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
    /\b([A-Za-z]+)\s*(?:\(\s*)?project\s*\(\s*(?:path\s*:\s*)?['"]:([^'"]+)['"]\s*\)/g,
  )) {
    projects.push({ configuration: m[1], name: m[2], line: lineAt(code, m.index) });
  }
  for (const m of code.matchAll(
    /\b([A-Za-z]+)\s*(?:\(\s*)?(?:(platform|enforcedPlatform)\s*\(\s*)?(["'])([^"'\n:]*:[^"'\n]*)\3/g,
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
    if (entry.directory) return javaFiles(file, fs);
    return entry.name.endsWith('.java') ? [file] : [];
  });
}

function requiredProjects(layer, context) {
  if (layer === 'domain') return [];
  if (layer === 'application') return [`${context}-domain`];
  return [`${context}-application`, `${context}-domain`];
}

const layerDirectory = (root, context, layer) =>
  path.join(root, layer === 'service' ? `apps/${context}-service` : `libs/${context}/${layer}`);

const isAllowedExtra = (env, layer, name) => layer === 'service' && env.contractProjects.has(name);

function checkProjectSet(env, unit, file, line, kind, actual) {
  const { context, layer } = unit;
  const required = requiredProjects(layer, context);
  for (const name of required.filter((r) => !actual.includes(r)))
    env.report(file, line, `${kind}: falta ${name}`);
  for (const name of actual.filter((a) => !required.includes(a) && !isAllowedExtra(env, layer, a)))
    env.report(file, line, `${kind}: ${name} não é permitido`);
}

function checkImplicitDependencies(env, unit, projectFile) {
  // WHY: o @nx/gradle tira do Gradle as dependências entre módulos (ADR 025); repeti-las aqui recriaria um grafo
  // mantido à mão. Só o contrato de outro contexto, que o Gradle não enxerga, entra no project.json.
  for (const name of JSON.parse(env.fs.read(projectFile)).implicitDependencies ?? []) {
    if (!isAllowedExtra(env, unit.layer, name)) {
      env.report(
        projectFile,
        1,
        `implicitDependencies: ${name} não é permitido; dependências entre módulos vêm do Gradle (ADR 025)`,
      );
    }
  }
}

/** Motivo da recusa de uma dependência externa de lib, ou null quando é permitida. */
function externalViolation(layer, dep) {
  if (dep.configuration.startsWith('test')) return null;
  if (!MAIN_CONFIGURATIONS.has(dep.configuration))
    return `configuração Gradle não reconhecida: ${dep.configuration}`;
  const allowed = LIB_EXTERNAL_ALLOWED[layer][dep.platform ? 'platforms' : 'coordinates'];
  return allowed.includes(dep.coordinate)
    ? null
    : `dependência externa não permitida em ${layer}: ${dep.coordinate}`;
}

function checkBuildFile(env, unit, buildFile) {
  const gradle = gradleDependencies(env.fs.read(buildFile));
  for (const u of gradle.unsupported)
    env.report(
      buildFile,
      u.line,
      `formato não suportado pelo verificador: ${u.text}; use project(':nome')`,
    );
  const mainProjects = gradle.projects.filter((p) => !p.configuration.startsWith('test'));
  checkProjectSet(
    env,
    unit,
    buildFile,
    mainProjects[0]?.line ?? 1,
    'dependências Gradle',
    mainProjects.map((p) => p.name),
  );
  if (unit.layer === 'service') return;
  for (const dep of gradle.externals) {
    const problem = externalViolation(unit.layer, dep);
    if (problem) env.report(buildFile, dep.line, problem);
  }
}

function checkSources(env, unit) {
  const { context, layer, dir } = unit;
  for (const other of ['kotlin', 'groovy', 'scala']) {
    const languageDir = path.join(dir, 'src/main', other);
    if (env.fs.exists(languageDir))
      env.report(languageDir, 1, `linguagem não suportada pelo verificador: ${other}`);
  }
  for (const file of javaFiles(path.join(dir, 'src/main/java'), env.fs)) {
    const { packageName, references } = javaReferences(env.fs.read(file));
    const wrongPackage = packageViolation(layer, context, packageName);
    if (wrongPackage) env.report(file, 1, wrongPackage);
    for (const ref of references) {
      const problem = referenceViolation(layer, context, env.contexts, ref.name);
      if (problem) env.report(file, ref.line, problem);
    }
  }
}

function checkLayer(env, unit) {
  const projectFile = path.join(unit.dir, 'project.json');
  if (!env.fs.exists(projectFile)) {
    env.report(projectFile, 1, 'project.json ausente');
    return;
  }
  checkImplicitDependencies(env, unit, projectFile);
  const buildFile = path.join(unit.dir, 'build.gradle');
  if (!env.fs.exists(buildFile)) {
    env.report(buildFile, 1, 'build.gradle ausente');
    return;
  }
  checkBuildFile(env, unit, buildFile);
  checkSources(env, unit);
}

/** Executa todas as regras e devolve a lista completa de violações (vazia quando tudo está conforme). */
export function checkWorkspace(root, fs = nodeFileSystem) {
  const violations = [];
  const relative = (file) => path.relative(root, file).split(path.sep).join('/');
  const report = (file, line, message) => violations.push({ file: relative(file), line, message });
  const contexts = JSON.parse(fs.read(path.join(root, 'tools/contexts.json')));
  const contractProjects = new Set(contexts.map((c) => `${c}-contracts`));
  const env = { fs, report, contexts, contractProjects };
  for (const context of contexts) {
    for (const layer of LAYERS) {
      checkLayer(env, { context, layer, dir: layerDirectory(root, context, layer) });
    }
  }
  return { modules: contexts.length * LAYERS.length, contexts: contexts.length, violations };
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
