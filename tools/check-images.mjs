// WHY: a mesma imagem precisa ser o mesmo artefato no compose, no chart, no k3d, nos Dev Services, no Dockerfile e
// nos scripts; sem digest ou com versões diferentes, o teste local valida uma coisa e o cluster roda outra.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const REF =
  /^((?:[a-z0-9.-]+(?::\d+)?\/)?[a-z0-9._-]+(?:\/[a-z0-9._-]+)*):([A-Za-z0-9._-]+)(?:@(sha256:[0-9a-f]{64}))?$/;
const SOURCES = ['infra/local/compose', 'infra/helm', 'infra/docker', 'apps', 'tools'];
const SKIP_DIRS = new Set(['node_modules', 'build', 'bin', 'results', '.nx', 'src/test']);

/** Imagens construídas neste repositório: não vêm de registro, então não têm digest a fixar. */
export const isOwnImage = (name) =>
  /(^|\/)marketplace(-nx)?\/(catalog-inventory|admin-web)$/.test(name);

export function parseRef(text) {
  const match = REF.exec(text.trim().replace(/^["']|["']$/g, ''));
  return match ? { name: match[1], tag: match[2], digest: match[3] ?? null } : null;
}

/** Referências de imagem de um arquivo, com a linha de cada uma. */
export function extractRefs(file, text) {
  const lines = text.split('\n');
  const refs = [];
  const add = (raw, index) => {
    const ref = parseRef(raw);
    if (ref && !isOwnImage(ref.name)) refs.push({ ...ref, file, line: index + 1 });
  };
  const anchors = new Map();
  lines.forEach((line) => {
    const anchor = /&([\w-]+)\s+(\S+)\s*$/.exec(line);
    if (anchor) anchors.set(anchor[1], anchor[2]);
  });
  lines.forEach((line, index) => {
    const image = /^\s*-?\s*image:\s*(\S+)\s*$/.exec(line);
    if (image)
      add(image[1].startsWith('*') ? (anchors.get(image[1].slice(1)) ?? '') : image[1], index);
    const anchor = /&[\w-]+\s+(\S+)\s*$/.exec(line);
    if (anchor && /image/i.test(line)) add(anchor[1], index);
    const from = /^FROM\s+(\S+)/.exec(line);
    if (from) add(from[1], index);
    const property = /image-name\s*=\s*(\S+)/.exec(line);
    if (property) add(property[1], index);
    const variable = /\b[A-Z0-9_]*IMAGE[A-Z0-9_]*=(?:\$\{[A-Z0-9_]+:-([^}\s]+)\}|([^(\s]\S*))/.exec(
      line,
    );
    if (variable) add(variable[1] ?? variable[2], index);
    const constant = /\b[A-Z0-9_]*IMAGE\s*=\s*'([^']+)'/.exec(line);
    if (constant) add(constant[1], index);
    const list = /\b[A-Z0-9_]*IMAGES=\(([^)]*)\)/.exec(line);
    if (list)
      list[1]
        .split(/\s+/)
        .filter(Boolean)
        .forEach((item) => add(item, index));
    const repository = /^(\s*)repository:\s*(\S+)\s*$/.exec(line);
    if (repository) {
      const block = {};
      for (let next = index + 1; next < lines.length; next++) {
        if (/^\s*#/.test(lines[next])) continue;
        const field = /^(\s*)(tag|digest):\s*"?([^"\s]*)"?\s*$/.exec(lines[next]);
        if (!field || field[1] !== repository[1]) {
          if (/^\s*(pullPolicy|registry):/.test(lines[next])) continue;
          break;
        }
        block[field[2]] = field[3];
      }
      if (block.tag)
        add(`${repository[2]}:${block.tag}${block.digest ? `@${block.digest}` : ''}`, index);
    }
  });
  return refs;
}

/** Problemas: imagem sem digest e o mesmo repositório com versões ou digests diferentes. */
export function findIssues(refs) {
  const issues = [];
  const byName = new Map();
  for (const ref of refs) {
    if (!ref.digest)
      issues.push(`${ref.file}:${ref.line}: ${ref.name}:${ref.tag} sem digest (@sha256:...)`);
    const versions = byName.get(ref.name) ?? new Map();
    const version = `${ref.tag}@${ref.digest ?? '-'}`;
    versions.set(version, [...(versions.get(version) ?? []), `${ref.file}:${ref.line}`]);
    byName.set(ref.name, versions);
  }
  for (const [name, versions] of byName) {
    if (versions.size < 2) continue;
    const detail = [...versions]
      .map(([version, places]) => `${version} em ${places.join(', ')}`)
      .join('; ');
    issues.push(`${name} com versões diferentes: ${detail}`);
  }
  return issues;
}

function* files(root, dir) {
  for (const entry of readdirSync(path.join(root, dir))) {
    const relative = path.join(dir, entry);
    if (SKIP_DIRS.has(entry) || SKIP_DIRS.has(relative.split(path.sep).slice(-2).join('/')))
      continue;
    if (statSync(path.join(root, relative)).isDirectory()) yield* files(root, relative);
    else if (/(\.ya?ml|\.sh|\.properties|Dockerfile[^/]*|(?<!\.test)\.mjs)$/.test(entry))
      yield relative;
  }
}

export function checkWorkspace(root) {
  const refs = SOURCES.flatMap((dir) => [...files(root, dir)]).flatMap((file) =>
    extractRefs(file, readFileSync(path.join(root, file), 'utf8')),
  );
  return { refs, issues: findIssues(refs) };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { refs, issues } = checkWorkspace(
    path.resolve(path.dirname(new URL(import.meta.url).pathname), '..'),
  );
  if (issues.length) {
    console.error(
      `Imagens: ${issues.length} problema(s)\n${issues.map((issue) => `  - ${issue}`).join('\n')}`,
    );
    process.exit(1);
  }
  console.log(
    `Imagens: ${refs.length} referências, todas com digest e uma versão por repositório.`,
  );
}
