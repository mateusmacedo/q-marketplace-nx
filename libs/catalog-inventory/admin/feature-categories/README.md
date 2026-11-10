# catalog-inventory-admin-feature-categories

Tela **Plataforma > Categorias** (`/plataforma/categorias`), da proposta de design do painel e das ADRs que regem a gestão de taxonomia. Exporta `routes`, que o app carrega sob demanda atrás da capacidade `MANAGE_TAXONOMY`.

Este guia consolida a implementação funcional da feature, com foco na arquitetura, na lógica de interação, no fluxo de dados e no comportamento esperado da tela. Ele não inclui testes.

## 1. Visão geral

A feature cobre a gestão da árvore de categorias do tenant em um painel administrativo. A experiência principal é dividir a tela entre:

- lista por nível da árvore
- detalhe da categoria selecionada ao lado
- formulários de criação/edição
- ações em lote em múltiplas categorias
- modal de mover categoria para outro pai

A funcionalidade central é a gestão funcional da taxonomia, sem deixar a árvore quebrar por ciclos, níveis inválidos ou conflitos de versão.

## 2. Estrutura dos arquivos

Os principais pontos de implementação são:

- [lib.routes.ts](./src/lib/lib.routes.ts): definição das rotas da feature
- [category-events.ts](./src/lib/category-events.ts): sincronização entre lista e detalhe
- [category-model.ts](./src/lib/category-model.ts): regras de negócio e utilitários de domínio
- [category-form.component.ts](./src/lib/category-form.component.ts): form de criação/edição
- [categories-page.component.ts](./src/lib/categories-page.component.ts): lista por nível e batch actions
- [category-page.component.ts](./src/lib/category-page.component.ts): detalhe da categoria e ações individuais
- [category-move-modal.component.ts](./src/lib/category-move-modal.component.ts): modal de mover para outro pai
- [category-attributes-table.component.ts](./src/lib/category-attributes-table.component.ts): tabela de atributos

## 3. Roteamento e contexto da feature

A rota principal em [lib.routes.ts](./src/lib/lib.routes.ts) define:

- rota raiz `''`: página principal da árvore
- serviço `CategoryEvents` no provider da rota raiz
- rota filha `:id`: detalhe da categoria selecionada
- `leaveGuard` para evitar sair com alterações pendentes

Essa organização permite que a feature tenha uma UI composta por duas partes do mesmo painel:

- lista por nível na esquerda
- detalhe da categoria na direita, em desktop
- empilhado em telas menores

A lista usa `?parentId=` para definir o nível atual. Sem pai, a tela mostra as categorias raiz. Com pai, mostra as subcategorias do nível correspondente.

## 4. Lista por nível

A listagem está em [categories-page.component.ts](./src/lib/categories-page.component.ts).

### 4.1 O que ela mostra

A tela lista categorias por nível com:

- nome da categoria
- código
- número de atributos próprios
- badge “Inativa” quando necessário
- link para abrir subcategorias quando `childCount > 0`
- breadcrumb para voltar no nível da árvore

### 4.2 Carregamento

A listagem usa `level` como `resource`, carregando o nível com:

- `loadLevel(parentId)`
- `loadAllPages(...)`
- `listCategories({ query: { parentId, cursor } })`

Isso garante que todas as páginas do backend sejam lidas, mesmo com paginação.

### 4.3 Breadcrumb e navegação

O breadcrumb usa:

- `parent` do nível atual para montar a trilha
- `categoryTrail()` e `categoryPath()` em [category-model.ts](./src/lib/category-model.ts)

Isso permite navegar pela árvore de forma consistente, sem depender de uma estrutura fixa em memória.

### 4.4 Criação no nível correto

Os botões para criar são:

- “Categoria raiz” quando não há pai
- “Subcategoria” quando há um nível aberto

A ação chamará o `CategoryFormComponent` com o alvo correto: raiz ou subcategoria do pai do nível atual.

## 5. Detalhe da categoria

O detalhe está em [category-page.component.ts](./src/lib/category-page.component.ts).

### 5.1 Dados exibidos

A página exibe:

- nome e status ativo/inativo
- código
- nível
- caminho completo da categoria
- atributos próprios
- atributos herdados
- ações do menu e também botão de edição

### 5.2 Atributos herdados

A lógica em [category-model.ts](./src/lib/category-model.ts) monta `AttributeRow` para atributos próprios e herdados.

Para herdados, o código usa:

- `ancestors`
- `effectiveAttributes`
- `categoryId` da origem do atributo

Assim, a tela mostra corretamente de qual categoria um atributo foi herdado.

### 5.3 Ações do detalhe

O menu oferece:

