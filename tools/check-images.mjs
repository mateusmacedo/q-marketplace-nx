// WHY: a mesma imagem precisa ser o mesmo artefato no compose, no chart, no k3d, nos Dev Services, no Dockerfile e
// nos scripts; sem digest ou com versões diferentes, o teste local valida uma coisa e o cluster roda outra.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const DIGEST_SUFFIX = /@(sha256:[0-9a-f]{64})$/;
const NAME_AND_TAG =
  /^((?:[a-z0-9.-]+(?::\d+)?\/)?[a-z0-9._-]+(?:\/[a-z0-9._-]+)*):([A-Za-z0-9._-]+)$/;
const SOURCES = ['infra/local/compose', 'infra/helm', 'infra/docker', 'apps', 'tools'];
const SKIP_DIRS = new Set(['node_modules', 'build', 'bin', 'results', '.nx', 'src/test']);

/** Imagens construídas neste repositório: não vêm de registro, então não têm digest a fixar. */
export const isOwnImage = (name) =>
  /(^|\/)marketplace(-nx)?\/(catalog-inventory|admin-web)$/.test(name);

export function parseRef(text) {
  const clean = text.trim().replace(/^["']|["']$/g, '');
  const digest = DIGEST_SUFFIX.exec(clean)?.[1] ?? null;
  const match = NAME_AND_TAG.exec(digest ? clean.slice(0, -(digest.length + 1)) : clean);
  return match ? { name: match[1], tag: match[2], digest } : null;
}

const IMAGE_ANCHOR = /&([\w-]+)\s+(\S+)\s*$/;
const REPOSITORY_FIELD = /^(\s*)repository:\s*(\S+)\s*$/;
const BLOCK_FIELD_KEY = /^(\s*)(tag|digest):/;
const BLOCK_FIELD_VALUE = /^"?([^"\s]*)"?$/;

// NOTE: a ordem da tabela é a das referências devolvidas quando uma linha traz mais de uma.
const LINE_PATTERNS = [
  {
    pattern: /^\s*(?:-\s*)?image:\s*(\S+)\s*$/,
    images: (match, { anchors }) => [
      match[1].startsWith('*') ? (anchors.get(match[1].slice(1)) ?? '') : match[1],
    ],
  },
  {
    pattern: /&[\w-]+\s+(\S+)\s*$/,
    images: (match, { line }) => (/image/i.test(line) ? [match[1]] : []),
  },
  { pattern: /^FROM\s+(\S+)/, images: (match) => [match[1]] },
  { pattern: /image-name\s*=\s*(\S+)/, images: (match) => [match[1]] },
  {
    pattern: /\b[A-Z0-9_]*IMAGE[A-Z0-9_]*=(?:\$\{[A-Z0-9_]+:-([^}\s]+)\}|([^(\s]\S*))/,
    images: (match) => [match[1] ?? match[2]],
  },
  { pattern: /\b[A-Z0-9_]*IMAGE\s*=\s*'([^']+)'/, images: (match) => [match[1]] },
  {
    pattern: /\b[A-Z0-9_]*IMAGES=\(([^)]*)\)/,
    images: (match) => match[1].split(/\s+/).filter(Boolean),
  },
];

function lineImages(line, anchors) {
  return LINE_PATTERNS.flatMap(({ pattern, images }) => {
    const match = pattern.exec(line);
    return match ? images(match, { anchors, line }) : [];
  });
}

/** Campos tag e digest do bloco `repository:` do values do chart, pulando comentários e opções de pull. */
function repositoryBlock(lines, from, indent) {
  const block = {};
  for (let next = from; next < lines.length; next++) {
    if (/^\s*#/.test(lines[next])) continue;
    const key = BLOCK_FIELD_KEY.exec(lines[next]);
    const value = key && BLOCK_FIELD_VALUE.exec(lines[next].slice(key[0].length).trim());
    if (!value || key[1] !== indent) {
      if (/^\s*(pullPolicy|registry):/.test(lines[next])) continue;
      break;
    }
    block[key[2]] = value[1];
  }
  return block;
}

function repositoryImage(lines, index) {
  const repository = REPOSITORY_FIELD.exec(lines[index]);
  if (!repository) return null;
  const block = repositoryBlock(lines, index + 1, repository[1]);
  if (!block.tag) return null;
  const digest = block.digest ? `@${block.digest}` : '';
  return `${repository[2]}:${block.tag}${digest}`;
}

/** Referências de imagem de um arquivo, com a linha de cada uma. */
export function extractRefs(file, text) {
  const lines = text.split('\n');
  const anchors = new Map();
  for (const line of lines) {
    const anchor = IMAGE_ANCHOR.exec(line);
    if (anchor) anchors.set(anchor[1], anchor[2]);
  }
  const refs = [];
  lines.forEach((line, index) => {
    const raws = lineImages(line, anchors);
    const repository = repositoryImage(lines, index);
    if (repository !== null) raws.push(repository);
    for (const raw of raws) {
      const ref = parseRef(raw);
      if (ref && !isOwnImage(ref.name)) refs.push({ ...ref, file, line: index + 1 });
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
    const details = issues.map((issue) => `  - ${issue}`).join('\n');
    console.error(`Imagens: ${issues.length} problema(s)\n${details}`);
    process.exit(1);
  }
  console.log(
    `Imagens: ${refs.length} referências, todas com digest e uma versão por repositório.`,
  );
}
