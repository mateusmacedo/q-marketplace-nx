# Proposta de design do painel: catálogo-inventário

Status: aprovada em 08/11/2026. As decisões estão registradas na ADR 029 em [`decisoes.md`](decisoes.md), que também revisou a ADR 028.

Esta proposta define a navegação, as telas e os padrões de interação do painel (`apps/admin-web`) para os recursos do catálogo-inventário. Ela parte das ADRs 027 e 028 (Angular 22, CoreUI grátis, paginação por cursor, datas no fuso `America/Sao_Paulo`), das escolhas do usuário numa entrevista sobre navegação, interface e usabilidade e da revisão dessas escolhas pelo que o template grátis do CoreUI entrega (ver [Revisão pelo template grátis](#revisão-pelo-template-grátis)).

A regra é usar os componentes, a disposição e as transições do template grátis, com a mesma marcação da demo, ajustando só o conteúdo ao contexto. Um componente próprio só entra quando a versão grátis não entrega e há justificativa de usabilidade, e mesmo assim a primeira opção é montar com o que o template tem. O que a API ainda não tem será construído (ver [Endpoints a construir](#endpoints-a-construir)).

## Decisões

| Tema | Decisão | Custo aceito |
|---|---|---|
| Menu lateral | Grupos por papel num menu só: **Minha loja**, **Moderação** e **Plataforma**. Cada grupo aparece se o JWT tiver o papel | Quem acumula papéis vê um menu mais longo |
| Página inicial | Formato do dashboard do template: 4 indicadores com minigráfico, gráfico principal com rodapé de números e tabela dos produtos atualizados | Pede o endpoint de métricas, com contagens e séries |
| Telas | Pensado para desktop e usável no celular | Editar produto com muitos SKUs no celular dá mais trabalho |
| Filtros | Abas por status, busca por texto nova no backend e filtros na URL | A busca depende de mudança na API |
| Paginação | `c-pagination` do template só com Anterior e Próxima, e 20, 50 ou 100 itens por página | Sem salto para uma página nem total |
| Ações na lista | O título do item é o link que abre o registro, e o dropdown do template (o mesmo dos indicadores) traz as ações permitidas no status. A tela individual repete o mesmo menu e mostra a ação principal como botão | Um clique a mais por ação, e a ação principal aparece em dois lugares na tela individual |
| Ordenação | Pelo backend, clicando no cabeçalho de Produto, Criado e Atualizado, com `sort` na URL e cursor composto. Padrão: Atualizado, mais recente primeiro. A fila de moderação vem em ordem de chegada. É a única parte própria das listas, escolhida pelo usuário, porque só a Smart Table da PRO ordena | Mudança de contrato e de cursor, com índices novos, e um cabeçalho ordenável escrito no projeto |
| Seleção em lote | Caixas de seleção em Produtos da loja, Fila de moderação e Categorias, com a barra de botões do template (`btn-toolbar`) e o resultado de cada item. O front chama a API item a item, 4 por vez | Até 100 chamadas por lote, e fechar a aba no meio interrompe o lote |
| Tela do produto | Abas (Conteúdo, SKUs, preço e estoque, Histórico) e checklist de envio ao lado | O checklist repete no front parte das regras de publicação |
| Troca de categoria | Botão **Trocar** ao lado da categoria, com prévia do efeito: atributos que somem, obrigatórios novos e SKUs incompatíveis, que impedem a troca | O front repete a regra de compatibilidade das variações |
| Preço e estoque | Painel lateral (offcanvas) por SKU, com mudanças em massa pela planilha | Um SKU por vez na tela |
| Formulários secundários | Modal para 1 ou 2 campos e confirmações, offcanvas para formulários médios | Dois padrões para seguir |
| Moderação | Fila e detalhe lado a lado, com avanço automático | Pede tela larga, e no celular vira lista e detalhe |
| Motivo de moderação | Motivos prontos com complemento livre, gravados no `reason` | Lista mantida no front até haver códigos na API |
| Categorias | Lista por nível (`list-group`) com breadcrumb e card de detalhe. Escolher categoria em modais usa um `form-select` com o caminho completo | Não mostra a hierarquia inteira de uma vez |
| Exclusão de categoria | Só da categoria vazia: sem subcategorias e sem nenhum produto, nem arquivado. A categoria já usada só é desativada | Depende de um endpoint novo, e excluir não tem volta |
| Importação | Envio, andamento, resultado por linha e histórico de lotes | O histórico depende de um endpoint novo |
| Avisos | Toast do template (marcador de cor, título, hora e barra de tempo) para sucesso, e erro no lugar onde aconteceu | Motivos de recusa traduzidos e mantidos no front |
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

Continuam como estavam, porque o template entrega: menu agrupado por papel, abas, busca com `input-group`, dropdown de ações, abas da tela do produto, offcanvas, modal, fila e detalhe lado a lado (grade, `list-group` e card), motivos de moderação com `form-check` e importação com progresso e tabela.

## Quem usa e o que acessa

| Papel | Grupo do menu | O que faz | Recursos da API |
|---|---|---|---|
| `seller` | Minha loja | Cadastra produtos e SKUs, define preço, promoções e estoque, importa planilhas e acompanha a situação da loja | `Products`, `Offers`, `Inventory`, `GET /stores/{storeId}/eligibility` |
| `catalog-moderator` | Moderação | Aprova, rejeita, bloqueia e desbloqueia produtos | `Moderation` e leitura de `Products`, `Offers` e `Inventory` |
| `catalog-admin` | Plataforma | Mantém a árvore de categorias e consulta produtos de qualquer loja | `Categories` e leitura de `Products`, `Offers`, `Inventory` e elegibilidade |
| `catalog-reservations` | nenhum | Papel de serviço do checkout | `Inventory` (reservas), sem tela |

## Navegação

### Menu

```text
Início

MINHA LOJA                 (seller)
  Produtos
  Importações
  Situação da loja

MODERAÇÃO                  (catalog-moderator)
  Fila de produtos

PLATAFORMA                 (catalog-admin)
  Categorias
  Produtos de todas as lojas
```

- Cada item do `_nav.ts` declara o papel exigido, e o layout monta o menu a partir dos papéis da sessão. Os títulos de grupo usam o `title: true` do `INavData` e somem quando o grupo fica vazio.
- Os outros contextos entram nos mesmos grupos: pedidos e repasses em **Minha loja**, a análise de KYB/KYC em **Moderação**.
- O cabeçalho continua como na ADR 028: botão do menu, seletor de tema, menu do usuário e breadcrumb, que lê o `data.title` das rotas.

### Rotas

| Rota | Tela | Papel | Lib |
|---|---|---|---|
| `/inicio` | Início | qualquer | app |
| `/loja/produtos` | Produtos da loja | `seller` | `feature-products` |
| `/loja/produtos/:id/conteudo`, `/skus`, `/historico` | Produto, uma rota por aba | `seller` | `feature-products` |
| `/loja/importacoes` | Importações | `seller` | `feature-imports` |
| `/loja/importacoes/:id` | Resultado de um lote | `seller` | `feature-imports` |
| `/loja/situacao` | Situação da loja | `seller` | `feature-store` |
| `/moderacao/fila`, `/moderacao/fila/:id` | Fila de moderação | `catalog-moderator` | `feature-moderation` |
| `/plataforma/categorias`, `/plataforma/categorias/:id` | Categorias | `catalog-admin` | `feature-categories` |
| `/plataforma/produtos`, `/plataforma/produtos/:id` | Produtos de todas as lojas, só leitura | `catalog-admin` | `feature-products` |

- Um `roleGuard` por grupo complementa o `authGuard` atual. Sem o papel, a rota volta para `/inicio` com o aviso "Você não tem acesso a esta área".
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
- Paginação com o `c-pagination` do template, só com Anterior e Próxima: o componente guarda a pilha de cursores visitados para voltar, e o seletor de itens por página oferece 20, 50 e 100 (a API aceita de 1 a 100).
- O menu "..." mostra só as ações válidas no status atual (tabela em [Produto](#4-produto)). A ação some, em vez de aparecer desabilitada sem explicação.
- **Lista e tela individual com as mesmas ações:** toda ação do menu "..." da lista também está na tela individual, num menu "..." com os mesmos nomes e a mesma ordem. Os dois menus vêm de uma única definição de ações por status, na feature. A ação principal do status (Enviar para moderação, Pausar, Reativar) também aparece como botão ao lado do menu. Só **Editar** fica de fora, porque a tela individual já é a edição. A tela individual pode ter ações a mais, como o **Desbloquear** do moderador, mas nunca a menos.
- Estado vazio com texto e ação ("Nenhum produto em rascunho. Criar produto"), carregamento com o `Placeholder` do CoreUI e erro de rede com "Tentar de novo".

- **Ordenação:** os cabeçalhos ordenáveis são botões, com a seta da ordem atual e o `aria-sort` na coluna. Clicar na coluna ativa inverte a ordem, e clicar em outra coluna usa a ordem padrão dela (texto em ordem crescente, datas da mais recente para a mais antiga). A ordem fica na URL (`sort=-updatedAt`), e trocar a ordem recomeça do primeiro cursor. Hoje a API ordena pelo ID, um UUID aleatório (`PgProductRepository.java:134` e `ProductService.java:45`), então a lista não tem ordem útil até o backend aceitar o `sort`. Ordenar só a página carregada fica de fora, porque a próxima página viria em outra ordem. É a única parte própria das listas: o template grátis não ordena, e a Smart Table, que ordena, é PRO. O usuário escolheu o cabeçalho ordenável, que é o padrão mais conhecido em tabelas e mostra a ordem na própria coluna.

### Seleção e operações em lote

```text
[x] 3 selecionados   [Enviar (1)] [Pausar (2)] [Reativar (0)] [Arquivar (3)]   Limpar seleção
[x] Camiseta Basic   [Ativo]
[x] Mochila 20 L     [Sem estoque]
[x] Tênis Run        [Rascunho]
```

- **Onde:** Produtos da loja (enviar para moderação, pausar, reativar, arquivar), Fila de moderação (aprovar, rejeitar, bloquear) e Categorias (ativar, desativar, excluir). Em Categorias, as caixas aparecem na lista do nível depois de clicar em **Selecionar**, para não pesar a navegação do dia a dia.
- **Seleção:** a caixa do cabeçalho marca ou desmarca a página inteira e fica parcial quando só parte da página está marcada. A seleção continua ao trocar de página e é limpa ao mudar filtro, busca, ordem ou itens por página. Não existe "selecionar todos os resultados", porque a API não informa o total.
- **Barra de ações:** a `btn-toolbar` do template, com um `btn-group` de botões, aparece acima da tabela quando há seleção. Cada botão mostra quantos selecionados aceitam a ação ("Pausar (2)") e fica desabilitado em 0. Os selecionados em que a ação não vale são ignorados e contados no resultado.
- **Confirmação:** arquivar, desativar e excluir pedem modal, como na ação de um item só. Aprovar em lote também pede, porque publica sem abrir cada produto. Rejeitar e bloquear em lote abrem o modal de motivo uma vez, e o mesmo motivo vale para todos.
- **Execução:** o front chama a API item a item, com as mesmas regras e eventos das ações de um item, 4 de cada vez. A barra vira progresso ("Pausando 3 de 8") e as caixas ficam travadas. A exclusão de categorias roda uma de cada vez, das subcategorias para as categorias pai, para que um ramo vazio inteiro possa ser excluído de uma vez.
- **Resultado:** um toast resume ("6 pausados, 2 com falha, 1 ignorado"). As falhas aparecem num alerta acima da lista, com o nome de cada item e o motivo traduzido, e esses itens continuam selecionados para uma nova tentativa. Os que deram certo saem da seleção.
- **Saída no meio:** sair da tela ou fechar a aba durante o lote interrompe o que falta. O painel avisa antes de sair (guard `canDeactivate` e `beforeunload`). Um endpoint em lote no backend fica como evolução (ver [Endpoints a construir](#endpoints-a-construir)).

### Formulários

| Tipo | Componente | Exemplos |
|---|---|---|
| 1 ou 2 campos e confirmações | Modal | Novo produto, motivo da rejeição, confirmar arquivamento, mover categoria |
| Formulário médio que pede ver a lista atrás | Offcanvas à direita | Painel do SKU, novo SKU, agendar promoção, nova categoria |
| Formulário principal | Página | Conteúdo do produto |

- A validação no front repete o contrato (`pattern`, `maxLength`, obrigatórios) para avisar antes do envio. O 422 continua sendo a palavra final: os `errors` (`field`, `message`) vão para os campos e os `reasons` para o alerta do formulário.
- Os atributos do produto e do SKU são montados pelo tipo do atributo efetivo da categoria: `TEXT` vira campo de texto, `INTEGER` e `DECIMAL` viram campos numéricos, `BOOLEAN` vira switch e `OPTION` vira select. O atributo `variant` vai para o SKU.
- O botão de salvar mostra spinner e fica desabilitado durante o envio, para não repetir a chamada.

### Avisos e erros

- **Sucesso:** toast do template no canto superior direito, com marcador de cor, título, hora, botão de fechar e a barra fina que marca os 5 segundos até sumir ("Produto enviado para moderação").
- **Erro:** fica onde aconteceu. Erro de campo aparece embaixo do campo, recusa de regra aparece num alerta no topo do formulário e, na tela do produto, também no checklist.
- Os `reasons` são traduzidos por uma tabela em `libs/shared/web-util`. O código desconhecido aparece cru, para não esconder o motivo.

| Código | Mensagem |
|---|---|
| `no-sku` | Adicione ao menos um SKU. |
| `missing-offer:<sku>` | Defina o preço do SKU `<sku>`. |
| `missing-attribute:<código>` | Preencha o atributo `<rótulo>`. |
| `missing-variant-attribute:<sku>:<código>` | Preencha `<rótulo>` no SKU `<sku>`. |
| `duplicate-variant:<sku>` | O SKU `<sku>` repete a variação de outro SKU. |
| `unknown-attribute:<sku>:<código>` | O SKU `<sku>` usa `<rótulo>`, que a categoria escolhida não tem como variação. |
| `category-inactive` | A categoria foi desativada. Escolha outra categoria. |
| `seller-not-eligible` | A loja ainda não foi aprovada no KYB/KYC. |
| `stock-below-reserved` | O estoque físico não pode ficar abaixo do reservado. |
| `sku-has-stock` | Zere o estoque do SKU antes de removê-lo. |
| `promotion-overlap` | Já existe uma promoção nesse período. |
| `promotion-above-list-price` | O preço promocional precisa ficar abaixo do preço "de". |
| `category-cycle` | Uma categoria não pode ficar dentro dela mesma. |
| `category-too-deep` | A árvore passaria de 6 níveis nesse lugar. |
| `attribute-conflict:<código>` | O atributo `<código>` já existe no caminho de destino. |
| `category-has-children` | Exclua ou mova as subcategorias antes de excluir esta categoria. |
| `category-has-products` | A categoria tem produtos, inclusive arquivados. Troque a categoria deles ou desative esta categoria. |

### Conflito de edição

Cada chamada de gravação envia o `If-Match` com a versão lida (ETag), porque o cliente gerado recebe o cabeçalho por chamada, sem interceptor. Quando a gravação volta com 412 ou 409, o formulário mostra o alerta "Outra pessoa alterou este registro depois que você abriu a tela" com o botão **Carregar versão atual**. O que a pessoa digitou continua no formulário até ela clicar, para que possa copiar o que precisa.

### Confirmações

| Ação | Confirmação |
|---|---|
| Arquivar produto, remover SKU, bloquear produto, excluir categoria | Modal com o nome do item e o efeito ("O produto sai da vitrine na hora") |
| Salvar conteúdo ou SKU de produto publicado | Modal: "Salvar devolve o produto à moderação, e ele sai da vitrine até ser aprovado" |
| Pausar, reativar, enviar para moderação | Sem modal. O toast confirma, e a ação contrária desfaz |

### Telas pequenas

- Abaixo de `lg`, o menu lateral vira gaveta, como no template.
- Em telas estreitas, as tabelas rolam na horizontal dentro do card (`table-responsive`), e a coluna Criado só aparece a partir de `xl`. Abaixo de `lg`, a fila mostra a lista e o detalhe em telas separadas, e as categorias empilham a lista e o detalhe. O offcanvas ocupa quase toda a largura.
- Os formulários ficam em uma coluna.

### Datas, moeda e números

- Datas e horas no fuso `America/Sao_Paulo` (ADR 028), no formato `08/11/2026 14:02`.
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
| 14 (+1 ↑)    [⋮] | | 5 (-1 ↓)     [⋮] | | 1 (-3 ↓)     [⋮] | | 1 (-1 ↓)     [⋮] |
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

- **Indicadores:** os 4 `c-widget-stat-a` do dashboard. Para o lojista: produtos ativos, em rascunho, sem estoque e bloqueados, cada um com a variação em relação ao mês anterior, o minigráfico dos últimos meses e o dropdown que leva à lista filtrada. Sem o papel de lojista, o moderador vê a fila e os aprovados, rejeitados e bloqueados do mês, e o admin vê categorias, raízes, inativas e categorias com produtos.
- **Gráfico principal:** o card "Traffic" do template, com o título "Catálogo", o período no subtítulo, o grupo Dia, Mês e Ano (`btn-check` com `btn-outline-secondary`), o botão de baixar os números em CSV e o gráfico de 300px com aprovados (área), enviados à moderação (linha) e a média de aprovados (tracejada). No rodapé, os 5 números com `progress-thin`: ativos, em rascunho, em moderação, bloqueados e pausados, com a porcentagem da loja.
- **Tabela:** a tabela de usuários do dashboard com os produtos atualizados por último: avatar com a inicial e o ponto da cor do status, produto com categoria e SKUs, status, estoque disponível sobre o físico com barra, menor preço vigente e "Atualizado há…".
- Todos os números e séries vêm do endpoint de métricas a construir. Loja `PENDING` ou `REJECTED` ganha também um alerta em **Produtos**: "Sua loja ainda não foi aprovada. Você pode cadastrar produtos, mas eles só são aprovados depois do KYB/KYC".

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
- A aba **Sem estoque** e a busca só aparecem depois das mudanças de backend: hoje a API recusa o filtro `OUT_OF_STOCK` com 400, porque o status é calculado pelo saldo, e não tem busca por texto.
- O rascunho que voltou da moderação mostra o badge **Rejeitado** ao lado de Rascunho, com o motivo no tooltip, quando a API expuser a última rejeição.

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
- **Histórico:** linha do tempo com `GET /products/{id}/history` (transição, de, para, quem, quando e motivo). A rejeição e o bloqueio mostram o motivo em destaque.
- **Checklist de envio:** calculado no front a partir do produto, da categoria e das ofertas, com os motivos da `PublishingPolicy` (`no-sku`, `missing-offer`, `missing-attribute`, `missing-variant-attribute`, `duplicate-variant`, `category-inactive`). Cada item leva à aba e ao campo do problema. O botão **Enviar para moderação** fica ativo mesmo com pendências, e a resposta 422 de `POST /products/{id}/submit` substitui o checklist quando divergir.

Ações por status. Pausar, reativar, enviar e arquivar ficam no menu "..." da lista e da tela do produto, e a primeira delas que o status permitir também vira botão na tela do produto:

| Status | Ações do lojista |
|---|---|
| `DRAFT` | Salvar, adicionar e remover SKU, enviar para moderação, arquivar |
| `PENDING_MODERATION` | Salvar (continua na fila), adicionar SKU, arquivar |
| `ACTIVE` | Salvar (volta à moderação, com confirmação), pausar, arquivar |
| `OUT_OF_STOCK` | Como `ACTIVE`, com o aviso "Sem estoque disponível" na aba de SKUs |
| `PAUSED_BY_SELLER` | Salvar (volta à moderação, com confirmação), reativar, arquivar |
| `BLOCKED` | Só leitura, com o motivo do bloqueio em destaque. Pode arquivar |
| `ARCHIVED` | Só leitura |

A remoção de SKU só aparece em `DRAFT` (`sku-removal-requires-draft`), e reativar exige a loja aprovada.

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

- O botão **Trocar** aparece em todo status editável (fora de `BLOCKED` e `ARCHIVED`) e abre um modal com o `form-select` de caminhos, só com as categorias usáveis e sem a atual.
- Ao escolher o destino, a prévia compara os atributos efetivos das duas categorias com a regra do domínio (`PublishingPolicy.valueViolations`): cada atributo precisa existir na categoria nova com o mesmo papel, do produto ou de variação, e com um valor aceito.
  - **Atributos do produto que somem ou ficam com valor inválido:** são apagados do formulário, e a prévia lista cada um com o valor perdido.
  - **Obrigatórios novos, do produto ou de variação:** não impedem a troca. Entram no checklist de envio.
  - **SKUs com variação que a categoria nova não tem ou não aceita:** impedem a troca, porque a API recusaria com `invalid-attributes` (`unknown-attribute:<sku>:<código>` ou `invalid-attribute:<sku>:<código>`). Em rascunho, a prévia orienta a remover esses SKUs, trocar e criar de novo. Fora do rascunho não dá para remover SKU, e a prévia orienta a escolher uma categoria com as mesmas variações ou a arquivar o produto e criar outro.
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
- **Promoções:** lista das agendadas e vigentes. **+ Agendar** abre um formulário no próprio painel, com início, fim e preço, e o intervalo é semiaberto (`[startsAt, endsAt)`). As datas usam `datetime-local`, lidas no fuso de São Paulo e enviadas em UTC. Cancelar (`DELETE`) pede confirmação e só aparece em promoção não encerrada.
- **Estoque:** `GET` e `PUT /inventory/skus/{skuId}`, com o físico absoluto, como uma contagem da loja. Reservado e vendido são só leitura, e o campo físico avisa quando o valor fica abaixo do reservado.
- Preço e estoque salvam separados, cada um com a sua versão. Mudar oferta ou estoque não devolve o produto à moderação (ADR 014), e o painel não mostra o aviso de moderação aqui.

### 6. Importações

```text
Importações               [Baixar modelo CSV] [Enviar planilha]

Lote de 08/11 14:20   Processando   320 de 1200   [=====     ]
  Criadas 290   Atualizadas 25   Sem mudança 0   Falhas 5
  [Todas] [Só falhas]
  Linha 14   CAM-PP   invalid-row    weight_g inválido
  Linha 15   CAM-PP   group-rejected

Histórico
  07/11 09:02   Concluída   800 linhas   3 falhas   >
```

- O modelo CSV é gerado no front com o cabeçalho exato da ADR 016 e uma linha de exemplo.
- O envio (`POST /products/imports`, `text/csv`, até 1 MB e 5000 linhas) confere o tamanho antes de subir. Uma recusa 400 `invalid-import-file` lista os motivos (`missing-column:<nome>`, `too-many-rows:<máximo>` e outros) traduzidos.
- O andamento consulta `GET /products/imports/{id}` a cada 2 segundos enquanto o status for `PENDING` ou `PROCESSING` e para em `COMPLETED`. A barra usa `processedLines` e `totalLines`.
- O resultado lista as linhas por `lineNumber`, `sellerSku`, `outcome`, `code` e `message`, com o filtro **Só falhas**, e a linha criada leva ao produto (`productId`).
- O histórico depende de `GET /products/imports`. Até lá, a tela mostra só o lote aberto, e a rota `/loja/importacoes/:id` pode ser salva.

### 7. Situação da loja

Callout com o status de `GET /stores/{storeId}/eligibility` e o que ele permite:

| Status | Texto |
|---|---|
| `PENDING` | Sua loja está em análise no KYB/KYC. Você pode cadastrar produtos, mas eles só são aprovados depois da análise. |
| `APPROVED` | Sua loja está aprovada. Produtos aprovados aparecem na vitrine. |
| `REJECTED` | Sua loja não foi aprovada no KYB/KYC. Os produtos não aparecem na vitrine e não podem ser aprovados ou reativados. |

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
- **Ordem:** os mais antigos na fila primeiro. Hoje a fila usa a mesma consulta dos produtos (`ModerationService.java:30`) e sai na ordem do ID. A ordem de chegada pede guardar quando o produto entrou em moderação, porque o `updated_at` muda a cada edição.
- **Em lote:** cada item da fila tem caixa de seleção, e a barra oferece Aprovar, Rejeitar e Bloquear para os marcados (ver [Seleção e operações em lote](#seleção-e-operações-em-lote)). As recusas de cada item, como `seller-not-eligible`, aparecem no alerta do resultado, e esses itens continuam na fila.
- **Aprovar** (`POST .../approve`) não pede confirmação. O toast confirma e o próximo item abre sozinho. A recusa por `seller-not-eligible` ou `product-not-publishable` aparece no alerta do detalhe.
- **Rejeitar** e **Bloquear** abrem um modal com motivos prontos (marcar um ou mais) e um complemento livre, com o contador dos 500 caracteres do `reason`. Rejeitar devolve ao rascunho, e bloquear tira da vitrine na hora, com o texto do efeito no modal.

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

- O `reason` gravado junta os motivos marcados e o complemento ("Categoria incorreta. A gola informada não bate..."). Os textos dos motivos prontos ficam num arquivo de configuração da feature.
- Produto bloqueado sai da fila. **Desbloquear** fica no detalhe do produto em **Produtos de todas as lojas**, para o moderador. Isso depende de liberar a tela para o papel `catalog-moderator`. Até lá, desbloqueia quem tiver os dois papéis.
- A situação da loja no detalhe depende de liberar `GET /stores/{storeId}/eligibility` para o moderador. Hoje ela é restrita a `seller` e `catalog-admin`.

### 9. Categorias

```text
+-------------------------------------+ +------------------------------------------+
| Categorias        [Selecionar] [+ ] | | Camisas [Ativa]        [Editar] [⋮]      |
| 14 categorias | até 6 níveis        | | Vestuário › Camisas | VEST-CAM | nível 2 |
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
- **Detalhe:** card com nome, situação, caminho, código e nível, e dois cards com os atributos próprios (editáveis) e os herdados (`effectiveAttributes`, só leitura, com a categoria de origem), na tabela do template.
- **Ações:** **Editar** é o botão principal. O dropdown traz Mover para…, Nova subcategoria, Desativar ou Ativar e Excluir. **Editar** e **Nova subcategoria** abrem um offcanvas com nome, código e a lista de atributos (código, rótulo, tipo, obrigatório, variante e opções). `PUT /categories/{id}` exige `If-Match`.
- **Mover:** modal com o `form-select` de caminhos, sem a própria categoria, as descendentes, o pai atual e os lugares em que a árvore passaria de 6 níveis, e com a opção "Raiz". As recusas `category-cycle`, `category-too-deep` e `attribute-conflict` aparecem no modal.
- Desativar avisa que a categoria e as subcategorias deixam de aceitar produtos novos.
- **Excluir** vale só para a categoria vazia, sem subcategorias e sem nenhum produto, nem arquivado.
  - **Com subcategorias:** a opção não aparece no dropdown, e o detalhe explica "Para excluir, primeiro exclua ou mova as subcategorias".
  - **Com produtos:** o front não tem como contar os produtos da categoria. A confirmação mostra a recusa `category-has-products` e sugere trocar a categoria dos produtos ou desativar a categoria.
  - **Vazia:** a confirmação avisa que a exclusão é definitiva. Depois, o detalhe passa para a categoria pai.
- **Em lote:** **Selecionar** mostra caixas na lista do nível, com "Selecionar todas deste nível", e a barra oferece Ativar, Desativar e Excluir para as marcadas. A exclusão em lote segue a mesma regra da exclusão de uma categoria e roda das subcategorias para as categorias pai.

### 10. Produtos de todas as lojas

A mesma lista e o mesmo detalhe de **Produtos da loja**, só leitura, com o filtro **Loja** por ID (`storeId`). O contexto que cadastra as lojas ainda não existe, então não há nome de loja para mostrar nem escolher. A coluna mostra o início do UUID com o completo no tooltip.

## Endpoints a construir

O que a API ainda não tem será construído. A prioridade segue a ordem das telas:

| Endpoint ou mudança | Tela que precisa | Prioridade |
|---|---|---|
| Busca `q` em `GET /products`, por título e `seller_sku` | Produtos, busca do cabeçalho | Alta |
| Ordenação `sort` em `GET /products` (`updatedAt`, padrão decrescente, `createdAt` e `title`), com cursor composto pelo valor da coluna e o id, e índices por tenant, loja, status e coluna. Hoje a lista sai na ordem do UUID | Produtos, Produtos de todas as lojas | Alta |
| Fila de moderação em ordem de chegada, guardando quando o produto entrou em moderação. Hoje sai na ordem do UUID | Fila de moderação | Alta |
| `GET /products/imports`, lotes da loja com cursor | Importações | Alta |
| `GET /stores/{storeId}/eligibility` também para `catalog-moderator`, ou a situação da loja no item da fila | Fila de moderação | Alta |
| Métricas do catálogo: contagem por status (da loja, da fila e das categorias) e séries por dia, mês e ano (aprovados, enviados à moderação, ativos, rascunhos, sem estoque, bloqueados e decisões de moderação) | Início | Alta |
| Filtro `OUT_OF_STOCK` em `GET /products`, calculado pelo saldo | Produtos, Início | Média |
| Última rejeição no produto em rascunho, ou filtro de rejeitados | Produtos, Início | Média |
| Oferta e saldo junto dos SKUs do produto, para não fazer 2 chamadas por SKU | Produto, Fila, Início | Média |
| `DELETE /categories/{id}` (papel `catalog-admin`, `If-Match` obrigatório) para a categoria vazia. Recusa com 409 `category-has-children` ou `category-has-products`, e as chaves estrangeiras de `category.parent_id` e `product.category_id` garantem a regra mesmo com gravações simultâneas | Categorias | Média |
| Caminho com os nomes dos ancestrais em `CategoryResponse` | Produto, Categorias, seletores de categoria | Baixa |
| Leitura de produto pelo moderador em **Produtos de todas as lojas** e desbloqueio fora da fila | Moderação | Baixa |
| Códigos de motivo de moderação | Fila | Evolução |
| Endpoints em lote (`POST /products/batch`, `/moderation/products/batch`, `/categories/batch`), com a ação, os ids e o resultado de cada item. Até lá, o front chama a API item a item | Seleção em lote | Evolução |

## Organização do código

Segue a ADR 027. Toda lib leva também a tag `lang:ts`:

| Lib | Tags | Conteúdo |
|---|---|---|
| `libs/catalog-inventory/admin/data-access` | `scope:catalog-inventory`, `layer:data-access`, `framework:angular` | Cliente gerado (já existe) |
| `libs/catalog-inventory/admin/feature-products` | `scope:catalog-inventory`, `layer:feature`, `framework:angular` | Lista, produto, painel do SKU, novo produto, produtos de todas as lojas, e os indicadores, o gráfico e a tabela da página inicial |
| `libs/catalog-inventory/admin/feature-imports` | idem | Importações |
| `libs/catalog-inventory/admin/feature-store` | idem | Situação da loja |
| `libs/catalog-inventory/admin/feature-moderation` | idem | Fila de moderação |
| `libs/catalog-inventory/admin/feature-categories` | idem | Categorias |
| `libs/catalog-inventory/admin/ui` | `scope:catalog-inventory`, `layer:ui`, `framework:angular` | Badge de status do produto, checklist de envio e seletor de categoria (`form-select` com o caminho) |
| `libs/shared/admin-ui` | `scope:shared`, `layer:ui`, `framework:angular` | Paginação por cursor sobre o `c-pagination`, cabeçalho ordenável (a única parte própria), barra de lote, bloco de número, modal de confirmação, alerta de problema RFC 9457 e serviço de toasts sobre o `c-toaster` |
| `libs/shared/web-util` | `scope:shared`, `layer:util`, `framework:none` | Moeda, datas no fuso de São Paulo, tradução de `reasons`, cursor |

- O seletor de categoria fica em `admin/ui` porque três telas usam o mesmo componente (novo produto, trocar categoria e mover categoria), e uma feature não importa outra.
- A página inicial fica no app e monta os indicadores, o gráfico e a tabela que `feature-products` exporta, com o `@coreui/angular-chartjs` e o `chart.js` carregados só nessa rota (ADR 028).
- Uma lib `layer:ui` só depende de `layer:ui` e `layer:util` (`eslint.config.mjs`), então os componentes de `admin/ui` não importam o `data-access`, nem para tipos. Eles usam tipos próprios e recebem tudo por input. O seletor recebe a lista de categorias com o caminho já montado, e cada feature monta essa lista com o `data-access`. O checklist recebe o produto, os atributos efetivos e os SKUs sem oferta. O badge recebe o status.
- As libs Angular não são buildable, e o `vitest-angular` não aceita lib nesse caso no Nx 24.0.4. Lib com teste é gerada com `--unitTestRunner=vitest-analog`, como registra a ADR 027.

## Ordem sugerida

1. `shared/admin-ui` e `web-util`: paginação, cabeçalho ordenável, barra de lote, avisos, confirmação, moeda e datas.
2. Produtos da loja e tela do produto, com conteúdo e histórico.
3. Painel do SKU: preço, promoções e estoque.
4. Situação da loja e Início, com o endpoint de métricas.
5. Fila de moderação.
6. Categorias.
7. Importações.

Os endpoints de prioridade alta entram antes da tela que os usa.

## Fora desta proposta

- O marketplace (`apps/storefront-web`) e a prévia do produto na vitrine.
- Imagens de produto: a API não tem mídia.
- Telas dos outros contextos, que seguem os mesmos padrões quando existirem.
- Tema por tenant (white-label) e paleta própria, que dependem da lib de tokens.
