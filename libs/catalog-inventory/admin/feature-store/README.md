# Situação da loja — feature de administração

## Visão geral

Esta feature apresenta ao usuário do painel a situação de elegibilidade KYB/KYC da loja associada à sessão. Ela consulta o backend e mostra o status atual, mais os efeitos operacionais sobre publicação e visibilidade dos produtos.

O problema de negócio coberto é dar transparência ao lojista sobre o estado de verificação e sobre o que esse estado significa para o catálogo. A tela é somente de consulta: não inicia nem altera o processo KYB/KYC e não grava dados da loja.

## Estrutura da feature/lib

A biblioteca Nx `catalog-inventory-admin-feature-store` segue a separação de responsabilidades do workspace: a feature compõe a tela e a navegação; a sessão, o cliente HTTP e os componentes genéricos são fornecidos por bibliotecas compartilhadas.

```text
src/
├── index.ts                           # API pública da biblioteca: exporta `routes`
└── lib/
    ├── lib.routes.ts                  # rota interna e carregamento lazy da tela
    ├── store-status-page.component.ts # composição da página e leitura reativa da elegibilidade
    └── store-situation.ts              # apresentação de status e textos de efeito
```

Responsabilidades externas usadas pela implementação:

- `@marketplace/catalog-inventory-admin-data-access`: tipos gerados a partir do OpenAPI e recurso Angular para consultar a elegibilidade.
- `@marketplace/shared-admin-ui`: `AdminSession` e estados visuais compartilhados (vazio, erro e carregamento).
- `@marketplace/shared-web-util`: converte a falha HTTP em `Problem`, consumido pelo estado compartilhado de erro.
- `@coreui/angular`: card, grid, callout e badge usados na apresentação.

O `project.json` classifica a biblioteca como `scope:catalog-inventory`, `layer:feature` e `framework:angular`. Não há serviço de domínio próprio, store separado, modelo persistido, formulário, tabela ou operação de gravação nessa feature.

## Roteamento e contexto

No painel, a feature é montada em `/loja/situacao`. A rota pai `/loja` exige a capacidade `MANAGE_OWN_PRODUCTS`; o segmento `situacao` carrega as rotas exportadas por `@marketplace/catalog-inventory-admin-feature-store` via `loadChildren`. Dentro da biblioteca, a rota vazia carrega `StoreStatusPageComponent` com `loadComponent` e define o título `Situação da loja`.

Não há parâmetro de loja na URL nem fluxo de lista/detalhe. O contexto vem de `AdminSession.storeId`, uma signal compartilhada que representa a loja da sessão. O subtítulo exibe os oito primeiros caracteres desse identificador para contextualizar a tela. Sem `storeId`, nenhum pedido é construído e a página mostra a orientação para solicitar acesso ao administrador.

A rota protege o acesso ao segmento, enquanto o backend também valida autenticação e autorização da API. Embora o contrato do endpoint aceite consulta de administradores e moderadores para qualquer loja, esta tela sempre usa a loja da sessão; não oferece seletor para consultar outra loja.

## Componentes principais

- **`StoreStatusPageComponent`** (`store-status-page.component.ts`): componente standalone com `OnPush`. Injeta a sessão, instancia o recurso reativo da API, deriva subtítulo, problema e situação com `computed` e coordena os estados do template. Compõe card, título, callout e badge.
- **`sellerStatusLook`** (`store-situation.ts`): converte o código do status em rótulo e cor visual sem alterar a regra do domínio.
- **`storeEffects`** (`store-situation.ts`): transforma os dois indicadores de elegibilidade fornecidos pelo servidor em mensagens compreensíveis para o usuário.
- **`routes`** (`lib.routes.ts`): expõe uma única rota lazy, sem rotas de edição ou detalhe.

Os estados de carregamento, vazio e erro vêm de `shared-admin-ui`, evitando que a feature redefina padrões visuais comuns ao painel.

## Regras de negócio e domínio

O status KYB/KYC é apresentado conforme o mapeamento local:

| Código recebido | Rótulo | Cor |
| --- | --- | --- |
| `PENDING` | Em análise | `warning` |
| `APPROVED` | Aprovada | `success` |
| `REJECTED` | Reprovada | `danger` |

Status desconhecido é mostrado literalmente, em `secondary`; status ausente aparece como `Sem situação`, também em `secondary`. Isso mantém a tela tolerante a um novo valor do servidor sem exibir um rótulo incorreto.

O efeito de negócio não é deduzido do status. O servidor envia `canPublish` e `visibleInStorefront`, e a interface produz uma mensagem independente para cada campo:

- `canPublish` verdadeiro: produtos aprovados vão para a vitrine e produtos pausados podem ser reativados.
- `canPublish` falso: produtos podem ser cadastrados, mas só são aprovados ou reativados após a aprovação KYB/KYC.
- `visibleInStorefront` verdadeiro: produtos ativos aparecem na vitrine; falso: não aparecem.

No tipo OpenAPI gerado, esses booleanos são opcionais. A tela os consome por avaliação booleana direta, então campo ausente segue o ramo falso. Não há regra local que imponha bloqueio, aprove produto, altere status ou mude sua visibilidade: essas decisões pertencem ao backend e aos fluxos de catálogo.

