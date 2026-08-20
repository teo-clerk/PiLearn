package org.pianoml.backend.identity;

import org.pianoml.backend.entity.User;

/**
 * Who is asking — a signed-in account, or an anonymous visitor within one browser session.
 *
 * <p>Every guest shares a single seeded {@code User} row, so {@code user} alone does NOT
 * identify a guest. {@code guestSessionId} is the discriminator, and any query scoped to
 * "this person's things" must apply both. {@link #matches} exists so callers cannot forget:
 * it is the one place that knows a guest scope needs two columns and an account scope needs
 * one.
 *
 * @param user the owning account — the seeded guest account when {@code isGuest}
 * @param guestSessionId opaque per-browser id; null for a signed-in account
 * @param isGuest true when this is an anonymous visitor
 */
public record OwnerScope(User user, String guestSessionId, boolean isGuest) {

  public OwnerScope {
    if (user == null) {
      throw new IllegalArgumentException("an owner scope always has a user");
    }
    if (isGuest && (guestSessionId == null || guestSessionId.isBlank())) {
      throw new IllegalArgumentException("a guest scope needs a session id");
    }
    if (!isGuest && guestSessionId != null) {
      throw new IllegalArgumentException(
          "a signed-in scope must not carry a guest session id — it would tag the row as "
              + "claimable by whoever holds that id");
    }
  }

  /**
   * Does a stored row belong to this scope?
   *
   * <p>For an account: same user. For a guest: same user AND same session — without the
   * session check every visitor would see every other visitor's rows, because they all
   * share the guest account.
   */
  public boolean matches(User rowUser, String rowGuestSessionId) {
    if (rowUser == null || !user.getId().equals(rowUser.getId())) {
      return false;
    }
    return isGuest
        ? guestSessionId.equals(rowGuestSessionId)
        : rowGuestSessionId == null;
  }
}
