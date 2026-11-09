# shared-admin-ui

Componentes Angular comuns do painel (ADR 027 e ADR 029), importados por `@marketplace/shared-admin-ui`, todos standalone, com `OnPush`, signals e a marcação do template grátis do CoreUI: paginação por cursor (`app-cursor-pagination`), cabeçalho ordenável (`th[app-sort-header]`), barra de lote (`app-batch-bar`) e alerta do resultado (`app-batch-result-alert`), número em bloco (`app-number-block`), modal de confirmação (`app-confirm-modal`), alertas de problema e de conflito de edição (`app-problem-alert` e `app-edit-conflict-alert`), toasts (`ToastService` e `app-toast-host`), estados vazio, carregando e erro, pipes `dateTime`, `relativeTime` e `money`, badges do menu (`NavBadgeService`) e a sessão do usuário para as features (`AdminSession`, com as capacidades e as permissões compostas que o servidor devolve em `GET /me`; o painel não lê papéis).

Peças para as telas com lote, formulário e ações:

- **Aviso de saída:** a tela implementa `LeaveGuarded` (`leaveWarning`, com os textos de `leaveWarning({ unsaved, batchRunning })`), a rota declara `canDeactivate: [leaveGuard]` e o construtor chama `warnBeforeUnload`. O modal vem do `app-leave-confirm-host`, que fica no layout.
- **Seleção em lote:** `ListSelection` guarda a seleção entre páginas, limpa ao mudar o recorte da lista (`setPage(itens, parâmetros)`), dá o estado da caixa do cabeçalho (`pageChecked` e `pageIndeterminate`) e conta as ações pelo `actions` de cada item (`batchActions`, para o `app-batch-bar`). `batchFailures` monta o `app-batch-result-alert` com o desfecho do `runBatch`, e `abortOnDestroy` interrompe os grupos seguintes ao sair da tela.
- **Menu de ações:** `app-action-menu`, o dropdown "..." do template, com os itens de `serverActionMenu` (as ações de `actions` liberadas e as de `blockedActions` desabilitadas com o motivo no tooltip).
- **Formulários:** `app-form-offcanvas` (offcanvas à direita com o alerta do problema ou do conflito de edição, Cancelar e salvar), `applyServerErrors` e `serverFieldErrors` (os `errors` do 400 nos campos do formulário reativo ou de signals), `schemaValidator` e `validateSchemaRules` (os limites do contrato, de `fieldRules`) e `controlMessage` (a mensagem embaixo do campo).

Os testes rodam com `npx nx test shared-admin-ui` (Vitest com o `vitest-analog`).
