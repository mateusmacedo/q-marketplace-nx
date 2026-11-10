# Importação de produtos da loja

## Visão geral

Esta biblioteca implementa a área administrativa de importação de produtos por arquivo CSV. Ela permite baixar um cabeçalho-modelo, enviar uma planilha, consultar o histórico de lotes e acompanhar o processamento assíncrono até obter o resultado por linha.

O problema de negócio é permitir a criação de produtos com vários SKUs e a atualização em lote de preço e estoque sem submeter cada alteração manualmente. A interface cria um lote; as regras de conteúdo e de domínio são aplicadas pelo backend e cada linha recebe um desfecho independente. A feature não oferece edição ou exclusão de lotes, edição individual de produtos ou movimentação de registros.

O fluxo é assíncrono: o envio aceito não significa que todas as linhas foram importadas com sucesso. O estado do lote pode terminar em `COMPLETED` mesmo com linhas `FAILED`; as contagens e os resultados detalhados devem ser consultados separadamente.

## Estrutura da feature/lib

A biblioteca é uma feature Angular standalone, identificada no Nx como `catalog-inventory-admin-feature-imports`, com export público de rotas por [`src/index.ts`](./src/index.ts). O projeto declara as tags `scope:catalog-inventory`, `layer:feature`, `framework:angular` e `lang:ts` em [`project.json`](./project.json).

| Arquivo                                                                      | Responsabilidade                                                                                                 |
| ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| [`lib.routes.ts`](./src/lib/lib.routes.ts)                                   | Define as rotas locais da listagem e do detalhe, ambas carregadas sob demanda.                                   |
| [`imports-page.component.ts`](./src/lib/imports-page.component.ts)           | Tela de entrada: download do modelo, seleção/envio do CSV, lote acompanhado, histórico e paginação do histórico. |
| [`import-batch-page.component.ts`](./src/lib/import-batch-page.component.ts) | Página profunda de resultado; lê o `:id` da rota e apresenta o lote.                                             |
| [`import-batch.component.ts`](./src/lib/import-batch.component.ts)           | Bloco reutilizado de acompanhamento: status, progresso, contagens, alertas e linhas.                             |
| [`import-lines.component.ts`](./src/lib/import-lines.component.ts)           | Tabela local das linhas processadas, filtro de falhas e paginação em memória.                                    |
| [`import-tracking.ts`](./src/lib/import-tracking.ts)                         | Cria os sinais de consulta, erro e carregamento e controla o polling de um lote.                                 |
| [`import-presentation.ts`](./src/lib/import-presentation.ts)                 | Centraliza rótulos/cores de status e resultado, progresso e apresentação das mensagens por linha.                |
| [`import-template.ts`](./src/lib/import-template.ts)                         | Gera e baixa o CSV de cabeçalho, sem linhas de dados.                                                            |

A feature consome os tipos e operações gerados em [`admin/data-access`](../data-access/src/generated/index.ts). O adaptador HTTP e as regras de aplicação ficam fora da UI: o endpoint está em [`ProductImportResource`](../../../../apps/catalog-inventory-service/src/main/java/dev/marketplace/cataloginventory/api/ProductImportResource.java), e o processamento por linha em [`ProductImportService`](../../../../libs/catalog-inventory/application/src/main/java/dev/marketplace/cataloginventory/application/ProductImportService.java), acionado pelo worker do serviço.

## Roteamento e contexto

O app administrativo registra `/loja/importacoes` por `loadChildren` e protege a área `/loja` com `capabilityGuard('MANAGE_OWN_PRODUCTS')` em [`app.routes.ts`](../../../../apps/admin-web/src/app/app.routes.ts). A biblioteca exporta as seguintes rotas:

| URL                     | Tela              | Comportamento                                                      |
| ----------------------- | ----------------- | ------------------------------------------------------------------ |
| `/loja/importacoes`     | Listagem e envio  | Mantém `cursor` e `limit` da página do histórico nos query params. |
| `/loja/importacoes/:id` | Resultado do lote | Consulta o lote pelo identificador da rota.                        |

A rota local define títulos para a navegação e carrega cada página com `loadComponent`. A página de detalhe converte o `paramMap` da rota em um signal por `toSignal`; a listagem usa `queryParamMap` como fonte de `cursor` e `limit`. Avançar no histórico atualiza o cursor; alterar o tamanho da página limpa o cursor e faz navegação relativa com merge dos demais query params.

Links de uma linha do histórico e do lote acompanhado abrem `/loja/importacoes/:id`. O detalhe oferece retorno relativo para `/loja/importacoes`. As linhas com `productId` também ligam à tela correspondente em `/loja/produtos/:id`.

