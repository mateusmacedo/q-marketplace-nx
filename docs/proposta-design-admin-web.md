# Proposta de design do painel: catálogo-inventário

Status: aprovada em 08/11/2026. As decisões estão registradas na ADR 029 em [`decisoes.md`](decisoes.md), que também revisou a ADR 028, e foram revistas no mesmo dia pela ADR 031, que põe no servidor as regras e os catálogos que o painel repetia.

Esta proposta define a navegação, as telas e os padrões de interação do painel (`apps/admin-web`) para os recursos do catálogo-inventário. Ela parte das ADRs 027 e 028 (Angular 22, CoreUI grátis, paginação por cursor, datas no fuso `America/Sao_Paulo`), das escolhas do usuário numa entrevista sobre navegação, interface e usabilidade e da revisão dessas escolhas pelo que o template grátis do CoreUI entrega (ver [Revisão pelo template grátis](#revisão-pelo-template-grátis)).

A regra é usar os componentes, a disposição e as transições do template grátis, com a mesma marcação da demo, ajustando só o conteúdo ao contexto. Um componente próprio só entra quando a versão grátis não entrega e há justificativa de usabilidade, e mesmo assim a primeira opção é montar com o que o template tem. Os endpoints que as telas usam estão em [Endpoints das telas](#endpoints-das-telas).

O painel não repete regra nem catálogo do servidor (ADR 031). As ações permitidas, as pendências de envio, as mensagens de recusa, as capacidades do usuário e os motivos de moderação vêm da API, e o painel guarda só a apresentação: rótulo, ícone, cor, ordem e confirmação. Onde um valor do servidor ainda se repete no painel, esta proposta nomeia a saída e marca a pendência, e a lista completa está em [Valores fixos com saída pendente](#valores-fixos-com-saída-pendente).

## Decisões

| Tema | Decisão | Custo aceito |
|---|---|---|
| Menu lateral | Grupos por área num menu só: **Minha loja**, **Moderação** e **Plataforma**. Cada item aparece se a sessão, lida de `GET /me`, tiver a capacidade que ele exige | Uma chamada a mais no início da sessão, com estado de erro e nova tentativa se ela falhar; quem acumula capacidades vê um menu mais longo |
| Página inicial | Formato do dashboard do template: 4 indicadores com minigráfico, gráfico principal com rodapé de números e tabela dos produtos atualizados, com os números de `/stats/*` | Os cards de cada papel são campos fixos do contrato até a lista de indicadores (pendente, ver [Valores fixos](#valores-fixos-com-saída-pendente)) |
| Telas | Pensado para desktop e usável no celular | Editar produto com muitos SKUs no celular dá mais trabalho |
| Filtros | Abas por status, busca por texto (`q`) e filtros na URL | A busca por texto pede o plano customizado no banco (ADR 006) |
| Paginação | `c-pagination` do template só com Anterior e Próxima, e 20 ou 50 itens por página ou o máximo do contrato | Sem salto para uma página nem total; o máximo (100) é copiado no painel até ser lido do schema gerado (pendente) |
| Ações na lista | O título do item é o link que abre o registro, e o dropdown do template (o mesmo dos indicadores) traz as ações que a API devolve em `actions`. A tela individual repete o mesmo menu e mostra a ação principal como botão | Um clique a mais por ação, e a ação principal aparece em dois lugares na tela individual |
| Ordenação | Pelo backend, clicando no cabeçalho de Produto, Criado e Atualizado, com `sort` na URL e cursor composto. Padrão: Atualizado, mais recente primeiro. A fila de moderação vem em ordem de chegada. É a única parte própria das listas, escolhida pelo usuário, porque só a Smart Table da PRO ordena | Mudança de contrato e de cursor, com índices novos, e um cabeçalho ordenável escrito no projeto |
| Seleção em lote | Caixas de seleção em Produtos da loja, Fila de moderação e Categorias, com a barra de botões do template (`btn-toolbar`) e o resultado de cada item. Cada grupo de até 100 selecionados vai numa chamada do endpoint em lote | Fechar a aba interrompe os grupos ainda não enviados; o resultado do lote não traz `actions`, e a lista é recarregada no fim; o tamanho do grupo é o `maxItems` do pedido, lido do schema gerado |
| Tela do produto | Abas (Conteúdo, SKUs, preço e estoque, Histórico) e checklist de envio ao lado, com as pendências calculadas pelo servidor (`publishingIssues` e `POST /products/{id}/check`) | O checklist do formulário não salvo depende de uma chamada à API, com debounce e estado de carregamento |
| Troca de categoria | Botão **Trocar** ao lado da categoria, com prévia do efeito calculada pelo servidor (`POST /products/{id}/check`): atributos que somem, obrigatórios novos e SKUs incompatíveis, que impedem a troca | A prévia espera a resposta da API ao escolher o destino |
| Preço e estoque | Painel lateral (offcanvas) por SKU, com mudanças em massa pela planilha | Um SKU por vez na tela |
| Formulários secundários | Modal para 1 ou 2 campos e confirmações, offcanvas para formulários médios | Dois padrões para seguir |
| Moderação | Fila e detalhe lado a lado, com avanço automático | Pede tela larga, e no celular vira lista e detalhe |
| Motivo de moderação | Motivos prontos do catálogo do tenant, lidos de `GET /moderation/reasons` e administrados em **Plataforma > Motivos de moderação**, com complemento livre (ADR 030) | Uma tela a mais em Plataforma para manter o catálogo; o tenant novo começa sem motivos |
| Categorias | Lista por nível (`list-group`) com breadcrumb e card de detalhe. Escolher categoria em modais usa um `form-select` com o caminho completo | Não mostra a hierarquia inteira de uma vez |
| Exclusão de categoria | Só da categoria vazia: sem subcategorias e sem nenhum produto, nem arquivado. A categoria já usada só é desativada | Excluir não tem volta |
| Importação | Envio, andamento, resultado por linha e histórico de lotes | O modelo CSV e o intervalo do andamento seguem fixos no painel, e o tamanho do arquivo só é conferido pelo servidor, até a saída de cada um (pendente) |
| Avisos | Toast do template (marcador de cor, título, hora e barra de tempo) para sucesso, e erro no lugar onde aconteceu, com o texto que o servidor manda | Menos controle sobre o tom de cada mensagem, compensado por sobrescritas pontuais no painel |
| Conflito de edição | Aviso com "Carregar versão atual", sem perder o que foi digitado | A pessoa refaz as alterações por cima da versão nova |
| Cabeçalho | Igual ao da demo: botão de busca (telas, produtos e categorias, também com Ctrl+K), ícones de notificações, tarefas e mensagens, seletor de tema e menu do usuário. Revisa a ADR 028, que reduzia o cabeçalho | Os três ícones ficam sem função até existir um contexto de notificações |
| Tema claro e escuro | Seletor Claro, Escuro e Automático do template, guardado no navegador, com as cores do `_theme.scss` | Nenhum |

## Revisão pelo template grátis

As escolhas da entrevista foram revistas contra o que a demo grátis do CoreUI entrega (<https://coreui.io/demos/angular/latest/free/#/dashboard>, mesma versão do pacote do projeto). O que o template entrega passou a ser feito com ele. Nos três pontos em que ele não entrega nada parecido, o usuário decidiu.

| Escolha anterior | Template grátis | Resultado |
|---|---|---|
| Página inicial com cards por papel | Entrega o dashboard: indicadores com minigráfico, gráfico principal com rodapé e tabela | Segue o dashboard |
| Anterior e Próxima em botões | Entrega `c-pagination` | Paginação do template |
| Clicar na linha abre o produto | A tabela do template não tem linha clicável | O título vira link |
| Toast colorido | Entrega toast com cabeçalho e barra de tempo | Toast do template |
| Barra de lote própria | Entrega `btn-toolbar` e `btn-group` | Barra do template |
| Linha do tempo no histórico | Não tem | `list-group` |
| Badge suave, marca própria e painel flutuante de controles | Não tem | Badge sólido, logo do painel e controles do protótipo no menu do usuário |
| Árvore de categorias (CDK) | Não existe nem na PRO | Lista por nível com breadcrumb e `form-select` de caminhos, por decisão do usuário |
| Ordenar clicando no cabeçalho | Só a Smart Table, que é PRO | Mantido como parte própria, por decisão do usuário |
| Cabeçalho reduzido (ADR 028) | Traz busca e ícones de notificação | Igual à demo, por decisão do usuário. A ADR 029 atualizou a ADR 028 |

Continuam como estavam, porque o template entrega: menu agrupado por área, abas, busca com `input-group`, dropdown de ações, abas da tela do produto, offcanvas, modal, fila e detalhe lado a lado (grade, `list-group` e card), motivos de moderação com `form-check` e importação com progresso e tabela.

## Quem usa e o que acessa

O painel não conhece papéis nem lê claims do token. Ele carrega a sessão de `GET /me` (`tenantId`, `storeId`, `capabilities` e as permissões compostas em `can`) e decide o que mostra pelas capacidades. O papel que concede cada capacidade é regra do servidor, só em `CallerContext`, e os endpoints exigem a capacidade, não o papel (ADR 007).

| Quem | Capacidades (`GET /me`) | Grupo do menu | O que faz | Recursos da API |
|---|---|---|---|---|
| Lojista | `MANAGE_OWN_PRODUCTS`, com `storeId` | Minha loja | Cadastra produtos e SKUs, define preço, promoções e estoque, importa planilhas e acompanha a situação da loja | `Products`, `Offers`, `Inventory`, `GET /stores/{storeId}/eligibility` |
| Moderador | `MODERATE_PRODUCTS`, `READ_SELLER_ELIGIBILITY` e `can.readTenantCatalog` | Moderação, e Produtos de todas as lojas em Plataforma | Aprova, rejeita, bloqueia e desbloqueia produtos | `Moderation`, leitura de `Products`, `Offers` e `Inventory` e elegibilidade de qualquer loja |
| Administrador do catálogo | `MANAGE_TAXONOMY`, `MANAGE_MODERATION_REASONS`, `READ_TENANT_CATALOG`, `READ_SELLER_ELIGIBILITY` e `can.readTenantCatalog` | Plataforma | Mantém a árvore de categorias e o catálogo de motivos de moderação do tenant e consulta produtos de qualquer loja | `Categories`, `/moderation/reasons` e leitura de `Products`, `Offers`, `Inventory` e elegibilidade |
| Checkout | `MANAGE_RESERVATIONS` | nenhum | Conta de serviço | `Inventory` (reservas), sem tela |

## Navegação

### Menu

```text
Início

MINHA LOJA
  Produtos                       MANAGE_OWN_PRODUCTS
  Importações                    MANAGE_OWN_PRODUCTS
  Situação da loja               MANAGE_OWN_PRODUCTS

MODERAÇÃO
  Fila de produtos               MODERATE_PRODUCTS

PLATAFORMA
  Categorias                     MANAGE_TAXONOMY
  Motivos de moderação           MANAGE_MODERATION_REASONS
  Produtos de todas as lojas     can.readTenantCatalog
```

- Cada item do `_nav.ts` declara a capacidade exigida (ou a permissão composta de `can`), e o layout monta o menu a partir da sessão carregada de `GET /me`. Os títulos de grupo usam o `title: true` do `INavData` e somem quando o grupo fica vazio. Os grupos são só organização visual.
- Se `GET /me` falhar, o painel mostra o erro com **Tentar de novo**, em vez de um menu vazio. No 401, ele renova o token uma vez e repete a chamada; se o servidor ainda recusar (sessão encerrada no provedor), mostra "Sua sessão expirou" com **Entrar de novo**, que leva ao login e volta para a rota atual. O painel não vai ao login sozinho, para não entrar em ciclo se o servidor recusar todo token.
- Os outros contextos entram nos mesmos grupos: pedidos e repasses em **Minha loja**, a análise de KYB/KYC em **Moderação**.
- O cabeçalho continua como na ADR 028: botão do menu, seletor de tema, menu do usuário e breadcrumb, que lê o `data.title` das rotas.

### Rotas

| Rota | Tela | Capacidade | Lib |
|---|---|---|---|
| `/inicio` | Início | qualquer sessão | app |
| `/loja/produtos` | Produtos da loja | `MANAGE_OWN_PRODUCTS` | `feature-products` |
| `/loja/produtos/:id/conteudo`, `/skus`, `/historico` | Produto, uma rota por aba | `MANAGE_OWN_PRODUCTS` | `feature-products` |
| `/loja/importacoes` | Importações | `MANAGE_OWN_PRODUCTS` | `feature-imports` |
| `/loja/importacoes/:id` | Resultado de um lote | `MANAGE_OWN_PRODUCTS` | `feature-imports` |
| `/loja/situacao` | Situação da loja | `MANAGE_OWN_PRODUCTS` | `feature-store` |
| `/moderacao/fila`, `/moderacao/fila/:id` | Fila de moderação | `MODERATE_PRODUCTS` | `feature-moderation` |
| `/plataforma/categorias`, `/plataforma/categorias/:id` | Categorias | `MANAGE_TAXONOMY` | `feature-categories` |
| `/plataforma/motivos-de-moderacao` | Motivos de moderação | `MANAGE_MODERATION_REASONS` | `feature-moderation` |
| `/plataforma/produtos`, `/plataforma/produtos/:id` | Produtos de todas as lojas | `can.readTenantCatalog` | `feature-products` |

- Um `capabilityGuard` (ou um `permissionGuard`, para as permissões compostas de `can`) por rota ou grupo complementa o `authGuard` atual e lê a sessão (`SessionStore`), carregada de `GET /me` por um `provideAppInitializer` depois do retorno do login. Sem a capacidade, a rota volta para `/inicio` com o aviso "Você não tem acesso a esta área". O guard e o menu nunca comparam nome de papel nem status.
- Filtros, aba e cursor ficam na URL (`?status=DRAFT&q=camiseta&limit=50&cursor=...`). O link pode ser salvo e o botão voltar do navegador refaz a lista.

## Visual

A referência é a demo grátis do CoreUI Angular, feita com o mesmo template de onde saiu o shell do painel (ADR 028). As telas usam a marcação dos componentes da demo e trocam só o conteúdo:

- **Base e tema:** fonte `system-ui` de 16px, fundo `--cui-tertiary-bg` no claro e `--cui-dark-bg-subtle` no escuro, como o `_theme.scss` do `apps/admin-web`. O seletor Claro, Escuro e Automático troca o `data-coreui-theme` do documento, como o `ColorModeService`, e guarda a escolha no navegador. No escuro, cabeçalho, cards e rodapé usam `--cui-body-bg` (#212631).
- **Shell:** menu lateral `sidebar-dark` de 256px, com títulos de grupo, badge `badge-sm bg-info` na Fila e o rodapé que recolhe o menu para 64px. O cabeçalho é o da demo: botão do menu, botão de busca (`c-search-button`), ícones de notificações, tarefas e mensagens, seletor de tema, menu do usuário e a linha do breadcrumb. A sombra aparece só ao rolar.
- **Cards:** o título fica dentro do card, como o "Traffic" do dashboard: `h4.card-title` com subtítulo `small text-body-secondary` à esquerda e as ações à direita (`float-end`). O `card-header`, quando existe, leva só o texto.
- **Tabelas:** `table mb-0 border align-middle table-hover table-striped`, `thead` com `text-nowrap text-truncate` e cabeçalhos em `bg-body-tertiary`. Cada célula segue a tabela de usuários do dashboard: o texto principal e uma linha de apoio em `small text-body-secondary` separada por "|", números em `strong` com `progress-thin` embaixo e datas no par rótulo pequeno e valor em negrito.
- **Indicadores:** `c-widget-stat-a` com `bg-primary`, `bg-info`, `bg-warning` e `bg-danger`, valor `fs-4 fw-semibold` com a variação entre parênteses, dropdown de opções e o minigráfico do template (linha, linha reta, área e barras, com as mesmas opções do Chart.js). O gráfico vem do `@coreui/angular-chartjs`, que a ADR 028 já previa para a primeira tela com gráfico.
- **Números em bloco:** `border-start border-start-4 border-start-{cor} py-1 px-3` com rótulo pequeno e valor `fs-5 fw-semibold`, como o "Traffic & Sales" do dashboard, usados na importação, na fila e no painel do SKU.
- **Formulários, alertas, badges, modais, offcanvas e toasts:** os do template, sem ajuste. Modal com Cancelar em `btn-secondary`. Toast no canto superior direito, com marcador de cor, título, hora, botão de fechar e barra de tempo.
- **Fora, por serem PRO ou add-on pago:** Smart Table, Smart Pagination, Multi Select, Autocomplete, Date Picker, Date Range Picker, Time Picker, Calendar, Stepper, Loading Button, Password Input, Range Slider, Data Grid e Scheduler. As alternativas são os componentes grátis do template (tabela, paginação, `form-select`, campos nativos de data, botão com `spinner`). O gradiente dos indicadores da demo PRO também fica de fora: a demo grátis usa cor sólida.

## Padrões de interação

### Listagens

- Tabela do template, com o título do item como link e o dropdown de ações na última coluna. A marcação segue a seção [Visual](#visual).
- Paginação com o `c-pagination` do template, só com Anterior e Próxima: o componente guarda a pilha de cursores visitados para voltar, e o seletor de itens por página oferece 20 e 50, preferência de interface, e o máximo que a API aceita. O máximo (100) é hoje uma cópia de `Page.MAX_SIZE` em `PAGE_SIZES` (`libs/shared/web-util`); a saída é ler o `maximum` do parâmetro `limit` no schema gerado, pendente (ver [Valores fixos](#valores-fixos-com-saída-pendente)).
- O menu "..." mostra as ações de `actions`, que o servidor calcula pelo status, pelas regras de edição, pelas capacidades do usuário e pela situação da loja (tabela em [Produto](#4-produto)). As de `blockedActions` aparecem desabilitadas, com o motivo (`reason.message`) no tooltip; as demais somem.
- **Lista e tela individual com as mesmas ações:** toda ação do menu "..." da lista também está na tela individual, num menu "..." com os mesmos nomes e a mesma ordem. Os dois menus vêm de `actions` e `blockedActions` da API; a feature guarda só a apresentação de cada código (rótulo, ícone, ordem, confirmação, cor e toast) e ignora o código que não conhece. A ação principal do status (Enviar para moderação, Pausar, Reativar) também aparece como botão ao lado do menu. Só **Editar** fica de fora, porque a tela individual já é a edição. A tela individual pode ter ações a mais, como o **Desbloquear** do moderador, mas nunca a menos.
- Estado vazio com texto e ação ("Nenhum produto em rascunho. Criar produto"), carregamento com o `Placeholder` do CoreUI e erro de rede com "Tentar de novo".

- **Ordenação:** os cabeçalhos ordenáveis são botões, com a seta da ordem atual e o `aria-sort` na coluna. Clicar na coluna ativa inverte a ordem, e clicar em outra coluna usa a ordem padrão dela (texto em ordem crescente, datas da mais recente para a mais antiga). A ordem fica na URL (`sort=-updatedAt`), e trocar a ordem recomeça do primeiro cursor. A API ordena por `title`, `createdAt` ou `updatedAt`, com o prefixo `-` para inverter, e o cursor composto pelo valor da coluna e o id; o padrão é `-updatedAt`. Ordenar só a página carregada fica de fora, porque a próxima página viria em outra ordem. É a única parte própria das listas: o template grátis não ordena, e a Smart Table, que ordena, é PRO. O usuário escolheu o cabeçalho ordenável, que é o padrão mais conhecido em tabelas e mostra a ordem na própria coluna.

### Seleção e operações em lote

```text
[x] 3 selecionados   [Enviar (1)] [Pausar (2)] [Reativar (0)] [Arquivar (3)]   Limpar seleção
[x] Camiseta Basic   [Ativo]
[x] Mochila 20 L     [Sem estoque]
[x] Tênis Run        [Rascunho]
```

- **Onde:** Produtos da loja (enviar para moderação, pausar, reativar, arquivar), Fila de moderação (aprovar, rejeitar, bloquear) e Categorias (ativar, desativar, excluir). Em Categorias, as caixas aparecem na lista do nível depois de clicar em **Selecionar**, para não pesar a navegação do dia a dia.
- **Seleção:** a caixa do cabeçalho marca ou desmarca a página inteira e fica parcial quando só parte da página está marcada. A seleção continua ao trocar de página e é limpa ao mudar filtro, busca, ordem ou itens por página. Não existe "selecionar todos os resultados", porque a API não informa o total.
- **Barra de ações:** a `btn-toolbar` do template, com um `btn-group` de botões, aparece acima da tabela quando há seleção. Cada botão mostra quantos selecionados aceitam a ação ("Pausar (2)") e fica desabilitado em 0: em Produtos e na Fila, os que trazem a ação em `actions`; em Categorias, que não têm `actions`, as inativas para Ativar, as ativas para Desativar e, para Excluir, as que o servidor marcar como excluíveis no campo `deletable`, pendente (ver [Valores fixos](#valores-fixos-com-saída-pendente)); a recusa `category-has-products` continua decidida pelo servidor. Os selecionados em que a ação não vale não são enviados e entram no resultado como ignorados.
- **Confirmação:** arquivar, desativar e excluir pedem modal, como na ação de um item só. Aprovar em lote também pede, porque publica sem abrir cada produto. Rejeitar e bloquear em lote abrem o modal de motivo uma vez, e o mesmo motivo vale para todos.
- **Execução:** cada grupo de até 100 selecionados vai numa chamada do endpoint em lote da tela: `POST /products/batch` (enviar, pausar, reativar e arquivar), `POST /moderation/products/batch` (aprovar, rejeitar e bloquear, com um motivo só para todos) e `POST /categories/batch` (ativar, desativar e excluir). O servidor aplica a ação a cada item isolado, com as mesmas regras e eventos das ações de um item, e devolve o resultado de cada um na ordem do pedido. Durante a chamada, o botão mostra o spinner e a barra fica travada. A exclusão de categorias roda no servidor, das subcategorias para as categorias pai, para que um ramo vazio inteiro saia de uma vez. O tamanho do grupo (100) é hoje uma cópia de `Batch.MAX_ITEMS` em `BATCH_LIMIT` (`libs/shared/web-util`); a saída é ler o `maxItems` do pedido no schema gerado, pendente.
- **Resultado:** um toast resume ("6 pausados, 2 com falha, 1 ignorado"). As falhas aparecem num alerta acima da lista, com o nome de cada item e a mensagem do servidor no `problem` do item, e esses itens continuam selecionados para uma nova tentativa. Os que deram certo saem da seleção. O resultado traz o status e a versão novos, mas não `actions`, então a lista é recarregada no fim para refazer os menus. Se o saldo não puder ser lido depois das gravações, o status de cada item sai como o gravado, sem o `OUT_OF_STOCK` calculado, e a recarga corrige.
- **Saída no meio:** sair da tela ou fechar a aba durante o lote interrompe os grupos ainda não enviados; o grupo já enviado é processado inteiro pelo servidor. O painel avisa antes de sair (guard `canDeactivate` e `beforeunload`).

### Formulários

| Tipo | Componente | Exemplos |
|---|---|---|
| 1 ou 2 campos e confirmações | Modal | Novo produto, motivo da rejeição, confirmar arquivamento, mover categoria |
| Formulário médio que pede ver a lista atrás | Offcanvas à direita | Painel do SKU, novo SKU, agendar promoção, nova categoria, novo motivo de moderação |
| Formulário principal | Página | Conteúdo do produto |

- Para avisar antes do envio, os formulários precisam dos limites do contrato (`pattern`, `maxLength` e obrigatórios, como o código de 60 caracteres, o nome de 120, o título de 200, a descrição de 5000, até 100 opções e o padrão do SKU). Os formulários leem esses limites do schema gerado pelo plugin `@hey-api/schemas` no codegen de `admin/data-access`, com `fieldRules` de `libs/shared/web-util`, e as features passam os limites aos componentes de `admin/ui`; nenhum formulário copia um limite. O 400 e o 422 continuam sendo a palavra final: os `errors` (`field`, `message`) vão para os campos e as mensagens de `reasonDetails` para o alerta do formulário.
- Os atributos do produto e do SKU são montados pelo tipo do atributo efetivo da categoria: `TEXT` vira campo de texto, `INTEGER` e `DECIMAL` viram campos numéricos, `BOOLEAN` vira switch e `OPTION` vira select. O atributo `variant` vai para o SKU.
- O botão de salvar mostra spinner e fica desabilitado durante o envio, para não repetir a chamada.

### Avisos e erros

- **Sucesso:** toast do template no canto superior direito, com marcador de cor, título, hora, botão de fechar e a barra fina que marca os 5 segundos até sumir ("Produto enviado para moderação").
- **Erro:** fica onde aconteceu. Erro de campo aparece embaixo do campo, recusa de regra aparece num alerta no topo do formulário e, na tela do produto, também no checklist.
- **Texto do servidor:** o servidor é o dono do texto (ADR 008). Toda recusa de regra traz `reasonDetails`, com um item por motivo de `reasons`, na mesma ordem, cada um com `code`, `params` e `message` em português; o emissor escreve a mensagem quando sabe mais que os parâmetros, como o rótulo do atributo em `missing-variant-attribute` ou o limite vigente em `category-too-deep`. O alerta mostra, para cada motivo, o primeiro texto que existir: a sobrescrita do painel, a `message` do servidor, o `detail` e o `title` do problema e, no fim, a mensagem genérica "Não foi possível concluir a operação". O código cru nunca aparece.
- **Código para a lógica:** o `code` destaca o campo e escolhe a aba, e o painel ignora com segurança o código que não conhece. O painel interpreta sozinho só os códigos técnicos de protocolo, que escolhem o componente: `version-mismatch` (412) e `concurrent-update` (409) abrem o [aviso de conflito](#conflito-de-edição), e `network-error`, criado pelo próprio painel quando o servidor não responde, mostra o erro de rede com **Tentar de novo**.
- **Sobrescritas:** `libs/shared/web-util` guarda um mapa pequeno de sobrescritas opcionais, só para os problemas que o painel cria sem resposta do servidor (`network-error` e `http-<status>`). Uma sobrescrita nunca leva número de regra: limites e valores vêm em `params` ou na mensagem do servidor.
- **Problemas sem `reasonDetails`:** `not-found`, `duplicate-*`, `version-mismatch`, `precondition-required` e os demais fora da família das recusas de regra trazem só `detail` e `title`, que o alerta usa nessa ordem.

### Conflito de edição

Cada chamada de gravação envia o `If-Match` com a versão lida, porque o cliente gerado recebe o cabeçalho por chamada, sem interceptor. A versão vem da `version` do corpo, que o contrato declara igual ao ETag forte; o cabeçalho `ETag` só vale quando o corpo não a traz, e nunca fraco, porque um proxy que comprime a resposta entrega `W/"3"` e a API recusa `If-Match` fraco. Quando a gravação volta com 412 `version-mismatch` ou 409 `concurrent-update`, o formulário mostra o alerta "Outra pessoa alterou este registro depois que você abriu a tela" com o botão **Carregar versão atual**. O que a pessoa digitou continua no formulário até ela clicar, para que possa copiar o que precisa.

### Confirmações

| Ação | Confirmação |
|---|---|
| Arquivar produto, remover SKU, bloquear produto, excluir categoria, desativar motivo de moderação | Modal com o nome do item e o efeito ("O produto sai da vitrine na hora") |
| Salvar conteúdo ou SKU de produto publicado (`saveReturnsToModeration`) | Modal: "Salvar devolve o produto à moderação, e ele sai da vitrine até ser aprovado" |
| Pausar, reativar, enviar para moderação | Sem modal. O toast confirma, e a ação contrária desfaz |

### Telas pequenas

- Abaixo de `lg`, o menu lateral vira gaveta, como no template.
- Em telas estreitas, as tabelas rolam na horizontal dentro do card (`table-responsive`), e a coluna Criado só aparece a partir de `xl`. Abaixo de `lg`, a fila mostra a lista e o detalhe em telas separadas, e as categorias empilham a lista e o detalhe. O offcanvas ocupa quase toda a largura.
- Os formulários ficam em uma coluna.

### Datas, moeda e números

- Datas e horas no fuso `America/Sao_Paulo` (ADR 028), no formato `08/11/2026 14:02`. O fuso é hoje uma constante em `libs/shared/web-util` (`dates.ts`) que precisa concordar com a do `CatalogStatsService`; a saída é a chave `catalog.time-zone` do serviço, entregue ao painel por `GET /settings` (`timeZone`), pendente. As séries da página inicial já trazem o fuso dos baldes em `timeZone`.
- Moeda pelo `Intl.NumberFormat('pt-BR', { style: 'currency', currency })`, com a moeda do produto.
- Colunas numéricas alinhadas à direita, com algarismos de largura fixa.

### Acessibilidade

- Status sempre com texto no badge, nunca só pela cor.
- Modal e offcanvas devolvem o foco ao botão que os abriu.
- Abas, dropdowns, modais, offcanvas e `list-group` usam a navegação por teclado dos componentes do template. O cabeçalho ordenável é um botão com `aria-sort` na coluna.

## Telas

### 1. Início

```text
+-[primary]--------+ +-[info]-----------+ +-[warning]--------+ +-[danger]---------+
| 14           [⋮] | | 5 (-1 ↓)     [⋮] | | 1            [⋮] | | 1 (-1 ↓)     [⋮] |
| Produtos ativos  | | Em rascunho      | | Sem estoque      | | Bloqueados       |
|  ~~linha~~       | |  ~~linha~~       | | ~~~área~~~       | | ▌▌ barras ▌▌     |
+------------------+ +------------------+ +------------------+ +------------------+
+--------------------------------------------------------------------------------+
| Catálogo                                          [Dia|Mês|Ano]  [baixar]      |
| Janeiro a dezembro de 2026                                                     |
|  (aprovados em área, enviados à moderação em linha, média tracejada)           |
|--------------------------------------------------------------------------------|
|  Ativos       Em rascunho    Em moderação    Bloqueados     Pausados           |
|  14 (54%)     5 (19%)        1 (4%)          1 (4%)         4 (15%)            |
+--------------------------------------------------------------------------------+
| [M] Produto              Status   Estoque              Preço     Atividade     |
|     Categoria | 3 SKUs   [Ativo]  12 disponíveis de 15  R$ 49,90  Atualizado   |
|                                   ======-----                     há 1 hora    |
+--------------------------------------------------------------------------------+
```

- **Indicadores:** os 4 `c-widget-stat-a` do dashboard, escolhidos pela capacidade da sessão. Com `MANAGE_OWN_PRODUCTS` (`GET /stats/store`): produtos ativos, em rascunho, sem estoque e bloqueados, com o minigráfico dos últimos meses e o dropdown que leva à lista filtrada. Em rascunho e Bloqueados mostram a variação em relação ao fim do mês anterior, quando há histórico. **Produtos ativos** não tem variação (`previous` e `change` nulos), porque o saldo não guarda passado: o valor é o `ACTIVE` com saldo, como na aba Ativos, e o minigráfico mostra o `ACTIVE` gravado, com ou sem saldo, no fim de cada mês e agora no corrente. **Sem estoque** também não tem variação, e o minigráfico só tem o mês corrente. Sem `MANAGE_OWN_PRODUCTS`, quem tem `MODERATE_PRODUCTS` vê a fila e os aprovados, rejeitados e bloqueados do mês (`GET /stats/moderation`), e quem tem `MANAGE_TAXONOMY` vê categorias, raízes, inativas e categorias com produtos (`GET /stats/categories`). Os cards de cada conjunto são campos fixos do contrato; a saída, uma lista `indicators` descrita pelo servidor, está pendente (ver [Valores fixos](#valores-fixos-com-saída-pendente)).
- **Gráfico principal:** o card "Traffic" do template, com o título "Catálogo", o período no subtítulo, o grupo Dia, Mês e Ano (`btn-check` com `btn-outline-secondary`), o botão de baixar os números em CSV e o gráfico de 300px com aprovados (área), enviados à moderação (linha) e a média de aprovados (tracejada). No rodapé, os 5 números com `progress-thin`: ativos, em rascunho, em moderação, bloqueados e pausados, com a porcentagem da loja.
- **Tabela:** a tabela de usuários do dashboard com os produtos atualizados por último: avatar com a inicial e o ponto da cor do status, produto com categoria e SKUs, status, estoque disponível sobre o físico com barra, menor preço vigente e "Atualizado há…".
- Todos os números e séries vêm de `/stats/store`, `/stats/moderation` e `/stats/categories`, que aceitam `from` e `to` de 0001-01-01 a 9999-12-31. Loja com `canPublish` falso em `GET /stores/{storeId}/eligibility` ganha também um alerta em **Produtos**: "Sua loja ainda não foi aprovada. Você pode cadastrar produtos, mas eles só são aprovados depois do KYB/KYC".

### 2. Produtos da loja

```text
Produtos                                        [+ Novo produto]
[Todos] [Rascunho] [Em moderação] [Ativos] [Sem estoque]
[Pausados] [Bloqueados] [Arquivados]
Buscar: [ título ou SKU...            ]

[ ] Título ↕       Status          SKUs  Criado ↕     Atualizado ▼
[ ] Camiseta Basic [Ativo]           3   02/11 10:12  08/11 14:02  [...]
[ ] Caneca 300ml   [Em moderação]    1   08/11 12:50  08/11 13:10  [...]
[ ] Tênis Run      [Rascunho]        2   05/11 11:00  07/11 09:15  [...]

[< Anterior]  [Próxima >]                    Itens: [20 v]
```

- Fonte: `GET /products?status=&sort=&limit=&cursor=`. Rótulos: Rascunho (`DRAFT`), Em moderação (`PENDING_MODERATION`), Ativo (`ACTIVE`), Pausado (`PAUSED_BY_SELLER`), Sem estoque (`OUT_OF_STOCK`), Bloqueado (`BLOCKED`), Arquivado (`ARCHIVED`).
- A aba **Sem estoque** filtra pelo saldo (`status=OUT_OF_STOCK`, o `ACTIVE` sem saldo disponível), e a busca (`q`) procura no título, sem diferenciar maiúsculas e acentos, e no código do SKU.
- O rascunho que voltou da moderação mostra o badge **Rejeitado** ao lado de Rascunho, com os motivos no tooltip, lidos de `lastRejection` (`reasonLabels` e `note`).
- Cada item traz `actions`, `blockedActions` e `saveReturnsToModeration`, mas não `publishingIssues`, para a lista não ler a categoria de cada produto. Por isso **Enviar para moderação** aparece liberado na lista mesmo com pendência e barrado por `product-not-publishable` na tela do produto; o 422 do envio decide.

### 3. Novo produto

Modal com três campos: categoria, título e moeda (fixa depois da criação). A categoria é escolhida num `form-select` com o caminho completo de cada categoria usável (`usable`), na ordem da árvore. É o mesmo seletor usado para [trocar a categoria](#trocar-categoria) e para mover categorias. Ao criar (`POST /products`), o painel abre o produto na aba **Conteúdo** para completar os atributos.

### 4. Produto

```text
Camiseta Basic   [Rascunho]          [Enviar p/ moderação] [...]
[Conteúdo] [SKUs, preço e estoque] [Histórico]
+---------------------------------+ +---------------------+
| Título    [Camiseta Basic    ]  | | Para enviar         |
| Categoria Camisas     [Trocar]  | | ok Conteúdo         |
| Descrição [...]                 | | x  SKU CAM-P sem    |
| Material  [Algodão v] *         | |    preço            |
| Gola      [Redonda  v]          | | x  Material vazio   |
|                       [Salvar]  | |                     |
+---------------------------------+ +---------------------+
```

- **Conteúdo:** título, descrição, categoria (com o botão [Trocar](#trocar-categoria)) e atributos do produto. `PUT /products/{id}` com `If-Match`, que leva o `categoryId` junto.
- **SKUs, preço e estoque:** tabela com código, variação, preço vigente, disponível e situação da oferta. Clicar na linha abre o [painel do SKU](#5-painel-do-sku). **+ Adicionar SKU** abre um offcanvas com o código do seller, os atributos de variação, a opção de SKU físico e, se for físico, as dimensões e o peso.
- **Histórico:** linha do tempo com `GET /products/{id}/history` (transição, de, para, quem e quando). A rejeição e o bloqueio mostram em destaque os motivos marcados, com os rótulos da época (`reasonLabels`), e o complemento (`note`), separados e sem interpretar o texto único `reason`, que é só derivado. Renomear ou desativar um motivo depois não muda o que aparece aqui.
- **Checklist de envio:** vem do servidor, calculado pela `PublishingPolicy`: `publishingIssues` na resposta do produto salvo e, enquanto o formulário tem mudanças não salvas, `POST /products/{id}/check` com o rascunho, chamado com debounce. Cada pendência traz `code`, `params`, `message` e `field` (`categoryId`, `attributes.<código>`, `skus[<sku>].offer` ou `skus[<sku>].variantAttributes.<código>`), que leva à aba e ao campo do problema. Com pendências, **Enviar para moderação** vem em `blockedActions` com `product-not-publishable`, e a resposta 422 de `POST /products/{id}/submit` continua sendo a palavra final.

Ações por status. Pausar, reativar, enviar e arquivar ficam no menu "..." da lista e da tela do produto, e a primeira delas que vier em `actions` também vira botão na tela do produto. O servidor calcula as ações (`ProductActions`), e a tabela descreve o resultado para o lojista, sem cópia no front:

| Status | Ações do lojista |
|---|---|
| `DRAFT` | Salvar, adicionar e remover SKU, enviar para moderação, arquivar |
| `PENDING_MODERATION` | Salvar (continua na fila), adicionar SKU, arquivar |
| `ACTIVE` | Salvar (volta à moderação, com confirmação), adicionar SKU, pausar, arquivar |
| `OUT_OF_STOCK` | Como `ACTIVE`, com o aviso "Sem estoque disponível" na aba de SKUs |
| `PAUSED_BY_SELLER` | Salvar (volta à moderação, com confirmação), adicionar SKU, reativar, arquivar |
| `BLOCKED` | Conteúdo só leitura, com o motivo do bloqueio em destaque. Pode arquivar e ajustar preço e estoque |
| `ARCHIVED` | Só leitura |

Fora do rascunho, a remoção de SKU vem em `blockedActions` com `sku-removal-requires-draft`, e reativar com a loja não aprovada vem com `seller-not-eligible`: os botões aparecem desabilitados com o motivo. Preço e estoque seguem `EDIT_OFFER` e `EDIT_STOCK`.

#### Trocar categoria

```text
Categoria  Vestuário > Camisas                  [Trocar]

+-- Trocar categoria -----------------------------------+
| Categoria atual: Vestuário > Camisas                  |
| Nova categoria  [ Vestuário › Calças            v ]   |
|                                                       |
| Efeito da troca                                       |
|  - Gola (Redonda) deixa de existir                    |
|  + Cintura (cm), obrigatório em cada SKU              |
|  x CAM-P-AZ usa Tamanho e Cor, que Calças não tem     |
|    como variação                                      |
|  Remova esses SKUs, troque a categoria e crie os      |
|  SKUs de novo.                                        |
|                                  [Cancelar] [Trocar]  |
+-------------------------------------------------------+
```

- O botão **Trocar** aparece quando `CHANGE_CATEGORY` vem em `actions` (hoje, em todo status editável, fora de `BLOCKED` e `ARCHIVED`) e abre um modal com o `form-select` de caminhos, só com as categorias usáveis (`usable`) e sem a atual.
- Ao escolher o destino, o modal chama `POST /products/{id}/check` com o rascunho e a categoria nova e mostra `categoryChange`, que o servidor calcula com a regra do domínio (`PublishingPolicy.valueViolations`): cada atributo precisa existir na categoria nova com o mesmo papel, do produto ou de variação, e com um valor aceito.
  - **Atributos do produto que somem ou ficam com valor inválido** (`removedAttributes`): são apagados do formulário, e a prévia lista cada um com o rótulo e o valor perdido.
  - **Obrigatórios novos, do produto ou de variação** (`newRequired`): não impedem a troca. Entram no checklist de envio.
  - **SKUs com variação que a categoria nova não tem ou não aceita** (`blockingSkus`, com os motivos): impedem a troca, porque a API recusaria com `invalid-attributes` (`unknown-attribute:<sku>:<código>` ou `invalid-attribute:<sku>:<código>`). Com `REMOVE_SKU` em `actions` (rascunho), a prévia orienta a remover esses SKUs, trocar e criar de novo. Sem ela, a prévia orienta a escolher uma categoria com as mesmas variações ou a arquivar o produto e criar outro.
- **Trocar** só muda o formulário: os atributos são refeitos para a categoria nova e um aviso mostra a categoria anterior, com a opção de desfazer. A gravação acontece no **Salvar**, num único `PUT /products/{id}` com o `categoryId` novo. Em produto publicado, o Salvar mantém a confirmação de volta à moderação.

### 5. Painel do SKU

```text
+-------------------------------------+
| CAM-M   M / Azul                [x] |
| PREÇO                               |
| De [ 59,90 ]   Por [ 49,90 ]        |
| Vigente agora: R$ 39,90 (promoção)  |
|                          [Salvar]   |
| PROMOÇÕES               [+ Agendar] |
| 10/11 10:00 a 15/11 23:59  R$ 39,90 |
|                          [Cancelar] |
| ESTOQUE                             |
| Físico [ 8 ]  Reservado 3  Vendido 41|
| Disponível 5             [Salvar]   |
+-------------------------------------+
```

- **Preço:** `GET` e `PUT /offers/{skuId}`. A primeira gravação cria a oferta, e as seguintes exigem `If-Match`. "De" é opcional e não pode ser menor que "Por" (`list-price-below-sale-price`).
- **Promoções:** lista das agendadas e vigentes, pela fase que o servidor calcula com o relógio dele (`phase`). **+ Agendar** abre um formulário no próprio painel, com início, fim e preço, e o intervalo é semiaberto (`[startsAt, endsAt)`). As datas usam `datetime-local`, lidas no fuso de São Paulo e enviadas em UTC. Cancelar (`DELETE`) pede confirmação e só aparece em promoção não encerrada.
- **Estoque:** `GET` e `PUT /inventory/skus/{skuId}`, com o físico absoluto, como uma contagem da loja. Reservado e vendido são só leitura, e o campo físico avisa quando o valor fica abaixo do reservado.
- Preço e estoque salvam separados, cada um com a sua versão. Mudar oferta ou estoque não devolve o produto à moderação (ADR 014), e o painel não mostra o aviso de moderação aqui.

### 6. Importações

```text
Importações               [Baixar modelo CSV] [Enviar planilha]

Lote de 08/11 14:20   Processando   320 de 1200   [=====     ]
  Criadas 290   Atualizadas 25   Sem mudança 0   Falhas 5
  [Todas] [Só falhas]
  Linha 14   CAM-PP   Falha   weight_g inválido
  Linha 15   CAM-PM   Falha   Produto P1 não criado por erro na(s) linha(s) 14

Histórico
  07/11 09:02   Concluída   800 linhas   3 falhas   >
```

- **Modelo CSV:** o botão **Baixar modelo CSV** chama `GET /products/imports/template` (`text/csv`), gerado de `ProductCsv.COLUMNS` com uma linha de exemplo; o endpoint está pendente. Até lá, o painel monta o modelo com o cabeçalho exato da ADR 016 (`TEMPLATE_COLUMNS`), cópia de `ProductCsv.COLUMNS` marcada como pendente.
- **Envio:** `POST /products/imports` (`text/csv`). Os limites de 1 MB e 5000 linhas (`quarkus.http.limits.max-body-size` e `catalog.imports.max-rows`) ficam no servidor. A conferência antes de subir espera `importMaxBytes` e `importMaxRows` de `GET /settings`, pendente; até lá, a tela envia o arquivo e mostra a recusa do servidor, sem copiar os números. Uma recusa 400 `invalid-import-file` mostra a mensagem de cada motivo em `reasonDetails` (coluna que falta, linhas demais, com o máximo vigente, e outros).
- **Andamento:** o painel consulta `GET /products/imports/{id}?lines=false` (situação e contagens, sem as linhas) enquanto o status for `PENDING` ou `PROCESSING` e para em qualquer outro, que conta como terminal; as linhas são lidas uma vez, com o lote terminado. O intervalo segue o `Retry-After` da resposta quando ele vier, o que o servidor ainda não manda (pendente); até lá, a tela usa um intervalo próprio (2 segundos), marcado como pendente. A barra usa `processedLines` e `totalLines`.
- **Resultado:** lista as linhas por `lineNumber`, `sellerSku`, `outcome` e `message`, com o filtro **Só falhas**, e a linha criada leva ao produto (`productId`). A linha recusada por regra de negócio traz também `reasonDetails`, um item por motivo, que o painel mostra como lista; o `code` da linha serve só ao filtro e nunca aparece cru.
- **Histórico:** `GET /products/imports`, com os lotes da loja do mais recente ao mais antigo e cursor, e a rota `/loja/importacoes/:id` pode ser salva.

### 7. Situação da loja

Callout com o status de `GET /stores/{storeId}/eligibility`. O front guarda só o rótulo e a cor de cada `SellerStatus`; o efeito vem dos campos que o servidor calcula, `canPublish` (produtos podem ser aprovados e reativados) e `visibleInStorefront` (produtos ativos aparecem na vitrine):

| Campo | Verdadeiro | Falso |
|---|---|---|
| `canPublish` | Produtos aprovados vão para a vitrine, e os pausados podem ser reativados. | Você pode cadastrar produtos, mas eles só são aprovados e reativados depois da aprovação no KYB/KYC. |
| `visibleInStorefront` | Os produtos ativos aparecem na vitrine. | Os produtos não aparecem na vitrine. |

### 8. Fila de moderação

```text
+----------------------+-----------------------------------+
| Fila                 | Camiseta Basic        Loja 3f2a.. |
|> Camiseta Basic      | Loja: Aprovada                    |
|  Tênis Run           | Vestuário > Camisas               |
|  Caneca 300ml        | Material: Algodão   Gola: Redonda |
|  ...                 | SKUs  CAM-P  P/Azul   R$ 49,90  12 |
|                      |       CAM-M  M/Azul   R$ 39,90   0 |
| [< Anterior] [Próx.>]| Histórico: enviado em 08/11 13:10 |
|                      | [Aprovar] [Rejeitar] [Bloquear]   |
+----------------------+-----------------------------------+
```

- Fonte: `GET /moderation/products`, com o produto completo em cada item. O detalhe busca as ofertas e os saldos dos SKUs e o histórico.
- **Ordem:** os mais antigos na fila primeiro, pela hora em que o produto entrou em moderação (`moderationQueuedAt`), que não muda a cada edição.
- **Ações:** cada item da fila traz `actions` com Aprovar, Rejeitar e Bloquear. Enquanto a loja não está aprovada, Aprovar vem em `blockedActions` com `seller-not-eligible` e aparece desabilitado com a mensagem do servidor; no detalhe, uma pendência de publicação barra antes com `product-not-publishable`. O painel não decide isso pelo status da loja.
- **Em lote:** cada item da fila tem caixa de seleção, e a barra oferece Aprovar, Rejeitar e Bloquear para os marcados que trazem a ação em `actions` (ver [Seleção e operações em lote](#seleção-e-operações-em-lote)). As recusas de cada item aparecem no alerta do resultado, com a mensagem do servidor, e esses itens continuam na fila.
- **Aprovar** (`POST .../approve`) não pede confirmação. O toast confirma e o próximo item abre sozinho. Uma recusa aparece no alerta do detalhe.
- **Rejeitar** e **Bloquear** abrem um modal com os motivos prontos que valem para a decisão (marcar um ou mais) e um complemento livre (`note`), com o contador do limite dele. O limite (500) é o `maxLength` de `note` no contrato, que o modal lê do schema gerado (`fieldRules`). Rejeitar devolve ao rascunho, e bloquear tira da vitrine na hora, com o texto do efeito no modal. Sem motivo marcado, o complemento é obrigatório.

```text
Rejeitar "Camiseta Basic"
[x] Categoria incorreta
[ ] Descrição incompleta ou enganosa
[ ] Atributos inconsistentes com os SKUs
[ ] Preço fora do padrão
[ ] Item proibido na plataforma
Complemento: [ A gola informada não bate com a foto...  ]
                                             312 de 500
O produto volta ao rascunho do lojista.   [Cancelar] [Rejeitar]
```

- **Opções do modal:** vêm de `GET /moderation/reasons?decision=REJECT` ou `?decision=BLOCK`, só os ativos do catálogo do tenant, na ordem de `position`, e a feature não guarda lista de motivos. Os do exemplo acima são só ilustração: cada tenant tem os seus, mantidos em [Motivos de moderação](#11-motivos-de-moderação). Com a lista vazia, o modal mostra só o complemento e avisa que o administrador cadastra os motivos em **Plataforma > Motivos de moderação**.
- **Motivo que mudou no meio:** se o motivo marcado foi desativado, não vale mais para a decisão ou saiu do catálogo, a API recusa com 422 `invalid-moderation-reason` (`reason-inactive`, `reason-not-applicable` ou `unknown-reason`). Em `reason-inactive` e `reason-not-applicable`, a mensagem de `reasonDetails` já traz o rótulo; em `unknown-reason` o servidor não tem rótulo para citar, porque o código não está no catálogo do tenant, e a mensagem diz só que um dos motivos não está no catálogo, sem o código. O modal mostra a mensagem, recarrega a lista e mantém o complemento digitado.
- O histórico grava os códigos, os rótulos da época e o complemento separados, e o `reason` é o texto único derivado ("Categoria incorreta. A gola informada não bate..."), que o painel não interpreta.
- Produto bloqueado sai da fila. **Desbloquear** fica no detalhe do produto em **Produtos de todas as lojas**, que o moderador lê pela permissão composta `can.readTenantCatalog`, e aparece quando `UNBLOCK` vem em `actions`.
- A situação da loja no detalhe vem de `GET /stores/{storeId}/eligibility`, que o moderador lê com `READ_SELLER_ELIGIBILITY`.

### 9. Categorias

```text
+-------------------------------------+ +------------------------------------------+
| Categorias        [Selecionar] [+ ] | | Camisas [Ativa]        [Editar] [⋮]      |
| 14 categorias | 3 raízes            | | Vestuário › Camisas | VEST-CAM | nível 2 |
| Raiz / Vestuário                    | +------------------------------------------+
| +---------------------------------+ | | Atributos próprios                       |
| | Calças                          | | |  Gola        Opção    –                  |
| | VEST-CAL | 1 atributo próprio   | | |  Tamanho     Opção    [Obrigatório] [Variante] |
| |>Camisas                  (2 >)  | | +------------------------------------------+
| | VEST-CAM | 3 atributos próprios | | | Atributos herdados                       |
| +---------------------------------+ | |  Material    Opção    [Obrigatório]  Vestuário |
+-------------------------------------+ +------------------------------------------+
```

- **Lista por nível:** um `list-group` com as categorias do nível atual (`GET /categories?parentId=`), cada uma com código e número de atributos próprios. O botão em pílula com o número de subcategorias entra no nível de baixo, e o breadcrumb ("Raiz / Vestuário") volta. Clicar no nome abre o detalhe ao lado. Categoria inativa leva o badge **Inativa**.
- **Detalhe:** card com nome, situação, caminho, código e nível (`usable`, `ancestors`, `level` e `childCount` vêm da API, sem recálculo no front), e dois cards com os atributos próprios (editáveis) e os herdados (`effectiveAttributes`, só leitura, com a categoria de origem), na tabela do template.
- **Ações:** **Editar** é o botão principal. O dropdown traz Mover para…, Nova subcategoria, Desativar ou Ativar e Excluir. **Editar** e **Nova subcategoria** abrem um offcanvas com nome, código e a lista de atributos (código, rótulo, tipo, obrigatório, variante e opções). `PUT /categories/{id}` exige `If-Match`.
- **Mover:** modal com o `form-select` de caminhos, sem a própria categoria, as descendentes e o pai atual, e com a opção "Raiz". O painel não conhece a profundidade máxima da árvore, então o modal não esconde os destinos em que ela seria excedida: a recusa `category-too-deep` aparece no modal com o limite vigente na mensagem do servidor. A saída é ler `categoryMaxDepth` de `GET /settings` e filtrar os destinos pelo `level` do destino e pela altura da subárvore; pendente. As recusas `category-cycle`, `category-too-deep` e `attribute-conflict` aparecem no modal.
- Desativar avisa que a categoria e as subcategorias deixam de aceitar produtos novos.
- **Excluir** vale só para a categoria vazia, sem subcategorias e sem nenhum produto, nem arquivado.
  - **Com subcategorias:** a opção não aparece no dropdown, e o detalhe explica "Para excluir, primeiro exclua ou mova as subcategorias".
  - **Com produtos:** o front não tem como contar os produtos da categoria. A confirmação mostra a recusa `category-has-products` e sugere trocar a categoria dos produtos ou desativar a categoria.
  - **Vazia:** a confirmação avisa que a exclusão é definitiva. Depois, o detalhe passa para a categoria pai.
- **Em lote:** **Selecionar** mostra caixas na lista do nível, com "Selecionar todas deste nível", e a barra oferece Ativar, Desativar e Excluir para as marcadas, numa chamada de `POST /categories/batch` por grupo. A exclusão em lote manda todas as marcadas e segue a mesma regra da exclusão de uma categoria, conferida pelo servidor item a item, das subcategorias para as categorias pai.

### 10. Produtos de todas as lojas

A mesma lista e o mesmo detalhe de **Produtos da loja**, com o filtro **Loja** por ID (`storeId`), para quem tem a permissão composta `can.readTenantCatalog`. O conteúdo é só leitura, e o menu de ações mostra o que vier em `actions`: para o moderador, **Desbloquear** em produto bloqueado. O contexto que cadastra as lojas ainda não existe, então não há nome de loja para mostrar nem escolher. A coluna mostra o início do UUID com o completo no tooltip.

### 11. Motivos de moderação

```text
Motivos de moderação                    Mostrar inativos [ o]   [+ Novo motivo]
Os motivos prontos que o moderador marca ao rejeitar ou bloquear.

Motivo                         Decisões              Situação   Posição
Categoria incorreta            [Rejeitar]            [Ativo]         10   [...]
wrong-category
Item proibido na plataforma    [Bloquear]            [Ativo]         20   [...]
prohibited-item
Preço fora do padrão           [Rejeitar][Bloquear]  [Inativo]       30   [...]
price-out-of-range
```

- **Acesso:** a rota `/plataforma/motivos-de-moderacao` e o item do menu exigem `MANAGE_MODERATION_REASONS`, que o servidor dá ao administrador do catálogo. Cada tenant mantém o próprio catálogo, e o tenant novo começa sem motivos (ADR 030).
- **Lista:** `GET /moderation/reasons?includeInactive=true`, inteira, sem paginação, na ordem de `position` e, no empate, de `code`, a mesma ordem do modal da fila e dos rótulos no texto da decisão. A tabela do template mostra o rótulo com o código na linha de apoio, as decisões em que o motivo vale (`decisions`), a situação (`active`) e a posição. O switch **Mostrar inativos** começa desligado. Estado vazio: "Nenhum motivo cadastrado. Sem motivos, o moderador decide só com o complemento. Criar motivo".
- **Novo motivo:** offcanvas com código, rótulo, decisões (`form-check` Rejeitar e Bloquear, ao menos uma) e posição opcional; sem posição, o motivo entra no fim. O campo do código avisa que ele não muda depois. `POST /moderation/reasons` responde 201 com o `ETag`. Recusas: 409 `duplicate-moderation-reason-code`, quando o código já existe no tenant, inclusive num motivo inativo, e o painel liga **Mostrar inativos** para a pessoa reativar o existente; 422 `moderation-reason-limit`, quando o tenant já tem o máximo de motivos ativos, e 422 `moderation-reason-catalog-limit`, quando o catálogo chegou ao teto contando os inativos, as duas com o teto vigente e a saída (desativar um motivo ou reativar um existente) na mensagem do servidor; 400 com os campos inválidos embaixo de cada campo.
- **Editar:** o mesmo offcanvas, com o código só leitura. `GET /moderation/reasons/{code}` traz o `ETag`, e `PUT /moderation/reasons/{code}` com `If-Match` troca rótulo, decisões e posição. O offcanvas avisa que as decisões já tomadas guardam o rótulo da época e o rótulo novo vale para as próximas. O 412 segue o [conflito de edição](#conflito-de-edição).
- **Desativar e reativar:** pelo menu "...", com `POST /moderation/reasons/{code}/deactivate` e `/activate`, os dois com `If-Match`. Desativar pede confirmação: "O motivo some da lista do moderador e não vale em decisões novas. As decisões passadas continuam com o rótulo da época". Reativar não pede. Não há **Excluir**, porque o código está no histórico e nos eventos; desativar é o fim de vida de um motivo.
- **Efeito na fila:** a lista do moderador passa pelo cache do serviço, invalidado a cada escrita (ADR 030), e a mudança vale na próxima vez que o modal carregar os motivos.
- O formato e os tamanhos do código e do rótulo vêm do contrato (`pattern` e `maxLength`) e seguem a regra dos [formulários](#formulários): copiados até a saída do schema gerado.

## Endpoints das telas

Todos existem no contrato (`contracts/catalog-inventory/openapi.json`):

| Tela | Endpoints |
|---|---|
| Sessão, menu e rotas | `GET /me` |
| Início | `GET /stats/store`, `/stats/moderation` e `/stats/categories`, `GET /products?sort=-updatedAt` e `GET /categories?ids=` (nomes das categorias da tabela) |
| Produtos da loja, Produtos de todas as lojas | `GET /products` com `status`, `q`, `sort`, `storeId`, `limit` e `cursor`; `POST /products/batch` |
| Produto | `GET`, `PUT` e `DELETE /products/{id}`, `POST /products/{id}/submit`, `/pause` e `/resume`, `POST /products/{id}/check`, `GET /products/{id}/history`, `POST /products/{id}/skus`, `PUT` e `DELETE /products/{id}/skus/{skuId}`; `GET /categories/tree?usable=true` e `GET /categories/{id}` para escolher a categoria e montar os atributos |
| Painel do SKU | `GET` e `PUT /offers/{skuId}`, `POST /offers/{skuId}/promotions`, `DELETE /offers/{skuId}/promotions/{promotionId}`, `GET` e `PUT /inventory/skus/{skuId}` |
| Importações | `POST` e `GET /products/imports`, `GET /products/imports/{id}` (com `lines=false` no andamento) |
| Situação da loja | `GET /stores/{storeId}/eligibility` |
| Fila de moderação | `GET /moderation/products`, `POST /moderation/products/{id}/approve`, `/reject`, `/block` e `/unblock`, `POST /moderation/products/batch`, `GET /moderation/reasons?decision=`, `GET /stats/moderation/queue` (badge do menu) |
| Categorias | `GET /categories`, `/categories/tree` e `/categories/{id}`, `POST /categories`, `PUT` e `DELETE /categories/{id}`, `PUT /categories/{id}/parent`, `POST /categories/batch` |
| Motivos de moderação | `GET /moderation/reasons?includeInactive=true`, `GET /moderation/reasons/{code}`, `POST /moderation/reasons`, `PUT /moderation/reasons/{code}`, `POST /moderation/reasons/{code}/activate` e `/deactivate` |

## Valores fixos com saída pendente

Esta proposta não aceita repetir no painel uma regra, um catálogo ou um número do servidor sem nomear a saída (ADR 031).

**Já no código.** Os valores abaixo estão fixos hoje, e a saída de cada um está pendente:

| Valor fixo hoje | Onde | Saída |
|---|---|---|
| Fuso `America/Sao_Paulo` | `dates.ts` em `libs/shared/web-util` e `CatalogStatsService` | Chave `catalog.time-zone` no serviço, entregue por `GET /settings` (`timeZone`) |
| 100 itens por chamada em lote | `BATCH_LIMIT` em `libs/shared/web-util`, cópia de `Batch.MAX_ITEMS` | `maxItems` do pedido no schema gerado (plugin `@hey-api/schemas`) |
| Máximo de 100 itens por página | `PAGE_SIZES` em `libs/shared/web-util`, cópia de `Page.MAX_SIZE` | `maximum` do parâmetro `limit` no schema gerado |
| Profundidade máxima da árvore, que o modal de mover não usa | Domínio (`Category`) e `CHECK` do banco; o painel não a copia | `categoryMaxDepth` em `GET /settings`, para filtrar os destinos |
| Indicadores da página inicial | Campos fixos de `StoreStatsResponse` e `ModerationStatsResponse`, no servidor | Lista `indicators` com `code`, `label`, `value`, `previous`, `change` e `months`, com o painel mapeando `code` para ícone e cor |
| Issuer, `clientId` e scope do login | `auth.config.ts`, um build por ambiente (ADR 027) | `/config.json` gerado na entrada do contêiner a partir dos values do Helm e lido antes do login |
| Tamanho máximo da busca (100) | `SEARCH_MAX_LENGTH` em `admin/feature-products`, cópia do `maxLength` do parâmetro `q` de `GET /products` | `maxLength` do parâmetro `q` no schema gerado |
| Um card por campo dos indicadores | `home-indicators.ts` em `admin/feature-products`, para `/stats/store`, `/stats/moderation` e `/stats/categories` | Lista `indicators`, como acima |
| Intervalo da consulta do andamento (2 segundos) | `POLL_INTERVAL_MS` em `admin/feature-imports`, usado enquanto a resposta não traz `Retry-After` | `Retry-After` em `GET /products/imports/{id}` enquanto o lote está em andamento |
| Cabeçalho do modelo CSV | `TEMPLATE_COLUMNS` em `admin/feature-imports`, cópia de `ProductCsv.COLUMNS` | `GET /products/imports/template` (`text/csv`) |
| Excluir só a categoria sem subcategorias (`childCount` zero) | `category-model.ts` em `admin/feature-categories`, no botão de cada categoria; o lote manda todas as marcadas, e o servidor recusa item a item; `category-has-products` fica com o servidor | Campo `deletable` calculado pelo servidor na resposta da categoria |

**Sem cópia no painel.** A importação não confere o arquivo antes do envio: manda a planilha e mostra a recusa do servidor (400 `invalid-import-file`), sem copiar os números. A conferência antes do envio espera a saída:

| Valor | Tela | Saída |
|---|---|---|
| 1 MB e 5000 linhas por planilha | Importação | `importMaxBytes` e `importMaxRows` em `GET /settings` |

Os limites dos formulários (`pattern`, `maxLength`, obrigatórios e o contador de 500 do complemento da moderação) e o tamanho de cada chamada em lote já saem do schema gerado (`fieldRules`); o `BATCH_LIMIT` de `libs/shared/web-util` ficou só como padrão de `chunk`, ainda marcado como cópia pendente.

Ficam no painel de propósito, sem saída: os rótulos e as cores dos status, a apresentação de cada ação (rótulo, ícone, ordem, confirmação, toast e cor), os códigos técnicos de protocolo do aviso de conflito e do erro de rede, as rotas em português, os ícones, o atraso do toast, o locale `pt-BR`, o prefixo `/api/catalog-inventory` e os tamanhos de página 20 e 50.

## Organização do código

Segue a ADR 027. Toda lib leva também a tag `lang:ts`:

| Lib | Tags | Conteúdo |
|---|---|---|
| `libs/catalog-inventory/admin/data-access` | `scope:catalog-inventory`, `layer:data-access`, `framework:angular` | Cliente gerado (já existe) |
| `libs/catalog-inventory/admin/feature-products` | `scope:catalog-inventory`, `layer:feature`, `framework:angular` | Lista, produto, painel do SKU, novo produto, produtos de todas as lojas, e os indicadores, o gráfico e a tabela da página inicial |
| `libs/catalog-inventory/admin/feature-imports` | idem | Importações |
| `libs/catalog-inventory/admin/feature-store` | idem | Situação da loja |
| `libs/catalog-inventory/admin/feature-moderation` | idem | Fila de moderação e Motivos de moderação |
| `libs/catalog-inventory/admin/feature-categories` | idem | Categorias |
| `libs/catalog-inventory/admin/ui` | `scope:catalog-inventory`, `layer:ui`, `framework:angular` | Badge de status do produto, checklist de envio e seletor de categoria (`form-select` com o caminho) |
| `libs/shared/admin-ui` | `scope:shared`, `layer:ui`, `framework:angular` | Paginação por cursor sobre o `c-pagination`, cabeçalho ordenável (a única parte própria), barra de lote, bloco de número, modal de confirmação, alerta de problema RFC 9457, serviço de toasts sobre o `c-toaster` e o `AdminSession`, contrato da sessão que as features injetam e que o `SessionStore` do app implementa com `GET /me` |
| `libs/shared/web-util` | `scope:shared`, `layer:util`, `framework:none` | Moeda, datas no fuso de São Paulo, escolha da mensagem de um problema (sobrescritas do painel, `reasonDetails`, `detail` e `title`), cursor e lote |

- O seletor de categoria fica em `admin/ui` porque três telas usam o mesmo componente (novo produto, trocar categoria e mover categoria), e uma feature não importa outra.
- A página inicial fica no app e monta os indicadores, o gráfico e a tabela que `feature-products` exporta, com o `@coreui/angular-chartjs` e o `chart.js` carregados só nessa rota (ADR 028).
- Uma lib `layer:ui` só depende de `layer:ui` e `layer:util` (`eslint.config.mjs`), então os componentes de `admin/ui` não importam o `data-access`, nem para tipos. Eles usam tipos próprios e recebem tudo por input. O seletor recebe a lista de categorias com o caminho já montado de `ancestors` e a usabilidade de `usable`, e cada feature monta essa lista com o `data-access`. O checklist recebe as pendências que o servidor calculou (`publishingIssues` do produto ou da resposta de `POST /products/{id}/check`), com a mensagem e o campo de cada uma, e só as distribui pelas abas pelo prefixo do campo. O badge recebe o status. Os limites que um componente de `admin/ui` precisar chegam por input, vindos da feature.
- As libs Angular não são buildable, e o `vitest-angular` não aceita lib nesse caso no Nx 24.0.4. Lib com teste é gerada com `--unitTestRunner=vitest-analog`, como registra a ADR 027.

## Ordem sugerida

1. `shared/admin-ui` e `web-util`: sessão por `GET /me`, paginação, cabeçalho ordenável, barra de lote, avisos com a mensagem do servidor, confirmação, moeda e datas.
2. Produtos da loja e tela do produto, com conteúdo, histórico, ações e checklist vindos do servidor.
3. Painel do SKU: preço, promoções e estoque.
4. Situação da loja e Início, com os endpoints de métricas.
5. Fila de moderação.
6. Motivos de moderação.
7. Categorias.
8. Importações.

Os endpoints de todas as telas já existem (ver [Endpoints das telas](#endpoints-das-telas)); as saídas dos valores fixos entram quando forem implementadas, sem mudar a ordem.

## Fora desta proposta

- O marketplace (`apps/storefront-web`) e a prévia do produto na vitrine.
- Imagens de produto: a API não tem mídia.
- Telas dos outros contextos, que seguem os mesmos padrões quando existirem.
- Tema por tenant (white-label) e paleta própria, que dependem da lib de tokens.
