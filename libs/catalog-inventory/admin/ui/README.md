# catalog-inventory-admin-ui

Biblioteca Angular de apresentação para o catálogo no painel administrativo. Ela fornece componentes pequenos e reutilizáveis para exibir status de produto, selecionar categorias hierárquicas e apresentar pendências de publicação. A feature consumidora busca dados, decide quando gravar e controla a navegação; a UI recebe modelos por inputs e comunica intenções por outputs.

Esta separação segue o ADR 029: a biblioteca não importa `catalog-inventory-admin-data-access`, nem mesmo para tipos. Os tipos de fronteira necessários à apresentação são definidos aqui, e a feature adapta os DTOs da API para esses tipos.

## Visão geral

A lib resolve necessidades visuais e de interação compartilhadas pelas features administrativas do catálogo:

- apresenta status de produto com rótulo e cor consistentes;
- monta caminhos legíveis para categorias e oferece um seletor compatível com formulários Angular;
- converte os caminhos de campo retornados pelo backend em destinos de aba/campo para um checklist de publicação;
- nomeia quem fez cada mudança do histórico do produto: "Você" ou o lado (loja ou moderação), nunca a pessoa.

Ela não implementa cadastro, edição, exclusão, movimentação, moderação ou ações em lote. Também não possui rotas, serviços de API, store compartilhada, carregamento de dados ou regras de persistência. Essas operações ficam em `feature-products`, `feature-categories` e `feature-moderation`; esta lib fornece as peças visuais e os adaptadores puros usados por elas.

## Estrutura da feature/lib

| Arquivo                                                                                                                  | Responsabilidade                                                                              |
| ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| [`src/index.ts`](./src/index.ts)                                                                                         | API pública: reexporta os componentes, tipos, constantes e funções de apoio.                  |
| [`src/lib/product-status/product-status.ts`](./src/lib/product-status/product-status.ts)                                 | Vocabulário de status, ordem canônica, rótulos e aparência do badge.                          |
| [`src/lib/product-status/product-status-badge.component.ts`](./src/lib/product-status/product-status-badge.component.ts) | Badge standalone que projeta o status recebido como texto e cor.                              |
| [`src/lib/product-status/transition-actor.ts`](./src/lib/product-status/transition-actor.ts)                             | `transitionActorLabel()`: autor da mudança do histórico a partir de `byCaller` e `actorSide`. |
| [`src/lib/category-select/category-options.ts`](./src/lib/category-select/category-options.ts)                           | Tipos próprios de categoria/opção, montagem de caminhos, ordenação e busca de descendentes.   |
| [`src/lib/category-select/category-select.component.ts`](./src/lib/category-select/category-select.component.ts)         | Seletor standalone com valor por `model()` e suporte a `ControlValueAccessor`.                |
| [`src/lib/submission/publishing-issues.ts`](./src/lib/submission/publishing-issues.ts)                                   | Tipos próprios para pendências e mapeamento de caminho de campo para aba, SKU e campo.        |
| [`src/lib/submission/submission-checklist.component.ts`](./src/lib/submission/submission-checklist.component.ts)         | Checklist standalone que apresenta as pendências e emite a pendência escolhida.               |

O projeto é uma biblioteca Nx (`catalog-inventory-admin-ui`), com prefixo Angular `app` e tag `layer:ui`. Seus componentes usam CoreUI e ícones; não há camada de serviços ou módulos de rota nesta lib.

## Roteamento e contexto

A biblioteca não declara rotas nem conhece `ActivatedRoute` ou `Router`. A navegação é responsabilidade do consumidor:

- [`feature-products/src/lib/lib.routes.ts`](../feature-products/src/lib/lib.routes.ts) registra a lista da loja em `/loja/produtos` e o detalhe em `/loja/produtos/:id`, com as abas `conteudo`, `skus` e `historico`. Também registra páginas de consulta da plataforma em `/plataforma/produtos` e `/plataforma/produtos/:id`.
- A lista usa `PRODUCT_STATUSES` para compor as abas de status e `ProductStatusBadgeComponent` nas linhas.
- A tela de detalhe usa `SubmissionChecklistComponent`; a própria tela decide se o checklist deve aparecer, qual resultado apresentar e para qual rota navegar.
- A criação e a troca de categoria usam `CategorySelectComponent`. A feature de administração de categorias reutiliza o mesmo seletor para escolher o novo pai de uma categoria.