## Formulários e interações

Não há formulário nem edição de dados nesta biblioteca. A única ação disponível é **Tentar de novo**, renderizada pelo estado de erro compartilhado; ela chama `eligibility.reload()` para repetir a consulta. Não há confirmação, exclusão, movimentação, ação em lote, validação de campos ou conflito de edição neste escopo.

## Fluxos principais da feature

1. O roteador autoriza a entrada em `/loja` pela capacidade `MANAGE_OWN_PRODUCTS` e carrega sob demanda a rota `/situacao`.
2. A página lê `storeId` da signal `AdminSession`.
3. Se a sessão não possuir loja, exibe o estado vazio e não consulta a API.
4. Com `storeId`, `getStoreEligibilityResource` recebe `{ path: { storeId } }` e executa a leitura. Enquanto não há valor, a tela exibe o estado de carregamento.
5. Em caso de sucesso, a página combina o rótulo/cor de `status` com as mensagens derivadas de `canPublish` e `visibleInStorefront` e renderiza o callout.
6. Em caso de falha, `toProblem` adapta o erro para o contrato visual comum. O usuário pode repetir a leitura; sucesso subsequente substitui o estado de erro pelo resultado atual.

Como é uma consulta sob demanda, a interface representa o resultado carregado pelo recurso; não há sincronização por websocket, polling explícito ou atualização de outra tela implementada nesta biblioteca.

## Integração com API e contratos

A única integração desta página é:

```http
GET /stores/{storeId}/eligibility
```

O `storeId` é um UUID obrigatório no caminho. A operação `getStoreEligibility` está definida em `contracts/catalog-inventory/openapi.json`, é gerada pelo Hey API e é exposta como `getStoreEligibilityResource` em `catalog-inventory-admin-data-access`. A base URL do cliente é `/api/catalog-inventory`; o proxy/ingress do painel encaminha esse prefixo ao serviço de catálogo.

A resposta `EligibilityResponse` pode conter:

| Campo | Tipo | Uso na tela |
| --- | --- | --- |
| `storeId` | UUID | Não é usado para renderização; o contexto já veio da sessão. |
| `status` | `PENDING \| APPROVED \| REJECTED` | Seleciona o rótulo e a cor do badge/callout. |
| `canPublish` | boolean | Alimenta a mensagem sobre aprovação e reativação de produtos. |
| `visibleInStorefront` | boolean | Alimenta a mensagem de visibilidade dos produtos ativos. |

O contrato declara respostas `200` com `application/json`, erros de cliente `4XX` e erros de servidor `5XX` como `application/problem+json` (RFC 9457), além de `401` para token inválido/ausente e `403` para falta de permissão. Não há mutação, `If-Match`, versão de recurso ou fluxo de conflito de edição nesta tela; portanto, concorrência de escrita não se aplica.

## Sincronização entre partes da tela

`AdminSession.storeId` é a fonte compartilhada do contexto. O callback passado ao resource lê essa signal: quando não existe identificador, retorna `undefined` e a requisição fica inativa; quando existe, fornece os parâmetros de caminho. A própria dependência reativa permite ao resource acompanhar a mudança do contexto sem copiar o identificador para um estado local independente.

O resource é a única fonte do dado remoto. `problem` e `situation` são projeções `computed` do estado e valor do mesmo resource, e o template escolhe entre ausência de loja, erro, resultado ou carregamento. Como não existe uma listagem ou tela de detalhe separada nessa feature, não há barramento de eventos nem sincronização cruzada entre componentes.

## Padrões arquiteturais importantes

- **Feature lazy-loaded:** rotas e componente são carregados sob demanda, reduzindo o acoplamento com o bundle inicial do painel.
- **Cliente tipado gerado do OpenAPI:** a feature reutiliza contrato, tipos e wrapper Angular existentes, sem implementar HTTP manualmente.
- **Signals e derivação declarativa:** signals de sessão/resource alimentam `computed`; não há estado duplicado nem efeitos imperativos para sincronizar texto e status.
- **`OnPush` e componente standalone:** dependências explícitas no componente e atualização alinhada aos valores reativos.
- **Regra do servidor separada da apresentação:** o status só determina sua aparência; permissões de publicação e visibilidade vêm de campos calculados pelo backend.
- **Estados compartilhados de tela:** vazio, carregamento, erro e retry seguem os componentes comuns do admin.
- **Falhas explícitas e recuperáveis:** a página converte o erro para `Problem` e oferece nova tentativa em vez de mascarar a falha como uma elegibilidade válida.

## Resumo executivo

A feature `/loja/situacao` é uma tela pequena e somente de leitura para informar o status KYB/KYC da loja da sessão e o efeito atual sobre publicação e vitrine. Sua organização mantém navegação e composição na camada de feature, contrato/API no data-access e sessão/estados genéricos em bibliotecas compartilhadas. O backend é a autoridade sobre elegibilidade; a UI limita-se a apresentar o status e os indicadores retornados, com estados vazios, de carregamento e de erro tratados explicitamente.
