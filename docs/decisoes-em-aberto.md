# Decisões em aberto

Pendências do painel do catálogo e do serviço que ficaram para depois, com o motivo e a saída de cada uma. Os valores que
o painel ainda copia do servidor estão na tabela "Valores fixos com saída pendente" da
[proposta de design](proposta-design-admin-web.md).

## Para corrigir

- **Rótulo do atributo na recusa `unknown-attribute`:** a mensagem mostra o código do atributo ("comprimento"), não o
  rótulo, na prévia de Trocar categoria (`i18n/ReasonMessages.properties`, `unknown-attribute.1`). A saída é o emissor
  levar o rótulo da categoria de origem.
- **`%00` no caminho de `/moderation/reasons/{code}`:** pelo gateway, o nginx recusa antes com 400; direto no serviço, o
  código chega sem validação de formato e o provável é um 500 do PostgreSQL. A saída é validar o `code` do caminho com o
  padrão de `ModerationReasonDefinition`, que já cai no 400 `validation-failed`.
- **Árvore com `usable=true`:** a recursão de `GET /categories/tree` poderia parar na categoria inativa, porque nenhum
  descendente dela passa no filtro.

## Para decidir

- **Preço e estoque no produto bloqueado:** o domínio só congela o arquivado (`Product.acceptsOfferAndStockChanges`),
  então o lojista ainda edita preço e estoque de um produto bloqueado.
- **Permissões no contrato:** com o acesso por capacidade (`@PermissionsAllowed`), 55 das 56 operações do OpenAPI
  declaram a segurança com escopos vazios; o contrato não diz mais quem pode chamar cada uma.
- **Lote de enviar e aprovar na listagem:** a listagem não traz `publishingIssues`, então Enviar e Aprovar aparecem
  habilitados para produto com pendência, e o servidor recusa item a item.
- **Motivos de moderação de um tenant novo:** o catálogo nasce vazio; até o admin cadastrar motivos, o moderador decide
  só com o complemento. O teto é de 100 motivos ativos e 1000 no total.

## Limites aceitos

- **Caractere U+0000 no corpo JSON:** a recusa acontece na leitura do JSON, então a resposta traz só esse erro, e os
  demais campos do mesmo corpo só são validados no reenvio.
- **Limite da busca e tamanhos de página no painel:** o gerador de schemas do cliente não gera os parâmetros, então o
  `maxLength` de `q` e o máximo de `limit` seguem copiados no painel, como cópia pendente (ADR 031).
- **Lote de importação já concluído:** abrir direto um lote terminado faz duas leituras, uma leve e uma com as linhas,
  em troca de o andamento nunca baixar as linhas.