O seletor não recebe nível de rota nem mantém estado de navegação. O checklist também não navega: seu evento `go` devolve um `ChecklistItem`, e o consumidor traduz esse item para uma rota e foco.

## Componentes principais

### `ProductStatusBadgeComponent`

Recebe `status` obrigatório (`ProductStatus | string`) e calcula a apresentação com `computed()`. O template usa o `c-badge` do CoreUI e sempre mostra o rótulo, não apenas uma cor. O componente não busca nem atualiza o status.

O mapeamento está centralizado em `productStatusLook()` e `productStatusLabel()`. Para status desconhecido, o texto original é preservado e a aparência cai para `secondary`, evitando que um valor novo da API quebre a renderização.

### `CategorySelectComponent`

Renderiza um `<select>` CoreUI a partir de uma lista de `CategoryOption` já preparada pelo consumidor. As opções mostram o caminho completo e marcam explicitamente `usable === false` como “(inativa)”. A lista visível exclui apenas os IDs entregues em `excluded`; o componente não decide se uma categoria é válida para determinada operação.

O valor pode ser ligado por `[(value)]` (`model<string | null>`) ou por formulário reativo (`ControlValueAccessor`). `CATEGORY_ROOT` representa a opção especial “Raiz” quando `rootLabel` é fornecido; a conversão desse sentinela para o contrato da API é feita pela feature que grava a movimentação.

Também expõe `placeholder`, `selectId`, `describedBy`, `invalid` e `disabled`, propaga blur como touched e combina o estado disabled local com o estado disabled vindo do formulário.

### `SubmissionChecklistComponent`

Recebe `issues`, `loading` e `refused`, deriva a lista visível com `checklistItems()` e emite a pendência selecionada por `go`. Enquanto verifica, indica carregamento; sem itens e sem carregamento, apresenta “Tudo pronto para enviar”. O rodapé distingue conferência em andamento, recusa de envio e resultado conferido.

O componente não chama o endpoint de verificação, não determina se o produto pode ser enviado e não apresenta o erro de rede da consulta. A feature consumidora fornece os dados e, quando a consulta falha, renderiza seu componente de erro ao lado do checklist.

## Regras de negócio e domínio

### Status do produto

`ProductStatus` reconhece `DRAFT`, `PENDING_MODERATION`, `ACTIVE`, `OUT_OF_STOCK`, `PAUSED_BY_SELLER`, `BLOCKED` e `ARCHIVED`. `PRODUCT_STATUSES` define a ordem usada pela navegação de filtros da lista.

Conforme o ADR 012 documentado no código, `OUT_OF_STOCK` não é gravado como status independente: é uma apresentação calculada na leitura para um produto `ACTIVE` sem saldo. A lib apenas apresenta o valor recebido; a decisão de domínio e o cálculo pertencem ao backend.

### Hierarquia de categorias

`CategoryNode` descreve o mínimo necessário à apresentação: ID e nome, além de `ancestors`, `level`, `usable` e `childCount` quando fornecidos pelo servidor. O backend é a fonte de verdade para ancestralidade, nível e usabilidade (incluindo a condição dos ancestrais).

`buildCategoryOptions()`:

1. inclui a categoria no caminho formado por seus ancestrais e pela própria categoria;
2. ordena os caminhos por nome em `pt-BR`, desempata por ID e mantém a categoria pai antes de suas descendentes;
3. preserva `level`, `usable` e `childCount` como metadados da opção.

`categoryDescendants()` retorna IDs cuja lista de ancestrais contém a categoria indicada. Na movimentação de categoria, o consumidor combina esses IDs com o ID atual e o pai atual para impedir escolhas inválidas na interface. O backend continua responsável por validar as regras estruturais completas, como ciclos, profundidade máxima e conflitos de atributos.

### Pendências de publicação

`PublishingIssue` mantém a estrutura relevante do servidor: `code`, `params`, `message` e `field`. `issueTarget()` interpreta o caminho `field`:

- `categoryId` e caminhos de conteúdo, como `attributes.<código>`, apontam para a aba `conteudo`;
- `skus`, `skus[<sellerSku>].offer` e `skus[<sellerSku>].variantAttributes.<código>` apontam para `skus`, preservando o SKU e o sufixo do campo quando existem;
- pendências sem caminho e caminhos não reconhecidos ficam na aba `conteudo`.

