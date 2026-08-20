package org.pianoml.backend.ingestion;

/**
 * The 202 payload returned when an upload is accepted.
 *
 * <p>{@code scoreId} is what the client polls and ultimately navigates to; {@code jobId}
 * is included for support and log correlation. {@code guestSessionId} is set only for
 * anonymous uploads — the client stores it and sends it back, so a visitor keeps a
 * stable identity across uploads without an account.
 */
public record UploadAcceptedResponse(
    String scoreId, String jobId, String status, String guestSessionId) {}
