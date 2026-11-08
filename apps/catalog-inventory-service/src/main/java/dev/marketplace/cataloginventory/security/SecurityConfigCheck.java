package dev.marketplace.cataloginventory.security;

import io.quarkus.runtime.Startup;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Optional;

/**
 * Falha a inicialização quando a validação de tokens não está configurada.
 * WHY: antecipa o erro para o boot; um pod mal configurado não deve ficar
 * "pronto"
 * para depois rejeitar todo tráfego autenticado.
 */
@Startup
@Singleton
public class SecurityConfigCheck {
    /**
     * Interrompe a inicialização com {@link IllegalStateException} se faltar
     * issuer, audiência ou
     * chave pública (por localização ou inline).
     */
    SecurityConfigCheck(@ConfigProperty(name = "mp.jwt.verify.issuer") String issuer,
            @ConfigProperty(name = "mp.jwt.verify.publickey.location") Optional<String> keyLocation,
            @ConfigProperty(name = "mp.jwt.verify.publickey") Optional<String> inlineKey,
            @ConfigProperty(name = "mp.jwt.verify.audiences") String audiences) {
        boolean hasKey = keyLocation.filter(v -> !v.isBlank()).or(() -> inlineKey.filter(v -> !v.isBlank()))
                .isPresent();
        if (issuer.isBlank() || audiences.isBlank() || !hasKey)
            throw new IllegalStateException(
                    "Configuração de JWT incompleta: issuer, chave pública e audiência são obrigatórios");
    }
}