## Componentes principais

- **`ImportsPageComponent`**: organiza a tela em três áreas: ações de arquivo, cartão do lote acompanhado (quando existe) e histórico paginado. O histórico é fornecido por `listProductImportsResource`; a tabela apresenta data, status, linhas, falhas e acesso ao resultado.
- **`ImportBatchPageComponent`**: compõe o cabeçalho da página de detalhe e entrega o ID à visualização compartilhada.
- **`ImportBatchComponent`**: recebe um `importId`, cria o rastreador e apresenta status, barra de progresso, quantidades de criadas/atualizadas/sem mudança/falhas e a tabela. É compartilhado entre a listagem e a página de detalhe; a entrada `linkToResult` altera o título para link quando usada na listagem. A saída `settled` informa uma única vez que aquele lote chegou a um status terminal.
- **`ImportLinesComponent`**: recebe as linhas disponíveis na resposta do lote e deriva uma visão filtrada e paginada, sem solicitar páginas ao servidor.
- **Estados compartilhados**: `LoadingStateComponent`, `ErrorStateComponent`, `EmptyStateComponent`, `ProblemAlertComponent` e componentes visuais CoreUI são usados para carregamento, rejeição de leitura, ausência de dados e erros de envio.

Os componentes usam `ChangeDetectionStrategy.OnPush`, templates inline e componentes standalone. Não há formulário de edição de metadados do lote: a interação principal é selecionar um `File` e enviar seu conteúdo.

## Regras de negócio e domínio

### Contrato do CSV

O arquivo deve ser CSV RFC 4180 em UTF-8, com BOM opcional e cabeçalho exato, nesta ordem:

```text
product_ref,category_code,title,description,attributes,seller_sku,variant_attributes,physical,height_cm,width_cm,length_cm,weight_g,currency,list_price,sale_price,on_hand
```

Atributos são pares `chave=valor` separados por `;`; números usam ponto decimal; `physical` usa `true` ou `false`. O limite máximo de linhas de dados é configurado no backend. O cabeçalho do modelo no frontend é uma cópia local de `ProductCsv.COLUMNS` (ADR 031); mudanças de contrato precisam manter esses dois lados alinhados.

A seleção de arquivo restringe o diálogo a `.csv` e `text/csv`, mas não valida o conteúdo no navegador. A API valida codificação, sintaxe, cabeçalho, arquivo vazio, existência de linhas de dados e limite de linhas. Falhas nessa validação recusam o arquivo inteiro com HTTP 400 e detalhes estruturados; validações de domínio de produto/SKU ocorrem durante o processamento das linhas.

### Semântica das linhas

- **SKU já existente na loja**: a importação altera somente preço e estoque. Se nenhum dos dois mudar, o resultado é `UNCHANGED`; se houver alteração, `UPDATED`. Produto arquivado pode ser recusado por transição inválida.
- **SKU novo**: precisa de `product_ref`; as linhas com a mesma referência são agrupadas para criar um produto em rascunho com seus SKUs. Para o produto novo, categoria, título e indicador de SKU físico são obrigatórios.
- **Integridade do grupo de produto novo**: todas as linhas agrupadas devem concordar em categoria, título, descrição, atributos e moeda. Divergência em qualquer desses campos, linha inválida ou categoria inexistente recusa o grupo inteiro; os SKUs do mesmo produto não são parcialmente aceitos quando o grupo falha na preparação.
- **SKU repetido no mesmo arquivo**: a partir da segunda ocorrência, a linha falha com `DUPLICATE_SELLER_SKU`; somente a primeira linha daquele SKU é importada.
- **Resultado por linha**: `CREATED`, `UPDATED`, `UNCHANGED` ou `FAILED`. Uma recusa de negócio é reportada como falha de linha e não deve ser confundida com erro de transporte ou com recusa do arquivo inteiro.

O serviço de aplicação é a autoridade dessas regras; a tela não tenta duplicar validação do domínio. Cada linha retorna, quando disponível, `sellerSku`, `productId`, código, mensagem e `reasonDetails`. A tabela apresenta mensagem e razões legíveis, omite o código cru e usa uma mensagem genérica quando a linha falhou sem detalhes. Quando há `productId`, o SKU/produto é navegável.

## Formulários e interações

