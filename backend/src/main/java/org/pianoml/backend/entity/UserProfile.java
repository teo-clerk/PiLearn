package org.pianoml.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.pianoml.backend.profile.InputMethod;
import org.pianoml.backend.profile.NotationFluency;
import org.pianoml.backend.profile.SkillLevel;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What we know about how a learner plays.
 *
 * <p>Scoped by (owner, guest session) rather than by owner alone: every anonymous visitor
 * shares one seeded account, so a profile keyed on {@code user_id} would give the first
 * guest's answers to every guest afterwards. See {@code OwnerScope}.
 */
@Entity
@Table(name = "user_profile", schema = "pianoml")
@Data
public class UserProfile {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /** Set only for an anonymous visitor; null for a signed-in account. */
  @Column(name = "guest_session_id", length = 64)
  private String guestSessionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "skill_level", length = 20, nullable = false)
  private SkillLevel skillLevel = SkillLevel.DEFAULT;

  @Enumerated(EnumType.STRING)
  @Column(name = "notation_fluency", length = 20, nullable = false)
  private NotationFluency notationFluency = NotationFluency.DEFAULT;

  @Enumerated(EnumType.STRING)
  @Column(name = "preferred_input", length = 20, nullable = false)
  private InputMethod preferredInput = InputMethod.DEFAULT;

  /** Target minutes of practice per day. */
  @Column(name = "daily_goal_minutes", nullable = false)
  private Integer dailyGoalMinutes = 15;

  /**
   * Whether the learner has actually answered the questionnaire.
   *
   * <p>Distinguishes "chose the defaults" from "never asked" — without it the onboarding
   * would either never appear or appear forever, since both states otherwise look like a
   * profile holding default values.
   */
  @Column(name = "onboarded", nullable = false)
  private Boolean onboarded = false;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
