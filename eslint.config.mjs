import { readFileSync } from 'node:fs';
import nx from '@nx/eslint-plugin';
import prettier from 'eslint-config-prettier/flat';

// Fronteiras do front (ADR 027): cada dimensão de tag tem as próprias regras, e todas as que casam com o projeto
// de origem valem juntas.
const contexts = JSON.parse(
  readFileSync(new URL('./tools/contexts.json', import.meta.url), 'utf8'),
);
const NON_ANGULAR_BANNED = [
  '@angular/*',
  '@coreui/*',
  'angular-oauth2-oidc',
  'angular-oauth2-oidc/*',
];

const frameworkConstraints = [
  {
    sourceTag: 'framework:angular',
    onlyDependOnLibsWithTags: ['framework:angular', 'framework:none'],
  },
  {
    sourceTag: 'framework:react',
    onlyDependOnLibsWithTags: ['framework:react', 'framework:none'],
    bannedExternalImports: NON_ANGULAR_BANNED,
  },
  {
    sourceTag: 'framework:none',
    onlyDependOnLibsWithTags: ['framework:none'],
    bannedExternalImports: [
      ...NON_ANGULAR_BANNED,
      'react',
      'react/*',
      'react-dom',
      'react-dom/*',
      'next',
      'next/*',
    ],
  },
];

const layerConstraints = [
  {
    sourceTag: 'layer:web-app',
    onlyDependOnLibsWithTags: ['layer:feature', 'layer:ui', 'layer:data-access', 'layer:util'],
  },
  {
    sourceTag: 'layer:feature',
    onlyDependOnLibsWithTags: ['layer:ui', 'layer:data-access', 'layer:util'],
  },
  { sourceTag: 'layer:ui', onlyDependOnLibsWithTags: ['layer:ui', 'layer:util'] },
  { sourceTag: 'layer:data-access', onlyDependOnLibsWithTags: ['layer:data-access', 'layer:util'] },
  { sourceTag: 'layer:util', onlyDependOnLibsWithTags: ['layer:util'] },
];

// O cliente da API de outro contexto é permitido (integração por contrato, ADR 003); a feature e a ui dele, não.
// As libs comuns não dependem de contexto nenhum, nem do cliente da API.
const scopeConstraints = [
  ...contexts.map((context) => ({
    sourceTag: `scope:${context}`,
    onlyDependOnLibsWithTags: [`scope:${context}`, 'scope:shared', 'layer:data-access'],
  })),
  { sourceTag: 'scope:shared', onlyDependOnLibsWithTags: ['scope:shared'] },
];

export default [
  ...nx.configs['flat/base'],
  ...nx.configs['flat/typescript'],
  ...nx.configs['flat/javascript'],
  {
    ignores: [
      '**/dist',
      '**/out-tsc',
      '**/build',
      '**/test-output',
      '**/coverage',
      '**/playwright-report',
      '**/src/generated',
      '**/vite.config.*.timestamp*',
      '**/vitest.config.*.timestamp*',
    ],
  },
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {
      '@nx/enforce-module-boundaries': [
        'error',
        {
          enforceBuildableLibDependency: true,
          allow: ['^.*/eslint(\\.base)?\\.config\\.[cm]?[jt]s$'],
          depConstraints: [...frameworkConstraints, ...layerConstraints, ...scopeConstraints],
        },
      ],
    },
  },
  {
    files: [
      '**/*.ts',
      '**/*.tsx',
      '**/*.cts',
      '**/*.mts',
      '**/*.js',
      '**/*.jsx',
      '**/*.cjs',
      '**/*.mjs',
    ],
    // Override or add rules here
    rules: {},
  },
  // Por último: desliga as regras de estilo que o Prettier decide (ADR 033); as configs dos projetos espalham esta
  // depois das do Angular, então vale também para elas.
  prettier,
];