1. **Baixar modelo**: `templateCsv()` concatena os nomes das colunas com CRLF. `downloadTemplate()` cria um `Blob` `text/csv;charset=utf-8`, dispara o download e revoga a URL temporária após o ciclo do clique.
2. **Escolher arquivo**: o botão aciona o input oculto. Durante o envio, `sending` desabilita o botão e mostra estado de progresso. O valor do input é limpo ao tratar o evento para permitir selecionar novamente o mesmo arquivo.
3. **Filtrar resultado**: os controles “Todas” e “Só falhas” alternam a visão local; a troca reinicia o deslocamento para o início.
4. **Paginar linhas**: a tabela começa com 50 linhas por página, e o seletor da paginação troca para 20 ou 100. Os cursores desta tabela são offsets locais convertidos para strings, diferentes do cursor opaco da API do histórico.
5. **Paginar histórico**: o cursor opaco e o limite vêm da URL e são passados ao recurso de listagem. A paginação é desabilitada enquanto o recurso está carregando.
6. **Tentar novamente**: falhas de leitura do histórico ou do lote têm ação explícita de retry. Um erro de polling não é repetido silenciosamente: o alerta permanece até o usuário selecionar “Tentar de novo”.

## Fluxos principais da feature

### Enviar e acompanhar uma planilha

1. O usuário baixa o modelo, preenche e seleciona o CSV.
2. `ImportsPageComponent.send()` limpa o input, evita envio duplicado, limpa o erro anterior e chama `importProducts({ body: file })`.
3. `apiData()` transforma uma resposta de erro em `Problem`. Se o envio for aceito, a interface exibe toast de sucesso, extrai o ID do cabeçalho `Location`, define o lote atual e recarrega o histórico.
4. A API retorna HTTP 202: isso confirma o registro do lote, não a conclusão do trabalho. Se não houver um `Location` utilizável, a tela ainda pode escolher o lote mais recente em andamento da página carregada do histórico.
5. `ImportBatchComponent` consulta o andamento com `lines=false`, sem o resultado de cada linha. Enquanto o status for `PENDING` ou `PROCESSING`, agenda novas leituras, que esperam a aba voltar quando ela está oculta. Em `COMPLETED`, lê o lote uma vez com as linhas, para o polling e emite `settled`; a listagem então recarrega o histórico para atualizar contagens/status. A tabela de linhas aparece com essa leitura final.
6. As linhas disponíveis são mostradas em ordem de arquivo, com filtro de falhas e links para produtos conhecidos.

### Abrir um lote existente

Ao abrir `/loja/importacoes/:id`, a página lê o ID e monta o mesmo `ImportBatchComponent`. A primeira consulta lê o andamento com `lines=false`; as linhas chegam com a leitura do status terminal, feita na hora se o lote já terminou. O estado de carregamento é mostrado até haver resposta. Se a leitura falhar antes do primeiro resultado, aparece um estado de erro com retry. Se falhar durante o acompanhamento, o relatório anterior continua disponível junto ao alerta de falha de atualização e ao botão de retry.

### Ver o histórico

O recurso de listagem busca lotes da loja em ordem do mais recente para o mais antigo, sem transferir as linhas individuais. A tela destaca o lote em andamento mais recente encontrado na página atual para exibir seu progresso continuamente. Outros lotes em andamento aparecem como resumo no histórico; ao abrir seu detalhe, passam a ter acompanhamento completo.

## Integração com API e contratos

As operações são geradas a partir do contrato OpenAPI em `@marketplace/catalog-inventory-admin-data-access` e usam autenticação Bearer. O serviço também exige `MANAGE_OWN_PRODUCTS`; o escopo do seller/loja é aplicado no backend.

| Operação                                              | Contrato                                                                                                                                                                                           | Uso na feature                                                                                         |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `POST /products/imports`                              | `Content-Type: text/csv`; corpo binário (`File`); HTTP 202 com `Location` apontando para o lote. Arquivo rejeitado: HTTP 400 `invalid-import-file`, com razões estruturadas.                       | Envio pelo formulário; ID do lote é obtido de `Location`.                                              |
| `GET /products/imports?cursor={cursor}&limit={limit}` | HTTP 200 com `{ items, nextCursor }`; itens de resumo não incluem linhas. Lotes são ordenados do mais recente ao mais antigo. O cursor é opaco; o limite do serviço deve estar entre 1 e 100.      | Recurso Angular reativo ligado a query params da URL.                                                  |
| `GET /products/imports/{id}`                          | HTTP 200 com ID, status, `totalLines`, `processedLines`, contagens, timestamps e resultados de linhas já processadas, em ordem; com `lines=false`, `lines` vem nulo e o servidor não lê as linhas. | Consulta inicial, polling e retry com `lines=false`; leitura final, com as linhas, no status terminal. |