- Editar
- Mover para…
- Nova subcategoria
- Ativar / Desativar
- Excluir

Toda gravação passa pelo `If-Match` da versão atual da categoria, preservada pelo `ETag` lido no backend.

## 6. Formulário de categoria

O formulário está em [category-form.component.ts](./src/lib/category-form.component.ts).

### 6.1 Formação do formulário

Ele cobre três cenários:

- criação de categoria raiz
- criação de subcategoria
- edição de categoria existente

Os campos são:

- nome
- código
- atributos próprios

Cada atributo pode ter:

- código
- rótulo
- tipo
- obrigatório
- variante do SKU
- opções, quando o tipo for `OPTION`

### 6.2 Validação

As regras de validação vêm do contrato e são montadas via `fieldRules(...)`:

- `CREATE_RULES`
- `REVISE_RULES`
- `ATTRIBUTE_RULES`

Isso faz o frontend respeitar os limites do contrato, sem duplicar a lógica de negócio manualmente.

### 6.3 Criação

No save do tipo create, o método chama `createCategory`:

- `code`
- `name`
- `attributes`
- `parentId`, quando houver

O servidor responde `201` com a categoria criada (`CategoryResponse`). O formulário lê o `id` dessa resposta, e a lista abre o nível do pai com o detalhe dela, sem reler o nível para achá-la; sem o `id`, abre só o nível do pai.

### 6.4 Edição

No save do tipo edit, o método chama `reviseCategory` com:

- `name`
- `attributes`
- `active`
- `If-Match` do ETag lido

### 6.5 Conflito de edição

Quando o backend responde com `412` ou `409`, a UI entra no fluxo de conflito:

- oferece “Carregar versão atual”
- lê a categoria novamente do servidor
- repopula o formulário
- evita sobrescrever alterações concorrentes

### 6.6 Erros de backend

A lógica do form separa:

- erro de campo específico
- erro geral do problema
- caso de código duplicado, que aparece diretamente no campo `code`

Isso melhora a experiência do usuário e reduz a fricção do formulário.

## 7. Mover categoria

O modal está em [category-move-modal.component.ts](./src/lib/category-move-modal.component.ts).

### 7.1 Carregamento da árvore

Ele usa `listCategoryTree` para montar a árvore completa do tenant e gerar opções válidas de destino.

### 7.2 Validações de integridade

A lista de destinos remove:

- a própria categoria
- descendentes da categoria
- o pai atual

Isso evita:

- mover para si mesmo
- criar ciclo na árvore
- movimentar categoria para um lugar inválido

### 7.3 Requisição

A ação chama `moveCategory` com:

- novo `parentId`
- `null` para a raiz quando o destino for raiz
- `If-Match` da versão atual

Se houver conflito de versão, o modal recarrega a categoria e preserva o destino escolhido.

## 8. Lote de categorias

A parte de lote está em [categories-page.component.ts](./src/lib/categories-page.component.ts).

### 8.1 Modo de seleção

O usuário ativa “Selecionar” para marcar várias categorias da lista.

Há também a opção “Selecionar todas deste nível”.

### 8.2 Ações disponíveis

A barra de lote oferece:

- Ativar
- Desativar
- Excluir

As ações são definidas em [category-model.ts](./src/lib/category-model.ts) via `CATEGORY_BATCH_ACTIONS`.

### 8.3 Elegibilidade da categoria

A regra `acceptsCategoryAction(category, action)` decide se cada categoria participa de cada ação.

Isso garante que:

- categoria ativa pode ser desativada
- categoria inativa pode ser ativada
- ações de lote respeitam o estado da categoria

### 8.4 Processamento em grupos

A operação usa `runBatch()` e `batchCategories`, quebrando os itens em grupos do tamanho máximo do contrato.

Isso é controlado por `categoryBatchSize()`, que lê o `maxItems` do schema do contrato.

### 8.5 Tratamento de falhas parciais

A execução em lote não exige que tudo seja bem-sucedido para ser aceita.

Ela registra:

- itens processados com sucesso
- itens rejeitados e resultado do motivo do servidor
- problema geral no lote

A seleção é atualizada de acordo com os resultados, e a lista/detalhe são recarregados.

### 8.6 Excluir em lote

A exclusão em lote trata de uma regra importante: o backend exclui categorias “das subcategorias para as categorias pai”.

Então, quando a seleção contém um ramo inteiro e vazio, ele pode sair em uma chamada só. Se a categoria ainda tem subcategorias ou produtos, ela volta como recusada com o motivo.

## 9. Política de exclusão e recusa por produtos