`checklistItems()` converte cada pendência para o formato visual e usa `translateReason()` de `shared-web-util` para obter o texto apresentado. O caminho da API é transformado em metadados de navegação, não em uma regra local de validação.

## Formulários e interações

Os componentes não definem formulários de produto nem schemas de gravação. O principal contrato com formulários é `CategorySelectComponent`, que pode ser usado com `formControlName` ou `[(value)]`. A validação, mensagens de servidor, estado de salvamento e schema pertencem ao componente consumidor.

Exemplos de integração presentes nas features:

```html
<app-category-select
  [options]="categoryOptions()"
  formControlName="categoryId"
/>

<app-submission-checklist
  (go)="go($event)"
  [issues]="checklistIssues()"
  [loading]="draftCheck.loading()"
  [refused]="store.refusal() !== null"
/>

<app-product-status-badge [status]="product.status ?? ''" />
```

Ao selecionar uma pendência, o consumidor navega para a aba indicada e entrega o item à tela para posicionar o foco no campo. A11y e estado visual do seletor são conectados por ID, `aria-describedby`, `invalid`, disabled e sinalização de blur; labels e mensagens concretas continuam no formulário proprietário.

## Fluxos principais da feature

Como a lib é de UI, os fluxos abaixo descrevem a integração real com as features consumidoras, não operações executadas por ela:

1. **Filtrar e consultar produtos:** a feature lê status da URL, usa `PRODUCT_STATUSES` para validar e ordenar as abas e passa o status da linha ao badge. Busca, paginação e carregamento ficam na lista.
2. **Criar produto:** o modal consumidor carrega todas as páginas de `GET /categories/tree?usable=true`, converte os DTOs em `CategoryNode`, monta opções e usa o seletor no formulário. Após validar o schema e gravar em `POST /products`, emite o produto criado; a feature abre seu detalhe para completar o conteúdo.
3. **Conferir pendências:** no detalhe, alterações do rascunho são verificadas pelo consumidor no servidor. Sem rascunho diferente do produto salvo, a tela usa `publishingIssues` já recebido; com rascunho, a consulta alimenta o checklist.
4. **Resolver uma pendência:** o checklist emite o item. A tela do produto navega para `conteudo` ou `skus`, guarda o foco solicitado e a aba de destino localiza o campo correspondente.
5. **Trocar categoria do produto:** a feature apresenta o seletor com categorias usáveis, consulta o efeito da troca no servidor e mostra atributos removidos, novos atributos obrigatórios e SKUs bloqueadores. A confirmação altera o formulário; a gravação só acontece ao salvar o produto.
6. **Mover categoria na árvore:** a feature de categorias monta as opções da árvore completa, exclui a categoria atual, suas descendentes e o pai atual, e oferece “Raiz” quando aplicável. A lib não salva a movimentação nem substitui as rejeições do backend.
7. **Recusa de envio:** quando a API rejeita o envio, a feature correlaciona os motivos da resposta com pendências conhecidas pelo mesmo código e parâmetros para recuperar o `field`; o checklist pode então oferecer navegação contextual. A recusa é apresentada pelo consumidor até uma nova alteração/atualização substituir esse estado.

## Integração com API e contratos

Não há endpoint chamado diretamente por `catalog-inventory-admin-ui`. A fronteira de integração é formada por tipos e componentes:

| Dado/contrato                 | Forma consumida pela UI                      | Dono da integração                                                                                                                                |
| ----------------------------- | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Status de produto             | `ProductStatus` ou `string`                  | A feature obtém a resposta; o backend calcula status derivados, incluindo `OUT_OF_STOCK`.                                                         |
| Categoria                     | `CategoryNode` e `CategoryOption`            | A feature chama `GET /categories/tree`, pagina a resposta e adapta os DTOs. O seletor recebe opções prontas.                                      |
| Pendência de publicação       | `PublishingIssue`                            | A feature usa `publishingIssues` do produto, a resposta de `POST /products/{id}/check` ou motivos de recusa de envio.                             |
| Mudança de categoria          | `CategoryChangeResponse` no fluxo consumidor | `POST /products/{id}/check` calcula a prévia; a UI apenas apresenta o resultado fornecido.                                                        |
| Autor de mudança do histórico | `actorSide` e `byCaller` de cada mudança     | A feature lê `GET /products/{id}/history`; o servidor informa o lado (`STORE` ou `MODERATION`) e se a mudança foi de quem consulta, sem a pessoa. |

