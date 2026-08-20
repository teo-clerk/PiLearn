package org.pianoml.backend.profile;

/**
 * The questionnaire payload.
 *
 * <p>Strings rather than enums so an unknown value degrades to a default instead of being
 * rejected by Jackson before any of our code runs.
 */
public record ProfileRequest(
    String skillLevel,
    String notationFluency,
    String preferredInput,
    Integer dailyGoalMinutes,
    Boolean onboarded,
    /** Echoed back so an anonymous visitor keeps one identity across requests. */
    String guestSessionId) {}
