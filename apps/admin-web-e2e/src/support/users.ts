import path from 'node:path';

import { test, type FullProject } from '@playwright/test';

export const DEMO_USERS = ['admin-demo', 'seller-demo', 'moderator-demo'] as const;

export type DemoUser = (typeof DEMO_USERS)[number];

/** `storageState` do usuário gravado pelo projeto `setup`, em `dist/`, que o git ignora. */
export function storageStatePath(user: DemoUser, project: Pick<FullProject, 'outputDir'> = test.info().project): string {
  // WHY: ao lado do outputDir, não dentro, porque o Playwright o esvazia a cada execução e um `--no-deps` ficaria sem sessão.
  return path.join(project.outputDir, '..', '.auth', `${user}.json`);
}
