package dev.marketplace.cataloginventory.observability;

import io.opentelemetry.api.common.Attributes;
import io.quarkus.arc.ArcInvocationContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Abre o span de {@link Traced}, com {@code code.namespace} e {@code code.function}, nos métodos que devolvem
 * {@code Uni}: porta de entrada, adaptador com a conexão de quem chama ({@link Lifecycle#sharing}) ou adaptador.
 */
@Traced
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class TracedInterceptor {
  @Inject
  Lifecycle lifecycle;

  @AroundInvoke
  Object trace(ArcInvocationContext context) throws Exception {
    Method method = context.getMethod();
    if (!Uni.class.isAssignableFrom(method.getReturnType()))
      return context.proceed();
    Class<?> owner = method.getDeclaringClass();
    String name = owner.getSimpleName() + "." + method.getName();
    Attributes attributes = Lifecycle.code(owner, method.getName());
    Supplier<Uni<Object>> call = () -> proceed(context);
    if (context.getInterceptorBindings().stream().anyMatch(b -> b instanceof Traced t && t.entry()))
      return lifecycle.entry(name, attributes, call);
    return usesCallerConnection(method)
        ? lifecycle.sharing(name, attributes, call)
        : lifecycle.nested(name, attributes, call);
  }

  private static boolean usesCallerConnection(Method method) {
    for (Class<?> type : method.getParameterTypes())
      if (SqlClient.class.isAssignableFrom(type))
        return true;
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Uni<Object> proceed(ArcInvocationContext context) {
    try {
      return (Uni<Object>) context.proceed();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      return Uni.createFrom().failure(e);
    }
  }
}
