package org.pianoml.backend.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoreDocumentRepository extends JpaRepository<ScoreDocumentEntity, UUID> {

  Optional<ScoreDocumentEntity> findByScoreIdAndRevision(UUID scoreId, Integer revision);

  @Query("""
      select d from ScoreDocumentEntity d
      where d.scoreId = :scoreId
      order by d.revision desc
      limit 1
      """)
  Optional<ScoreDocumentEntity> findLatestByScoreId(@Param("scoreId") UUID scoreId);

  @Query("select coalesce(max(d.revision), 0) from ScoreDocumentEntity d where d.scoreId = :scoreId")
  Integer findMaxRevision(@Param("scoreId") UUID scoreId);

  List<ScoreDocumentEntity> findByScoreIdOrderByRevisionDesc(UUID scoreId);

  @Query("""
      select d from ScoreDocumentEntity d
      where d.reviewStatus <> 'OK'
      order by d.createdAt desc
      """)
  List<ScoreDocumentEntity> findAwaitingReview();
}