`ImportSummaryResponse` alimenta o histórico e `ImportResponse` o detalhe/acompanhamento. Os resultados de linha carregam `lineNumber`, `sellerSku`, `outcome`, `productId` opcional e dados de recusa (`code`, `message`, `reasonDetails`). A interface compara `processedLines` e `totalLines` recebidos para exibir o progresso; `processedLines` representa as linhas que já têm resultado.

Erros HTTP e de transporte são normalizados por `toProblem`/`apiData` do `shared/web-util`. No upload, `ProblemAlertComponent` apresenta as mensagens derivadas de `reasonDetails` ou `reasons` e não repete erros de campo. No histórico, falhas de leitura são renderizadas com `ErrorStateComponent`; no lote, a primeira falha vira estado de erro e falhas posteriores de polling aparecem como alerta com retry. Erros de validação de arquivo (400) são distintos de rejeições de regra de negócio, que são entregues como `FAILED` em uma linha.

A feature não implementa concorrência otimista nem envia `If-Match`/ETag para essas operações. O contrato de importação cria e consulta lotes; não existe edição concorrente de um lote na UI. Conflitos que ocorram durante a aplicação das regras de produto são tratados pelo backend conforme o resultado da linha, não por uma versão de formulário mantida pelo frontend.

## Sincronização entre partes da tela

- **URL → histórico**: `queryParamMap` alimenta os signals `cursor` e `pageSize`; mudanças de navegação alteram os parâmetros e reexecutam `listProductImportsResource`.
- **Envio → lote atual**: o ID recebido em `Location` atualiza `current`, que instancia o bloco de acompanhamento acima do histórico.
- **Histórico → lote atual inicial**: um `effect` escolhe, se ainda não houver `current`, o primeiro lote em andamento da página retornada.
- **Lote → histórico**: `settled` é emitido uma vez por ID terminal e chama `history.reload()`.
- **ID → polling**: `trackImport` acompanha mudanças de ID por `effect`, limpa estado antigo, cancela timers anteriores e ignora respostas de uma geração antiga. `DestroyRef` interrompe o timer e solta o ouvinte de `visibilitychange` ao destruir o componente.
- **Filtro e paginação de linhas**: `signal` guarda filtro, tamanho e offset; `computed` recalcula linhas, contadores e cursor local. Não há estado duplicado de linhas entre componentes: o relatório é a fonte, e o componente da tabela recebe `lines` como input.

## Padrões arquiteturais importantes

- **Separação de camadas**: a feature contém rotas, apresentação e coordenação da interação; `data-access` mantém cliente e tipos gerados; `ProductImportResource` implementa o adaptador REST; `ProductImportService` concentra a aplicação de regras por linha/grupo e o worker processa o lote fora do ciclo do request.
- **Signals e derivação declarativa**: `signal` representa estado mutável local, `computed` deriva filtros, rótulos e progresso, e `effect` coordena efeitos colaterais (seleção do lote em andamento e emissão de conclusão). `toSignal` adapta os observables do Router.
- **Polling encapsulado e cancelável**: `trackImport()` encapsula carregamento, erro, retry, polling e limpeza, evitando espalhar temporizadores pelos templates. O intervalo usa `Retry-After` quando interpretável como segundos ou data HTTP; sem ele, o fallback é 2 segundos. Com `document.hidden`, a leitura agendada fica em espera e é feita uma vez quando a aba volta a ficar visível.
- **Apresentação centralizada**: `import-presentation.ts` mapeia status/resultados para rótulos e cores, trata códigos desconhecidos e formata mensagens de linha. Componentes exibem a semântica sem repetir regras de mapeamento.
- **Contrato tipado e geração de cliente**: operações e DTOs vêm do data-access gerado, reduzindo divergência de URL, payload e formato de resposta entre telas e API. O cabeçalho do CSV é a exceção explícita por ser cópia local do contrato do backend.
- **Falhas explícitas**: cada etapa distingue carregamento, erro recuperável por ação do usuário, arquivo recusado e resultado `FAILED` por linha. O sucesso HTTP 202 não é apresentado como sucesso integral do processamento.

## Resumo executivo

A biblioteca oferece a experiência administrativa completa para importar produtos por CSV: modelo, envio assíncrono, histórico paginado, progresso e diagnóstico por linha. A UI permanece pequena e focada na apresentação; as regras de produto e integridade de grupos ficam na camada de aplicação do backend, e os contratos HTTP/tipos ficam no data-access. Signals, polling isolado e um bloco de lote reutilizado mantêm histórico e detalhe coerentes sem confundir a aceitação do arquivo com o resultado real da importação.
