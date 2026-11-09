package dev.marketplace.cataloginventory;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

/**
 * Atalhos de teste: um tenant isolado por cenário, com categoria de tShirts e
 * uma loja.
 */
final class Api {
    static final String CATEGORY = """
            {"code":"camisetas","name":"Camisetas","attributes":[
              {"code":"marca","label":"Marca","type":"TEXT","required":true,"variant":false},
              {"code":"tamanho","label":"Tamanho","type":"OPTION","required":true,"variant":true,"options":["P","M","G"]}]}""";

    /**
     * SKU a criar no produto e o preço "por" da oferta que o helper define logo
     * depois.
     */
    record SkuSpec(String sellerSku, String json, String salePrice) {
    }

    final UUID tenant = UUID.randomUUID();
    final UUID store = UUID.randomUUID();
    final String seller = TestTokens.seller(tenant, store);
    final String admin = TestTokens.admin(tenant);
    final String moderator = TestTokens.moderator(tenant);
    final String checkout = TestTokens.reservations(tenant);
    String categoryId;

    static RequestSpecification as(String token) {
        return given().auth().oauth2(token).contentType("application/json");
    }

    /** Último segmento da URL, o id do recurso criado, como no cabeçalho {@code Location}. */
    static String idFrom(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /**
     * Dispara {@code attempts} requisições ao mesmo tempo, com a largada
     * sincronizada, e devolve os status na ordem de disparo.
     */
    static List<Integer> race(int attempts, IntFunction<Response> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                int attempt = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return request.apply(attempt).statusCode();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results)
                statuses.add(result.get(30, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Lê a coluna da única linha da consulta; coluna nula devolve nulo. */
    static <T> T scalar(Pool pool, String sql, String column, Class<T> type, Object... params) {
        return pool.preparedQuery(sql).execute(Tuple.from(Arrays.asList(params))).await().atMost(Duration.ofSeconds(20))
                .iterator().next().get(type, column);
    }

    /** Define o estoque físico do SKU; {@code ifMatch} nulo omite o cabeçalho. */
    static Response putStock(String token, String skuId, int onHand, String ifMatch) {
        RequestSpecification request = as(token).body("{\"onHand\":" + onHand + "}");
        if (ifMatch != null)
            request = request.header("If-Match", ifMatch);
        return request.put("/inventory/skus/" + skuId);
    }

    static SkuSpec sku(String sellerSku, String size, String salePrice) {
        return new SkuSpec(sellerSku, """
                {"sellerSku":"%s","variantAttributes":{"tamanho":"%s"},"physical":true,
                 "dimensions":{"heightCm":10,"widthCm":20,"lengthCm":30.5,"weightGrams":350}}""".formatted(sellerSku,
                size), salePrice);
    }

    static String price(String salePrice) {
        return """
                {"listPrice":{"amount":120.00,"currency":"BRL"},"salePrice":{"amount":%s,"currency":"BRL"}}"""
                .formatted(salePrice);
    }

    Api withCategory() {
        categoryId = as(admin).body(CATEGORY).post("/categories").then().statusCode(201).extract().path("id");
        return this;
    }

    /** Catálogo de motivos do tenant, criado pelo admin como num tenant real; o tenant novo não tem nenhum. */
    Api withModerationReasons() {
        reason("wrong-category", "Categoria incorreta", "REJECT");
        reason("misleading-description", "Descrição incompleta ou enganosa", "REJECT", "BLOCK");
        reason("inconsistent-attributes", "Atributos inconsistentes com os SKUs", "REJECT");
        reason("price-out-of-range", "Preço fora do padrão", "REJECT", "BLOCK");
        reason("prohibited-item", "Item proibido na plataforma", "BLOCK");
        return this;
    }

    /** Cria o motivo ativo no fim do catálogo do tenant. */
    void reason(String code, String label, String... decisions) {
        String list = String.join(",", Arrays.stream(decisions).map(d -> "\"" + d + "\"").toList());
        as(admin).body("{\"code\":\"%s\",\"label\":\"%s\",\"decisions\":[%s]}".formatted(code, label, list))
                .post("/moderation/reasons").then().statusCode(201);
    }

    Api approvedStore() {
        return kybVerdict("SellerApproved", "APPROVED");
    }

    /** Publica o veredito KYB/KYC da loja e espera o catálogo consumi-lo. */
    Api kybVerdict(String eventType, String expectedStatus) {
        Kafka.publishVerdict(store, Kafka.verdict(UUID.randomUUID(), eventType, tenant, store, Instant.now()));
        return awaitEligibility(expectedStatus);
    }

    Api awaitEligibility(String expectedStatus) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> as(admin).get("/stores/" + store + "/eligibility")
                .then().statusCode(200).body("status", is(expectedStatus)));
        return this;
    }

    String productBody(String marca, SkuSpec... skus) {
        String attributes = marca == null ? "{}" : "{\"marca\":\"" + marca + "\"}";
        String skuList = String.join(",", Arrays.stream(skus).map(SkuSpec::json).toList());
        return """
                {"categoryId":"%s","currency":"BRL","title":"Camiseta básica","description":"Algodão","attributes":%s,"skus":[%s]}"""
                .formatted(categoryId, attributes, skuList);
    }

    /** Produto em rascunho, sem ofertas. */
    String draftProduct(SkuSpec... skus) {
        return as(seller).body(productBody("Acme", skus)).post("/products").then().statusCode(201).extract().path("id");
    }

    /** Produto em rascunho com a oferta de cada SKU definida no preço informado. */
    String createProduct(SkuSpec... skus) {
        String id = draftProduct(skus);
        for (SkuSpec sku : skus)
            offer(skuId(id, sku.sellerSku()), sku.salePrice());
        return id;
    }

    void offer(String skuId, String salePrice) {
        as(seller).body(price(salePrice)).put("/offers/" + skuId).then().statusCode(201);
    }

    String skuId(String productId, String sellerSku) {
        JsonPath product = as(seller).get("/products/" + productId).then().statusCode(200).extract().jsonPath();
        List<String> ids = product.getList("skus.id");
        List<String> codes = product.getList("skus.sellerSku");
        return ids.get(codes.indexOf(sellerSku));
    }

    List<String> skuIds(String productId) {
        return as(seller).get("/products/" + productId).then().statusCode(200).extract().path("skus.id");
    }

    Response post(String token, String path, String etag) {
        RequestSpecification request = as(token);
        if (etag != null)
            request = request.header("If-Match", etag);
        return request.post(path);
    }

    String etag(String token, String path) {
        return as(token).get(path).then().statusCode(200).extract().header("ETag");
    }

    void stock(String skuId, int onHand) {
        putStock(seller, skuId, onHand, null).then().statusCode(200);
    }

    /** Seller de outra loja do mesmo tenant. */
    String otherStoreSeller() {
        return TestTokens.seller(tenant, UUID.randomUUID());
    }

    /** Seller com o mesmo {@code store_id}, mas de outro tenant. */
    String otherTenantSeller() {
        return TestTokens.seller(UUID.randomUUID(), store);
    }

    /** Envia o rascunho do produto à moderação, como o seller, sem {@code If-Match}. */
    Response submit(String productId) {
        return post(seller, "/products/" + productId + "/submit", null);
    }

    /** Aprova o produto em moderação, como o moderador, sem {@code If-Match}. */
    Response approve(String productId) {
        return post(moderator, "/moderation/products/" + productId + "/approve", null);
    }

    /** Consulta pública da vitrine do tenant, sem token. */
    Response storefront(String productId) {
        return given().get("/storefront/" + tenant + "/products/" + productId);
    }

    /** Define o mesmo estoque físico para todos os SKUs do produto. */
    void stockAll(String productId, int onHand) {
        skuIds(productId).forEach(skuId -> stock(skuId, onHand));
    }

    /**
     * Cria com ofertas e 10 unidades por SKU, envia e aprova; devolve o id do
     * produto ativo.
     */
    String activeProduct(SkuSpec... skus) {
        String id = createProduct(skus);
        stockAll(id, 10);
        submit(id).then().statusCode(200);
        approve(id).then().statusCode(200);
        return id;
    }
}
