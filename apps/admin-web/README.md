# admin-web

Painel de operação do marketplace (ADRs 027, 028 e 029 em `docs/decisoes.md`). As telas do catálogo seguem `docs/proposta-design-admin-web.md`.

O shell do painel vem do [CoreUI Free Angular Admin Template](https://github.com/coreui/coreui-free-angular-admin-template), commit `a514b2e` (v5.7.42, Angular 22.2.5): `app.component`, o layout em `src/app/layout`, o tema em `src/scss` e os ícones `logo` e `signet`. As telas de exemplo do template ficaram de fora; cada componente entra quando uma tela precisar dele. A licença MIT do template está em `LICENSE-coreui`.
