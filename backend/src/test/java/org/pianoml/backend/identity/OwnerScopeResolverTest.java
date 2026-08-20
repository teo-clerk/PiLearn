package org.pianoml.backend.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.repository.UserRepository;
import org.pianoml.backend.service.AccountService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Who the caller is.
 *
 * <p>Two properties matter most. An anonymous visitor is never turned away — demanding an
 * account before the learner has seen a bar render is the failure this class exists to
 * prevent. And a guest scope always carries a session id, because every guest shares one
 * account: a scope without the session would match every other visitor's rows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OwnerScopeResolverTest {

  @Mock private UserRepository userRepository;
  @Mock private AccountService accountService;

  private OwnerScopeResolver resolver;
  private User guest;

  @BeforeEach
  void setUp() {
    resolver = new OwnerScopeResolver(userRepository, accountService);

    guest = new User();
    guest.setId(OwnerScopeResolver.GUEST_USER_ID);
    when(userRepository.findById(OwnerScopeResolver.GUEST_USER_ID))
        .thenReturn(Optional.of(guest));

    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private User signIn() {
    User user = new User();
    user.setId(UUID.randomUUID());
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user.getId().toString(), null));
    when(accountService.getUserFromAuthentication(any())).thenReturn(user);
    return user;
  }

  @Nested
  @DisplayName("resolve — establishes ownership, mints a session when needed")
  class Resolve {

    @Test
    @DisplayName("no credentials resolves to the guest account with a fresh session id")
    void anonymousGetsGuestScope() {
      var scope = resolver.resolve(null);

      assertThat(scope.isGuest()).isTrue();
      assertThat(scope.user()).isSameAs(guest);
      assertThat(scope.guestSessionId()).startsWith("guest_");
    }

    @Test
    @DisplayName("Spring's anonymous token is treated as no credentials")
    void springAnonymousTokenIsGuest() {
      SecurityContextHolder.getContext().setAuthentication(
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
      when(accountService.getUserFromAuthentication(any())).thenReturn(null);

      assertThat(resolver.resolve(null).isGuest()).isTrue();
    }

    @Test
    @DisplayName("a signed-in user owns their own row and gets no session id")
    void authenticatedOwnsRow() {
      User user = signIn();

      var scope = resolver.resolve("guest_previoussession1234");

      assertThat(scope.isGuest()).isFalse();
      assertThat(scope.user()).isSameAs(user);
      // Not a guest row, even though the browser still sent its old session id —
      // otherwise the row would be tagged as claimable by whoever holds that id.
      assertThat(scope.guestSessionId()).isNull();
    }

    @Test
    @DisplayName("a valid session id is reused, so a visitor's rows stay linked")
    void sessionIdIsReused() {
      assertThat(resolver.resolve("guest_abc123DEF456").guestSessionId())
          .isEqualTo("guest_abc123DEF456");
    }

    @Test
    @DisplayName("a hostile or malformed session id is replaced, not rejected")
    void malformedSessionIdIsReplaced() {
      for (String hostile : new String[] {
          "<script>alert(1)</script>", "short", "'; DROP TABLE score; --",
          "x".repeat(65), "has spaces in it"
      }) {
        String resolved = resolver.resolve(hostile).guestSessionId();
        assertThat(resolved).startsWith("guest_").doesNotContain(hostile);
      }
    }

    @Test
    @DisplayName("a token resolving to the guest account is refused a signed-in scope")
    void guestAccountCannotAuthenticate() {
      SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken("guest", null));
      when(accountService.getUserFromAuthentication(any())).thenReturn(guest);

      // Otherwise the caller would be scoped to the guest account with no session
      // filter, which matches every guest's rows at once.
      var scope = resolver.resolve(null);
      assertThat(scope.isGuest()).isTrue();
      assertThat(scope.guestSessionId()).isNotBlank();
    }

    @Test
    @DisplayName("a missing guest account fails loudly rather than silently owning nothing")
    void missingGuestAccountIsReported() {
      when(userRepository.findById(OwnerScopeResolver.GUEST_USER_ID))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> resolver.resolve(null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("503");
    }

    @Test
    @DisplayName("a token that cannot be resolved degrades to guest instead of erroring")
    void brokenTokenDegradesToGuest() {
      SecurityContextHolder.getContext().setAuthentication(
          new UsernamePasswordAuthenticationToken("nobody", null));
      when(accountService.getUserFromAuthentication(any()))
          .thenThrow(new IllegalStateException("stale token"));

      assertThat(resolver.resolve(null).isGuest()).isTrue();
    }
  }

  @Nested
  @DisplayName("resolveExisting — reads, and never invents an identity")
  class ResolveExisting {

    @Test
    @DisplayName("an anonymous caller with no session owns nothing yet")
    void anonymousWithoutSessionIsNull() {
      // Minting here would answer "your library" with a fresh empty scope on every
      // request, which is indistinguishable from having lost the visitor's scores.
      assertThat(resolver.resolveExisting(null)).isNull();
    }

    @Test
    @DisplayName("an unusable session id is not silently replaced on a read")
    void anonymousWithJunkSessionIsNull() {
      assertThat(resolver.resolveExisting("short")).isNull();
      assertThat(resolver.resolveExisting("x".repeat(65))).isNull();
    }

    @Test
    @DisplayName("a valid session id resolves to that visitor's guest scope")
    void guestSessionResolves() {
      var scope = resolver.resolveExisting("guest_abc123DEF456");

      assertThat(scope).isNotNull();
      assertThat(scope.isGuest()).isTrue();
      assertThat(scope.guestSessionId()).isEqualTo("guest_abc123DEF456");
    }

    @Test
    @DisplayName("a signed-in caller ignores any session id the browser still holds")
    void authenticatedIgnoresSession() {
      User user = signIn();

      var scope = resolver.resolveExisting("guest_abc123DEF456");

      assertThat(scope.user()).isSameAs(user);
      assertThat(scope.guestSessionId()).isNull();
    }
  }

  @Nested
  @DisplayName("matches — the rule that keeps one guest's rows away from another's")
  class Matches {

    @Test
    @DisplayName("a guest scope matches only its own session")
    void guestScopeIsSessionBound() {
      var scope = new OwnerScope(guest, "guest_mysession12345678", true);

      assertThat(scope.matches(guest, "guest_mysession12345678")).isTrue();
      // Same shared guest account, different visitor. This is the case that would leak.
      assertThat(scope.matches(guest, "guest_someoneelse123456")).isFalse();
      assertThat(scope.matches(guest, null)).isFalse();
    }

    @Test
    @DisplayName("an account scope matches only its own un-tagged rows")
    void accountScopeIgnoresGuestRows() {
      User user = new User();
      user.setId(UUID.randomUUID());
      var scope = new OwnerScope(user, null, false);

      assertThat(scope.matches(user, null)).isTrue();
      assertThat(scope.matches(user, "guest_something1234567")).isFalse();
      assertThat(scope.matches(guest, null)).isFalse();
    }

    @Test
    @DisplayName("a guest scope cannot be constructed without a session id")
    void guestScopeRequiresSession() {
      assertThatThrownBy(() -> new OwnerScope(guest, null, true))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OwnerScope(guest, "  ", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a signed-in scope cannot carry a guest session id")
    void accountScopeRejectsSession() {
      User user = new User();
      user.setId(UUID.randomUUID());
      assertThatThrownBy(() -> new OwnerScope(user, "guest_something1234567", false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
