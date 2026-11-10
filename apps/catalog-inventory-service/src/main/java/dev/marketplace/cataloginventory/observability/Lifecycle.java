package dev.marketplace.cataloginventory.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jboss.logging.MDC;

/**
 * Spans do ciclo de vida das operações (ADR 021). A operação é o span que
 * recebe as chaves de negócio por
 * {@link Telemetry}: a requisição HTTP, a mensagem consumida, a execução
 * agendada ou uma etapa dela. Dentro da
 * operação, cada passagem por resource, consumidor ou adaptador vira um span
 * filho ({@link Traced}), e as consultas
 * SQL ficam penduradas na passagem que as fez.
 */
@ApplicationScoped
public class Lifecycle {
    static final ContextKey<Span> OPERATION = ContextKey.named("marketplace.operation");
    static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("marketplace.outcome");
    static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> NAMESPACE = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> FUNCTION = AttributeKey.stringKey("code.function");
    private static final String DOMAIN = "dev.marketplace.cataloginventory.domain.";
    private static final String APPLICATION = "dev.marketplace.cataloginventory.application.";

    private final Tracer tracer;

    Lifecycle(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Span da operação em curso, que recebe as chaves de negócio; fora de uma
     * operação, o span ativo.
     */
    static Span operation() {
        Span operation = Context.current().get(OPERATION);
        return operation != null ? operation : Span.current();
    }

    /**
     * Roda o trabalho numa etapa: um span filho do ativo, aberto na assinatura, que
     * passa a ser a operação e fica
     * ativo num contexto próprio ({@link #isolated}) até o {@code Uni} terminar,
     * então as consultas, os logs e os
     * eventos da etapa entram nele; falha marca o span como erro, e cancelamento só
     * o encerra. Ao fim, o MDC volta ao
     * que era antes da etapa: as chaves gravadas nela (por {@link Telemetry#tag})
     * não passam para os logs seguintes.
     */
    public <T> Uni<T> step(String name, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> {
            Map<String, Object> outer = MDC.getMap();
            Span span = tracer.spanBuilder(name).startSpan();
            return isolated(Context.current().with(span).with(OPERATION, span), work, failure -> {
                if (failure != null) {
                    span.recordException(failure);
                    span.setStatus(StatusCode.ERROR);
                }
                span.end();
                MDC.clear();
                outer.forEach(MDC::put);
            });
        });
    }

    /**
     * Mantém o span da execução agendada ativo, como operação, até o {@code Uni}
     * terminar. O agendador fecha o escopo
     * quando o método devolve o {@code Uni}, e o cliente SQL lê o span do contexto
     * Vert.x; sem isto, cada consulta
     * viraria raiz de um trace solto. Chamar de dentro do método agendado. Não
     * encerra o span nem o marca como erro:
     * falha que o chamador recupera fica só no log.
     */
    public <T> Uni<T> job(Supplier<Uni<T>> work) {
        Context job = Context.current().with(OPERATION, Span.current());
        io.vertx.core.Context current = Vertx.currentContext();
        io.vertx.core.Context vertx = current == null ? null : VertxContext.getOrCreateDuplicatedContext(current);
        Uni<T> traced = Uni.createFrom().deferred(() -> {
            Scope scope = job.makeCurrent();
            Uni<T> running;
            try {
                running = work.get();
            } catch (RuntimeException e) {
                scope.close();
                throw e;
            }
            return running.onTermination().invoke(scope::close);
        });
        return vertx == null ? traced : traced.runSubscriptionOn(task -> vertx.runOnContext(ignored -> task.run()));
    }

    /**
     * Porta de entrada (resource HTTP ou consumidor Kafka): o span abre na chamada,
     * filho do span da requisição ou da
     * mensagem, e fica ativo no contexto dela até o {@code Uni} terminar, como no
     * {@code @WithSpan} do Quarkus. A
     * operação continua a ser o span de fora; uma porta de entrada não tem irmã em
     * paralelo.
     */
    public <T> Uni<T> entry(String name, Attributes attributes, Supplier<Uni<T>> work) {
        Context parent = Context.current();
        Span span = start(name, attributes, parent);
        Scope scope = parent.with(span).with(OPERATION, operationOf(parent)).makeCurrent();
        Uni<T> running;
        try {
            running = work.get();
        } catch (RuntimeException e) {
            scope.close();
            end(span, e);
            throw e;
        }
        return running.onTermination().invoke((item, failure, cancelled) -> {
            scope.close();
            end(span, failure);
        });
    }

