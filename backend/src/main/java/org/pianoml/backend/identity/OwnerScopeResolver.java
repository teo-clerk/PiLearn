package org.pianoml.backend.identity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.repository.UserRepository;
import org.pianoml.backend.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Decides who the caller is.
 *
 * <p>An anonymous visitor must be able to upload a PDF, practise it and see it in their
 * library — requiring an account before someone has seen the product work is the wrong
 * order. So a request with no credentials resolves to a seeded guest account (Liquibase
 * 022) plus an opaque session id.
 *
 * <p>The guest account exists rather than making the owning foreign keys nullable: those
 * columns are correctly NOT NULL for every other row, and relaxing a real constraint to
 * accommodate one case is a worse trade than one sentinel row.
 *
 * <p><b>Every guest shares this one account.</b> That is why this class hands back an
 * {@link OwnerScope} rather than a bare {@code User} — see {@link OwnerScope#matches}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OwnerScopeResolver {

  /** Fixed id of the seeded guest account. Must match Liquibase changeset 022. */
  public static final UUID GUEST_USER_ID =
      UUID.fromString("00000000-0000-0000-0000-00000000feed");

  /** Opaque, bounded, and safe to echo back in JSON or store in a column. */
  private static final String SESSION_ID_PATTERN = "^[A-Za-z0-9_-]{8,64}$";

  private final UserRepository userRepository;
  private final AccountService accountService;

  /**
   * Resolve the caller, minting a guest session id when the visitor has none.
   *
   * <p>Use for a write that establishes ownership (an upload, a first profile save).
   *
   * @param requestedSessionId a session id supplied by the client, or null to mint one
   */
  public OwnerScope resolve(String requestedSessionId) {
    User authenticated = authenticatedUser();
    if (authenticated != null) {
      return new OwnerScope(authenticated, null, false);
    }
    return new OwnerScope(guestAccount(), normalise(requestedSessionId), true);
  }

  /**
   * Resolve the caller without minting anything.
   *
   * <p>Use for a read. An anonymous visitor with no usable session id owns nothing yet, and
   * inventing an id here would answer "your library" with a brand-new empty scope every
   * request — indistinguishable from a bug that lost their scores.
   *
   * @return null when the caller is anonymous and supplied no valid session id
   */
  public OwnerScope resolveExisting(String requestedSessionId) {
    User authenticated = authenticatedUser();
    if (authenticated != null) {
      return new OwnerScope(authenticated, null, false);
    }
    if (requestedSessionId == null || !requestedSessionId.matches(SESSION_ID_PATTERN)) {
      return null;
    }
    return new OwnerScope(guestAccount(), requestedSessionId, true);
  }

  /**
   * The signed-in user, or null for an anonymous caller.
   *
   * <p>Deliberately not gated on {@code isAuthenticated()}: that flag is false for tokens
   * built with the two-argument constructor, so trusting it would silently demote real
   * users to guests. Whether {@link AccountService} can resolve a user is the authority.
   */
  private User authenticatedUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }

    User user;
    try {
      user = accountService.getUserFromAuthentication(authentication);
    } catch (Exception e) {
      // A malformed or expired token is not an error here — it just means guest.
      log.debug("could not resolve an authenticated user; treating as guest: {}", e.getMessage());
      return null;
    }

    // The guest account is not a login. If a token ever resolved to it, treating the
    // caller as that account would hand them every guest's rows at once.
    return user == null || GUEST_USER_ID.equals(user.getId()) ? null : user;
  }

  private User guestAccount() {
    return userRepository
        .findById(GUEST_USER_ID)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Guest access is unavailable because the guest account is missing. "
                + "Run the database migrations (Liquibase changeset 022)."));
  }

  /**
   * Accept only an opaque, bounded token.
   *
   * <p>The value is client-supplied and is written to the database and returned in
   * responses. Anything that does not match is replaced with a fresh id rather than
   * rejected — a visitor should never see an error because their browser sent an odd
   * cookie.
   */
  private String normalise(String requested) {
    if (requested == null || !requested.matches(SESSION_ID_PATTERN)) {
      return "guest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
    return requested;
  }
}
