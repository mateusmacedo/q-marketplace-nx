# catalog-inventory-admin-data-access

Cliente da API do catálogo para o painel (ADR 027), gerado pelo Hey API a partir de `contracts/catalog-inventory/openapi.json`, com as funções de requisição e as versões `httpResource` de cada operação. O código fica em `src/generated` e é refeito com `npx nx run catalog-inventory-admin-data-access:codegen`. O app liga o cliente ao próprio `HttpClient` com `provideCatalogInventoryApi()`, e a `baseUrl` é `/api/catalog-inventory`.
