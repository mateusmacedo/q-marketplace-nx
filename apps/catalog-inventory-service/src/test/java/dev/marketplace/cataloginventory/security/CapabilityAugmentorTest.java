package dev.marketplace.cataloginventory.security;

import dev.marketplace.cataloginventory.application.Capability;
import io.quarkus.security.Authenticated;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityAugmentorTest {
  static final java.nio.file.Path API = java.nio.file.Path.of("src/main/java/dev/marketplace/cataloginventory/api");
  static final List<Class<? extends Annotation>> HTTP_METHODS = List.of(GET.class, POST.class, PUT.class,
      DELETE.class, PATCH.class);

  final CapabilityAugmentor augmentor = new CapabilityAugmentor();

  SecurityIdentity withRoles(String... roles) {
    return augmentor.augment(QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("u-1"))
        .addRoles(Set.of(roles)).build(), null).await().indefinitely();
  }

  boolean grants(SecurityIdentity identity, String capability) {
    return identity.checkPermissionBlocking(new StringPermission(capability));
  }

  @Test
  void theIdentityGetsTheCapabilitiesThatTheCallerContextDerivesFromTheRoles() {
    SecurityIdentity moderator = withRoles(Roles.CATALOG_MODERATOR);
    assertTrue(grants(moderator, Capabilities.MODERATE_PRODUCTS));
    assertTrue(grants(moderator, Capabilities.READ_SELLER_ELIGIBILITY));
    assertFalse(grants(moderator, Capabilities.MANAGE_MODERATION_REASONS));
    assertEquals(Set.of(Roles.CATALOG_MODERATOR), moderator.getRoles(), "os papéis continuam os do token");

    SecurityIdentity admin = withRoles(Roles.CATALOG_ADMIN);
    for (Capability capability : CallerContext.capabilities(Set.of(Roles.CATALOG_ADMIN)))
      assertTrue(grants(admin, capability.name()), capability.name());
    assertFalse(grants(admin, Capabilities.MANAGE_OWN_PRODUCTS));

    SecurityIdentity unknown = withRoles("customer");
    for (Capability capability : Capability.values())
      assertFalse(grants(unknown, capability.name()), "papel desconhecido não ganha " + capability);
  }

  @Test
  void theAnonymousIdentityStaysAsItIs() {
    SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    assertSame(anonymous, augmentor.augment(anonymous, null).await().indefinitely());
  }

  @Test
  void everyAnnotationConstantNamesACapability() throws IllegalAccessException {
    Set<String> constants = new TreeSet<>();
    for (Field field : Capabilities.class.getDeclaredFields())
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        assertEquals(field.getName(), field.get(null), field.getName());
        constants.add((String) field.get(null));
      }
    assertEquals(names(Capability.values()), constants);
  }

  @Test
  void everyEndpointDeclaresCapabilitiesInsteadOfRoles() throws IOException, ClassNotFoundException {
    List<String> problems = new ArrayList<>();
    Set<String> capabilities = names(Capability.values());
    int endpoints = 0;
    for (Class<?> resource : resources())
      for (Method method : resource.getDeclaredMethods()) {
        if (HTTP_METHODS.stream().noneMatch(method::isAnnotationPresent))
          continue;
        endpoints++;
        String endpoint = resource.getSimpleName() + "." + method.getName();
        if (method.isAnnotationPresent(RolesAllowed.class) || resource.isAnnotationPresent(RolesAllowed.class))
          problems.add(endpoint + " usa @RolesAllowed; o papel fica só no CallerContext");
        PermissionsAllowed permissions = method.isAnnotationPresent(PermissionsAllowed.class)
            ? method.getAnnotation(PermissionsAllowed.class)
            : resource.getAnnotation(PermissionsAllowed.class);
        if (permissions != null) {
          Arrays.stream(permissions.value()).filter(p -> !capabilities.contains(p))
              .forEach(p -> problems.add(endpoint + " exige " + p + ", que não é capacidade"));
        } else if (Stream.of(Authenticated.class, PermitAll.class)
            .noneMatch(a -> method.isAnnotationPresent(a) || resource.isAnnotationPresent(a))) {
          problems.add(endpoint + " sem anotação de segurança");
        }
      }
    assertTrue(endpoints > 50, "a varredura achou " + endpoints + " endpoints");
    assertEquals(List.of(), problems);
  }

  static Set<String> names(Capability[] values) {
    return Arrays.stream(values).map(Capability::name).collect(Collectors.toCollection(TreeSet::new));
  }

  static List<Class<?>> resources() throws IOException, ClassNotFoundException {
    List<Class<?>> resources = new ArrayList<>();
    try (Stream<java.nio.file.Path> files = Files.list(API)) {
      for (String file : files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".java")).sorted()
          .toList()) {
        Class<?> type = Class.forName("dev.marketplace.cataloginventory.api." + file.replace(".java", ""));
        if (type.isAnnotationPresent(Path.class))
          resources.add(type);
      }
    }
    return resources;
  }
}