No detalhe do produto, [`productDraftCheck()`](../feature-products/src/lib/product/product-draft-check.ts) espera 400 ms após a última alteração antes de conferir o rascunho em `POST /products/{id}/check`. A operação não persiste dados. O resultado fornece pendências e, quando relevante, o efeito da mudança de categoria. Enquanto a nova resposta chega, a feature mantém o último resultado disponível; em caso de falha, apresenta um estado de erro separado do checklist.

O `data-access` mantém os contratos gerados e os clientes HTTP. A feature adapta respostas com funções como `categoryNode()` e valida formulários contra schemas do contrato; nenhuma dessas dependências vaza para esta lib. Assim, evoluções de DTO não obrigam os componentes a importar serviços ou tipos de transporte.

## Sincronização entre partes da tela

Não existe serviço compartilhado ou store nesta biblioteca. A comunicação é explícita:

- **estado descendente:** `input()` e `model()` entregam status, opções, pendências e flags visuais ao componente;
- **estado derivado:** `computed()` calcula aparência, opções não excluídas, itens do checklist e texto do rodapé;
- **intenção ascendente:** `output()` comunica a pendência escolhida; `model()` sincroniza o valor selecionado;
- **formulários:** `ControlValueAccessor` integra o seletor com forms reativos sem acoplar o componente ao formulário pai.

No detalhe de produto, [`ProductStore`](../feature-products/src/lib/product/product-store.ts) é fornecido pela tela e compartilhado por cabeçalho, abas e painel de SKU. Guarda produto, rascunho, foco e recusa; o componente de checklist permanece sem estado de domínio próprio. A tela coordena `draftCheck.issues()` e a recusa mais recente e só depois da navegação envia o item para foco.

Na lista, a query da URL e os componentes de filtro/tabela são coordenados por `feature-products`; a lib só fornece a enumeração de status e o badge. Na movimentação de categoria, o consumidor fornece opções e exclusões calculadas a partir da árvore atual.

## Padrões arquiteturais importantes

- **Componentes standalone e `OnPush`:** cada componente declara suas dependências de template localmente e usa `ChangeDetectionStrategy.OnPush`.
- **Signals do Angular:** `input`, `output`, `model` e `computed` tornam contratos e estado derivado explícitos, sem serviço de estado global.
- **Tipos de apresentação próprios:** `ProductStatus`, `CategoryNode` e `PublishingIssue` evitam dependência do `data-access` e limitam o acoplamento com DTOs.
- **Adaptadores puros:** `buildCategoryOptions`, `categoryDescendants`, `productStatusLook`, `transitionActorLabel` e `issueTarget` isolam transformações determinísticas, reutilizáveis e sem efeitos de rede.
- **Regra de domínio no servidor:** elegibilidade de categoria, atributos efetivos, pendências de publicação, efeito de mudança de categoria e ações disponíveis vêm do backend; a UI apresenta e direciona o usuário, sem reimplementar políticas divergentes.
- **Concorrência fora da lib:** a biblioteca não conhece `ETag`, `If-Match` nem conflitos. As features consumidoras mantêm a versão e enviam `If-Match` nas gravações; por exemplo, `ProductStore` e as telas de produto coordenam recarga e aviso em conflitos. O `CategorySelectComponent` também é usado numa movimentação versionada da árvore, mas não participa do controle de versão.
- **Design system compartilhado:** CoreUI fornece os elementos visuais e `shared-web-util` traduz razões do servidor; a camada consumidora continua responsável por tratamento de erro, feedback de sucesso e persistência.

## Resumo executivo

`catalog-inventory-admin-ui` concentra três peças transversais do catálogo: status de produto, seleção hierárquica de categoria e checklist de publicação. Sua API pública é pequena, baseada em inputs/outputs e tipos locais; transformações de dados permanecem em funções puras e o seletor integra-se a formulários Angular via `ControlValueAccessor`.

A arquitetura preserva uma fronteira clara: as features controlam rotas, dados, formulários, chamadas HTTP, versões e reações a erros; o backend continua fonte de verdade para regras de domínio; esta lib apresenta os resultados com comportamento consistente e reutilizável.