    /**
     * Passagem por um adaptador: o span nasce na assinatura, filho do ativo, e fica
     * ativo num contexto próprio
     * ({@link #isolated}). Assim, chamadas em paralelo ({@code Uni.combine}) não
     * trocam de pai.
     */
    public <T> Uni<T> nested(String name, Attributes attributes, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> {
            Context parent = Context.current();
            Span span = start(name, attributes, parent);
            return isolated(parent.with(span).with(OPERATION, operationOf(parent)), work,
                    failure -> end(span, failure));
        });
    }

    /**
     * {@link #nested} com o nome {@code Classe.método} e os atributos de código,
     * para trecho sem bean próprio.
     */
    public <T> Uni<T> nested(Class<?> owner, String function, Supplier<Uni<T>> work) {
        return nested(owner.getSimpleName() + "." + function, code(owner, function), work);
    }

    /**
     * Passagem por um adaptador que usa a conexão de quem chama: o span fica ativo
     * no contexto de quem chama até o
     * {@code Uni} terminar. WHY: o cliente SQL lê o pai no contexto em que a
     * conexão nasceu, e não no da chamada; num
     * contexto próprio, as consultas desta passagem cairiam no span de quem abriu a
     * transação.
     */
    public <T> Uni<T> sharing(String name, Attributes attributes, Supplier<Uni<T>> work) {
        return Uni.createFrom().deferred(() -> {
            Context parent = Context.current();
            Span span = start(name, attributes, parent);
            Scope scope = parent.with(span).with(OPERATION, operationOf(parent)).makeCurrent();
            return call(work).onTermination().invoke((item, failure, cancelled) -> {
                scope.close();
                end(span, failure);
            });
        });
    }

    static Attributes code(Class<?> owner, String function) {
        return Attributes.of(NAMESPACE, owner.getName(), FUNCTION, function);
    }

    /**
     * Span com pai explícito que não fica ativo: o trabalho recebe o contexto do
     * span, para levar o pai adiante, e o
     * span termina com o {@code Uni}. O span de quem chama entra como link quando é
     * de outro trace. Serve à publicação
     * de cada evento da outbox, que continua o trace da requisição que o gravou.
     */
    public <T> Uni<T> linked(String name, Context parent, Attributes attributes, Function<Context, Uni<T>> work) {
        return Uni.createFrom().deferred(() -> {
            SpanBuilder builder = tracer.spanBuilder(name).setParent(parent).setAllAttributes(attributes);
            SpanContext caller = Span.current().getSpanContext();
            if (caller.isValid() && !caller.equals(Span.fromContext(parent).getSpanContext()))
                builder.addLink(caller);
            Span span = builder.startSpan();
            Uni<T> running;
            try {
                running = work.apply(parent.with(span));
            } catch (RuntimeException e) {
                end(span, e);
                throw e;
            }
            return running.onTermination().invoke((item, failure, cancelled) -> end(span, failure));
        });
    }

    /**
     * Roda o trabalho num contexto Vert.x aninhado, despachado na mesma thread, com
     * {@code traced} ativo só ali, e
     * entrega o resultado no contexto de quem chama, onde também roda {@code done}.
     * WHY: o cliente SQL lê o span ativo
     * do contexto Vert.x, e um contexto só por requisição faria passagens em
     * paralelo trocarem de pai e uma consulta
     * anterior devolver o contexto OTel que tinha, apagando o da etapa. O contexto
     * aninhado copia os locais (MDC,
     * contexto CDI), então as chaves gravadas aqui chegam ao log da requisição.
     */
    private static <T> Uni<T> isolated(Context traced, Supplier<Uni<T>> work, Consumer<Throwable> done) {
        io.vertx.core.Context caller = Vertx.currentContext();
        if (caller == null) {
            Scope scope = traced.makeCurrent();
            return call(work).onTermination().invoke((item, failure, cancelled) -> {
                scope.close();
                done.accept(failure);
            });
        }
        return Uni.createFrom().<T>emitter(emitter -> {
            ContextInternal back = (ContextInternal) caller;
            AtomicBoolean finished = new AtomicBoolean();
            Scope[] scope = new Scope[1];
            Cancellable[] running = new Cancellable[1];
            ((ContextInternal) VertxContext.newNestedContext(caller)).dispatch(() -> {
                scope[0] = traced.makeCurrent();
                running[0] = call(work).subscribe().with(item -> {
                    if (finished.compareAndSet(false, true)) {
                        scope[0].close();
                        back.dispatch(() -> {
                            done.accept(null);
                            emitter.complete(item);
                        });
                    }
                }, failure -> {
                    if (finished.compareAndSet(false, true)) {
                        scope[0].close();
                        back.dispatch(() -> {
                            done.accept(failure);
                            emitter.fail(failure);
                        });
                    }
                });
            });
            emitter.onTermination(() -> {
                if (finished.compareAndSet(false, true)) {
                    running[0].cancel();
                    scope[0].close();
                    done.accept(null);
                }
            });
        });
    }

    private Span start(String name, Attributes attributes, Context parent) {
        return tracer.spanBuilder(name).setParent(parent).setAllAttributes(attributes).startSpan();
    }

    private static Span operationOf(Context parent) {
        Span operation = parent.get(OPERATION);
        return operation != null ? operation : Span.fromContext(parent);
    }

    private static <T> Uni<T> call(Supplier<Uni<T>> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            return Uni.createFrom().failure(e);
        }
    }

    /**
     * Encerra o span. Recusa prevista (resposta 4xx) fica como resultado
     * {@code refused}, com o tipo da exceção; só
     * falha técnica grava a exceção e marca o span como erro.
     */
    private static void end(Span span, Throwable failure) {
        if (failure != null && refusal(failure)) {
            span.setAttribute(OUTCOME, "refused");
            span.setAttribute(EXCEPTION_TYPE, failure.getClass().getName());
        } else if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
        }
        span.end();
    }

    /**
     * WHY: as exceções de domínio e de aplicação, a validação e o corpo ilegível
     * viram 4xx em {@code ProblemMappers};
     * pintar o trace de erro a cada 409 ou 422 esconderia as falhas reais.
     */
    static boolean refusal(Throwable failure) {
        if (failure instanceof WebApplicationException web)
            return web.getResponse().getStatus() < 500;
        String type = failure.getClass().getName();
        return type.startsWith(DOMAIN) || type.startsWith(APPLICATION)
                || failure instanceof ConstraintViolationException || failure instanceof JsonProcessingException
                || failure instanceof Refusal;
    }
}