A categoria só deve aparecer para excluir quando `childCount === 0`, e isso é validado pela lógica `canDelete()` em [category-model.ts](./src/lib/category-model.ts).

A recusa de produto, quando a categoria não pode ser removida porque ainda contém produtos, é tratada pelo servidor via problema `category-has-products`. Ao detectar a recusa, o front oferece a alternativa de desativar a categoria em vez de excluir.

Essa abordagem melhora a UX e respeita a regra de negócio da taxonomia.

## 10. Sincronização entre lista e detalhe

A comunicação entre a lista e o detalhe é feita por [category-events.ts](./src/lib/category-events.ts).

Esse serviço mantém o estado compartilhado da tela:

- `listStamp`: lista mudou
- `detailStamp`: detalhe mudou
- `created`: categoria recém-criada para revelar na lista
- `openId`: categoria aberta no detalhe
- `gone`: categoria removida ou movida para outro contexto

Esse mecanismo evita acoplamento direto entre componentes e garante que:

- a criação de categoria abra o detalhe automaticamente
- a exclusão limpe a seleção em lote
- uma ação em lote recarregue a tela de forma coerente
- a categoria selecionada fique destacada corretamente

## 11. Mapa de endpoints e integrações

A feature faz uso de integrações principais com o data-access e com a API:

- `GET /categories` para listar por nível
- `GET /categories/{id}` para carregar o detalhe
- `POST /categories` para criar categoria
- `PUT /categories/{id}` para atualizar categoria e estado ativo
- `DELETE /categories/{id}` para excluir
- `GET /categories/tree` para carregar a árvore para mover
- `PUT /categories/{id}/parent` para mover para outro pai
- `POST /categories/batch` para ações em lote
- `GET /stats/categories` para dados de resumo

O uso desses endpoints fica encapsulado pelos métodos do módulo de data-access e pelos utilitários em [category-model.ts](./src/lib/category-model.ts).

## 12. Padrões de implementação que vale destacar

### 12.1 `If-Match` em gravações

Toda operação de escrita usa a versão atual da categoria, lida previamente do backend. Isso é um padrão importante para evitar sobrescrita concorrente de dados.

### 12.2 Reatividade com signals e resources

A feature usa sinais reativos de forma consistente para controlar estado da tela e sincronizar atualizações. Isso reduz código imperativo e melhora a previsibilidade da interface.

### 12.3 Tratamento de conflitos e erros

O front trata de forma explícita:

- conflitos de versão
- erros de contrato
- rejeições de negócio
- problemas de lote
- mensagens do servidor no lugar correto

### 12.4 Separação de responsabilidade

A arquitetura separa claramente:

- rotas e navegação
- regras e mapeamento de domínio
- renderização de dados
- formulário de criação/edição
- ações em lote
- ações em detalhe
- modal de mover categoria

Isso torna a feature fácil de evoluir e manter.

## 13. Fluxos principais resumidos

### Criação de categoria raiz

1. usuário clica em “Categoria raiz”
2. form abre
3. usuário envia nome, código e atributos
4. chamada `createCategory`
5. lista recarrega
6. nova categoria é revelada e o detalhe abre automaticamente

### Criação de subcategoria

1. usuário entra em um nível da árvore
2. clica em “Subcategoria”
3. form recebe o pai do nível atual
4. cria categoria com `parentId`
5. lista e detalhe sincronizam

### Edição

1. usuário abre a categoria no detalhe
2. clica em “Editar”
3. form repopula valores
4. chama `reviseCategory` com `If-Match`
5. a categoria atualizada é exibida no detalhe e a lista atualiza

### Mover

1. usuário abre modal de mover
2. árvore completa é carregada
3. destino é escolhido
4. `moveCategory` envia o novo pai
5. a categoria muda de nível e o detalhe/lista refletem a nova árvore

### Excluir

1. usuário confirma exclusão
2. chama `deleteCategory` com `If-Match`
3. categoria sai da árvore
4. rota é atualizada para pai/raiz
5. seleção em lote e eventos globais são atualizados

### Lote

1. usuário entra em seleção múltipla
2. escolhe ação em lote
3. o sistema valida itens elegíveis
4. divide em grupos de acordo com o contrato
5. executa `batchCategories`
6. registra sucesso e falhas
7. recarrega lista e detalhe

## 14. Conclusão

A feature de categorias foi implementada como uma gestão de taxonomia completa, com foco em:

- organização hierárquica da árvore
- integridade da estrutura
- edição segura com versionamento
- UX de erro e conflito previsível
- suporte para lote em múltiplos itens
- sincronização de estado entre lista e detalhe

A organização atual facilita a compreensão da funcionalidade, a manutenção futura e a evolução do painel administrativo do marketplace.
