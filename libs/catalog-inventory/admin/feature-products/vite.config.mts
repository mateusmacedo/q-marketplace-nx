/// <reference types='vitest' />
import { defineConfig } from 'vite';
import angular from '@analogjs/vite-plugin-angular';

export default defineConfig(() => ({
  root: import.meta.dirname,
  cacheDir: '../../../../node_modules/.vite/libs/catalog-inventory/admin/feature-products',
  resolve: {
    tsconfigPaths: true,
  },
  plugins: [angular()],
  test: {
    name: 'catalog-inventory-admin-feature-products',
    watch: false,
    // WHY: o padrão é um worker por núcleo menos um, e cada um carrega Angular e jsdom; com o resto do ambiente, esgota
    // a memória do host (README, "Memória do host"). VITEST_MAX_WORKERS sobrepõe este teto.
    maxWorkers: 2,
    globals: true,
    environment: 'jsdom',
    include: ['{src,tests}/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
    setupFiles: ['src/test-setup.ts'],
    reporters: ['default'],
    coverage: {
      reportsDirectory: '../../../../coverage/libs/catalog-inventory/admin/feature-products',
      provider: 'v8' as const,
    }
  },
}));
