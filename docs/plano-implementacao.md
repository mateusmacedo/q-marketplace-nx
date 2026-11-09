# Plano de implementação

## Entregue

- Workspace Nx com lockfile, Gradle Wrapper e Java 21.
- Oito serviços com saúde e informações, 16 bibliotecas Java e oito diretórios de contratos.
- Catálogo e inventário: categorias, produtos e SKUs, ofertas com promoções, moderação, vitrine, estoque, reservas atômicas com prazo, eventos com outbox no Kafka, consumo dos vereditos KYB/KYC com inbox e importação de produtos por planilha CSV (ADRs 006 a 016).
- Fiscalização de limites, CI, smoke, imagem nativa por serviço e Compose com PostgreSQL, Kafka e Keycloak.
- Helm chart de produção do catálogo (`infra/helm/catalog-inventory`), com segredos por referência e Job que cria os tópicos e a fila de descarte no Kafka (ADR 018).
- Suíte de carga do catálogo por perfis, com k6 (`tools/load`, ADR 022): massa própria por execução, cenários que cobrem as rotas, as reservas, o varredor e a importação, e conferência dos estados finais.
- Painel de operação do catálogo (`apps/admin-web`, Angular 22 e CoreUI grátis, ADRs 027 a 029), conforme a [proposta de design](proposta-design-admin-web.md): Início com indicadores, Situação da loja, produtos com ações em lote, produto e SKU (conteúdo, preço, promoção, estoque e histórico), Fila e Motivos de moderação, Categorias e Importações, para o lojista, a moderação e a plataforma.
- Regras e catálogos no servidor (ADR 031): sessão por `GET /me` com as capacidades, ações permitidas e barradas de cada produto, pendências de publicação conferidas no rascunho, mensagens das recusas em `reasonDetails` e motivos de moderação por tenant (ADR 030).
- e2e do painel (`apps/admin-web-e2e`) com o login e os fluxos de admin, lojista e moderação contra a stack local, e a conferência de memória do host antes das tarefas pesadas (`npm run check:memory`).

## Pendências do catálogo

- Produto mestre (BuyBox), evolução fora do escopo atual (ADR 011).
- Reserva por pedido de seller isolado e conciliação com o OMS, dependentes dos contratos da saga (ADR 012).
- Pendências do painel e do serviço, com o motivo e a saída de cada uma, em [`decisoes-em-aberto.md`](decisoes-em-aberto.md); os valores que o painel ainda copia do servidor estão na tabela "Valores fixos com saída pendente" da proposta de design.

## Sequência sugerida por capacidade

| Etapa | Contextos | Entrega e critério de saída |
|---|---|---|
| 1 | Identity & Organization | OIDC e autorização por organização; testes negativos de acesso entre tenants |
| 2 | Catalog & Inventory | Entregue, com as pendências acima; concorrência não causa saldo negativo |
| 3 | KYB/KYC | Adaptador de provedor e estados; seller não aprovado não transaciona; publica os vereditos que o catálogo consome |
| 4 | Cart & Checkout | Revalidação de preço, frete e reserva; valores fecham em centavos |
| 5 | Order Management + Payment & Split | Contratos de captura, saga e webhook idempotente; repetir callback não duplica pedido nem cobrança |
| 6 | Fulfillment & Logistics | Etiqueta/rastreio com prova de entrega; evento duplicado não duplica transição |
| 7 | Settlement & Payout | Ledger imutável, liberação e conciliação; repetição de payout não transfere duas vezes |

O catálogo é o primeiro contexto implementado (ADR 004) e usa JWT validado no lugar da identidade que a etapa 1 entregará (ADR 007). Mensageria com outbox, inbox e rastreabilidade segue o padrão do catálogo (ADR 013) em cada nova integração. Implementar armazenamento em cada contexto conforme seu modelo, sem introduzir todos os bancos apenas porque aparecem nas referências.

## Refinamentos necessários

1. Resolver nomes e semântica de eventos de confirmação/captura de pagamento.
2. Definir propriedade do escrow e momento de liberação, distinguindo modelo interno e operação do provedor.
3. Separar status financeiro global de status operacional dos subpedidos.
4. Definir fronteira do tenant white-label e seu relacionamento com sellers e buyers.
5. Definir o frontend e as jornadas do storefront; o painel de operação do catálogo já tem as suas (proposta de design e ADR 029).

Não há estimativas de prazo: equipe, escopo de cada corte e provedores ainda não foram definidos.
