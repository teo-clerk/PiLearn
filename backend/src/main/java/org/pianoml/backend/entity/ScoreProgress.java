package org.pianoml.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How far a learner has got with one score.
 *
 * <p>Exists so "Resume practice" can drop someone back on the exact stage and tempo they
 * left, and so the library can show progress without replaying their history.
 *
 * <p>Scoped by (score, owner, guest session). The session is part of the key for the same
 * reason as in {@link UserProfile}: every guest shares one account.
 */
@Entity
@Table(name = "score_progress", schema = "pianoml")
@Data
public class ScoreProgress {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "score_id", nullable = false)
  private Score score;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "guest_session_id", length = 64)
  private String guestSessionId;

  /** Index into the flattened stage ladder — what "Resume" jumps to. */
  @Column(name = "stage_index", nullable = false)
  private Integer stageIndex = 0;

  @Column(name = "chunk_ordinal", nullable = false)
  private Integer chunkOrdinal = 0;

  /**
   * Stages the learner has actually passed.
   *
   * <p>Tracked separately from {@code stageIndex} because they diverge the moment someone
   * skips ahead or steps back: position is where they are, this is what they have earned.
   */
  @Column(name = "stages_completed", nullable = false)
  private Integer stagesCompleted = 0;

  /** Total stages in the roadmap when it was last generated, for the progress fraction. */
  @Column(name = "total_stages", nullable = false)
  private Integer totalStages = 0;

  /** Practice tempo as a percentage of the printed tempo. */
  @Column(name = "tempo_percent", nullable = false)
  private Integer tempoPercent = 100;

  /** Best attempt accuracy so far, 0..1. */
  @Column(name = "mastery_score")
  private Double masteryScore;

  @Column(name = "mastered", nullable = false)
  private Boolean mastered = false;

  @Column(name = "last_practiced_at")
  private OffsetDateTime lastPracticedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
