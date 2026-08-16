package org.pianoml.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing configuration.
 *
 * <p>There is deliberately no default value for {@code secret}. The application must fail to start
 * when {@code JWT_SECRET} is absent rather than silently signing tokens with a well-known key —
 * the previous implementation carried two such defaults (one in {@code application.properties},
 * one inline in {@code JwtTokenProvider}), either of which would have made every token forgeable.
 *
 * <p>Generate a key with: {@code openssl rand -base64 64 | tr -d '\n'}
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(

    @NotBlank(message = "JWT_SECRET must be set; generate one with: openssl rand -base64 64")
    @Size(min = 64, message = "JWT_SECRET must be at least 64 characters for HMAC-512")
    String secret,

    @Positive(message = "jwt.expiration-ms must be positive")
    long expirationMs

) {

  /** Rejects placeholder values that look real enough to survive code review. */
  public JwtProperties {
    if (secret != null && FORBIDDEN_SECRETS.contains(secret.trim().toLowerCase())) {
      throw new IllegalStateException(
          "JWT_SECRET is set to a known placeholder value. Generate a real key: "
              + "openssl rand -base64 64 | tr -d '\\n'");
    }
  }

  private static final java.util.Set<String> FORBIDDEN_SECRETS = java.util.Set.of(
      "supersecret",
      "'supersecret'",
      "changeme",
      "secret",
      "your-super-secret-key-that-is-long-enough");
}
