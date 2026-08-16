package org.pianoml.backend.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the fail-fast contract on the JWT signing key.
 *
 * <p>Regression cover for the pre-Phase-0 state, where a well-known key was reachable from two
 * independent defaults ({@code application.properties} and an inline {@code @Value} fallback in
 * {@code JwtTokenProvider}), either of which would have made every issued token forgeable.
 */
class JwtPropertiesTest {

  private static final String VALID_SECRET =
      "test-only-hmac512-key-do-not-use-outside-tests-0123456789abcdefghij";

  private final Validator validator =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("accepts a sufficiently long, non-placeholder secret")
  void validSecret_passesValidation() {
    var properties = new JwtProperties(VALID_SECRET, 86_400_000L);

    assertThat(validator.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("rejects a blank secret")
  void blankSecret_failsValidation() {
    var properties = new JwtProperties("   ", 86_400_000L);

    assertThat(validator.validate(properties))
        .extracting(v -> v.getPropertyPath().toString())
        .contains("secret");
  }

  @Test
  @DisplayName("rejects a secret shorter than 64 characters")
  void shortSecret_failsValidation() {
    var properties = new JwtProperties("too-short-for-hmac512", 86_400_000L);

    assertThat(validator.validate(properties))
        .anySatisfy(v -> assertThat(v.getMessage()).contains("at least 64 characters"));
  }

  @Test
  @DisplayName("rejects a non-positive expiry")
  void nonPositiveExpiry_failsValidation() {
    var properties = new JwtProperties(VALID_SECRET, 0L);

    assertThat(validator.validate(properties))
        .extracting(v -> v.getPropertyPath().toString())
        .contains("expirationMs");
  }

  @ParameterizedTest(name = "placeholder \"{0}\" is rejected at construction")
  @ValueSource(strings = {
      "supersecret",
      "'supersecret'",
      "changeme",
      "secret",
      "your-super-secret-key-that-is-long-enough",
      "  SuperSecret  "
  })
  @DisplayName("rejects known placeholder secrets before they can sign anything")
  void placeholderSecret_throws(String placeholder) {
    assertThatThrownBy(() -> new JwtProperties(placeholder, 86_400_000L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("placeholder");
  }
}
