# Contratos de kyb-kyc

Espaço para OpenAPI, protobuf e esquemas de eventos publicados por este contexto, que ainda não tem API implementada.

`events/seller-verdicts.v1.schema.json` define os vereditos `SellerApproved`, `SellerRejected` e `ReviewRequired` que este contexto publica no tópico `kyb-kyc.seller.v1`, com chave do registro igual ao `storeId`; o `catalog-inventory` os consome (ADR 013). Até o lançamento o contrato pode mudar sem compatibilidade retroativa (ADR 015). Consumidores dependem de contratos publicados, nunca das classes de domínio do produtor. Adicionar a dependência ao grafo Nx quando houver geração ou consumo de código.
