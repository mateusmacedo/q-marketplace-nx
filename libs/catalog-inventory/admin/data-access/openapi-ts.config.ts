import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: '../../../../contracts/catalog-inventory/openapi.json',
  output: 'src/generated',
  plugins: [
    { name: '@hey-api/client-angular', runtimeConfigPath: './src/client-config' },
    '@hey-api/typescript',
    '@hey-api/sdk',
    { name: '@angular/common', httpRequests: true, httpResources: true },
    '@hey-api/schemas',
  ],
});
