package org.pianoml.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoreDocumentServiceTest {

  @Mock
  private ScoreDocumentRepository repository;

  private ScoreDocumentService service;
  private UUID scoreId;

  @BeforeEach
  void setUp() {
    service = new ScoreDocumentService(repository, new ObjectMapper());
    scoreId = UUID.randomUUID();
  }

  /** A document where every page was recognised. */
  private static String documentJson(String status, int totalPages, int recognisedPages) {
    StringBuilder pages = new StringBuilder();
    for (int page = 1; page <= totalPages; page++) {
      if (page > 1) {
        pages.append(",");
      }
      pages.append(String.format(
          "{\"page\":%d,\"engine\":\"homr\",\"recognised\":%b,\"confidence\":%s}",
          page, page <= recognisedPages, page <= recognisedPages ? "0.9" : "0.0"));
    }
    return String.format("""
        {
          "score_id": "s1",
          "revision": 1,
          "schema_version": "1.0",
          "analysis_version": "analysis-2026.08",
          "meta": { "title": "Test", "composer": "C", "measure_count": 32 },
          "difficulty": { "global_grade": 5.5 },
          "confidence": {
            "document_confidence": 0.91,
            "status": "%s",
            "pages": [%s]
          }
        }
        """, status, pages);
  }

  @Nested
  @DisplayName("save")
  class Save {

    @Test
    @DisplayName("persists a clean document and denormalises its summary fields")
    void savesCleanDocument() {
      when(repository.findMaxRevision(scoreId)).thenReturn(0);
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      var saved = service.save(scoreId, documentJson("OK", 3, 3), "{\"steps\":[]}");

      assertThat(saved.getRevision()).isEqualTo(1);
      assertThat(saved.getSchemaVersion()).isEqualTo("1.0");
      assertThat(saved.getAnalysisVersion()).isEqualTo("analysis-2026.08");
      assertThat(saved.getReviewStatus()).isEqualTo("OK");
      assertThat(saved.getDocumentConfidence()).isEqualTo(0.91);
      assertThat(saved.getMeasureCount()).isEqualTo(32);
      assertThat(saved.getGlobalGrade()).isEqualTo(5.5);
      assertThat(saved.getSourcePages()).isEqualTo(3);
      assertThat(saved.getRecognisedPages()).isEqualTo(3);
      assertThat(saved.requiresReview()).isFalse();
    }

    @Test
    @DisplayName("allocates the next revision rather than overwriting")
    void allocatesNextRevision() {
      when(repository.findMaxRevision(scoreId)).thenReturn(4);
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      var saved = service.save(scoreId, documentJson("OK", 1, 1), null);

      assertThat(saved.getRevision()).isEqualTo(5);
    }

    @Test
    @DisplayName("REJECTS a document claiming OK while pages were dropped")
    void rejectsInconsistentPageAccounting() {
      // The legacy defect verbatim: pdf2pack.sh exited 0 having recognised 9 of 12 pages,
      // and the resulting short score was treated as complete.
      assertThatThrownBy(() -> service.save(scoreId, documentJson("OK", 12, 9), null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("claims status OK")
          .hasMessageContaining("9 of 12");

      verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("accepts dropped pages when the document admits it needs review")
    void acceptsHonestPartialFailure() {
      when(repository.findMaxRevision(scoreId)).thenReturn(0);
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      var saved = service.save(scoreId, documentJson("REVIEW_REQUIRED", 12, 9), null);

      assertThat(saved.getReviewStatus()).isEqualTo("REVIEW_REQUIRED");
      assertThat(saved.requiresReview()).isTrue();
      assertThat(saved.getRecognisedPages()).isEqualTo(9);
      assertThat(saved.getSourcePages()).isEqualTo(12);
    }

    @Test
    @DisplayName("rejects a document with no confidence report")
    void rejectsMissingConfidence() {
      String json = """
          { "schema_version": "1.0", "analysis_version": "a",
            "meta": { "measure_count": 10 } }
          """;

      assertThatThrownBy(() -> service.save(scoreId, json, null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("no confidence report");
    }

    @Test
    @DisplayName("rejects a document with no measures")
    void rejectsZeroMeasures() {
      String json = documentJson("OK", 1, 1).replace("\"measure_count\": 32", "\"measure_count\": 0");

      assertThatThrownBy(() -> service.save(scoreId, json, null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("no measures");
    }

    @Test
    @DisplayName("rejects malformed JSON")
    void rejectsMalformedJson() {
      assertThatThrownBy(() -> service.save(scoreId, "{not json", null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("rejects an empty payload")
    void rejectsEmptyPayload() {
      assertThatThrownBy(() -> service.save(scoreId, "  ", null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("rejects a document missing schema_version")
    void rejectsMissingSchemaVersion() {
      String json = documentJson("OK", 1, 1).replace("\"schema_version\": \"1.0\",", "");

      assertThatThrownBy(() -> service.save(scoreId, json, null))
          .isInstanceOf(InvalidScoreDocumentException.class)
          .hasMessageContaining("schema_version");
    }

    @Test
    @DisplayName("stores the alignment index alongside the document")
    void storesAlignmentIndex() {
      when(repository.findMaxRevision(scoreId)).thenReturn(0);
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      String index = "{\"ppq\":480,\"steps\":[]}";

      service.save(scoreId, documentJson("OK", 1, 1), index);

      var captor = ArgumentCaptor.forClass(ScoreDocumentEntity.class);
      verify(repository).save(captor.capture());
      assertThat(captor.getValue().getAlignmentIndex()).isEqualTo(index);
    }
  }

  @Nested
  @DisplayName("lookup")
  class Lookup {

    @Test
    @DisplayName("find with no revision returns the latest")
    void findLatestWhenRevisionOmitted() {
      var entity = new ScoreDocumentEntity();
      entity.setRevision(7);
      when(repository.findLatestByScoreId(scoreId)).thenReturn(Optional.of(entity));

      assertThat(service.find(scoreId, null)).hasValueSatisfying(
          e -> assertThat(e.getRevision()).isEqualTo(7));
      verify(repository, never()).findByScoreIdAndRevision(any(), any());
    }

    @Test
    @DisplayName("find with a revision returns that exact revision")
    void findSpecificRevision() {
      var entity = new ScoreDocumentEntity();
      entity.setRevision(3);
      when(repository.findByScoreIdAndRevision(scoreId, 3)).thenReturn(Optional.of(entity));

      assertThat(service.find(scoreId, 3)).isPresent();
      verify(repository, never()).findLatestByScoreId(any());
    }

    @Test
    @DisplayName("parsedDocument throws when nothing is stored")
    void parsedDocumentThrowsWhenAbsent() {
      when(repository.findLatestByScoreId(scoreId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.parsedDocument(scoreId, null))
          .isInstanceOf(ScoreDocumentNotFoundException.class);
    }

    @Test
    @DisplayName("findDocumentJson returns the raw stored JSON unchanged")
    void returnsRawJson() {
      var entity = new ScoreDocumentEntity();
      entity.setDocument("{\"a\":1}");
      when(repository.findLatestByScoreId(scoreId)).thenReturn(Optional.of(entity));

      assertThat(service.findDocumentJson(scoreId, null)).contains("{\"a\":1}");
    }
  }

  @Nested
  @DisplayName("requiresReview")
  class RequiresReview {

    @Test
    @DisplayName("true when the status says so")
    void trueOnStatus() {
      var entity = new ScoreDocumentEntity();
      entity.setReviewStatus("REVIEW_REQUIRED");

      assertThat(entity.requiresReview()).isTrue();
    }

    @Test
    @DisplayName("true when pages are missing even if the status claims otherwise")
    void trueOnMissingPagesRegardlessOfStatus() {
      var entity = new ScoreDocumentEntity();
      entity.setReviewStatus("OK");
      entity.setSourcePages(12);
      entity.setRecognisedPages(9);

      assertThat(entity.requiresReview()).isTrue();
    }

    @Test
    @DisplayName("false for a fully recognised OK document")
    void falseWhenClean() {
      var entity = new ScoreDocumentEntity();
      entity.setReviewStatus("OK");
      entity.setSourcePages(5);
      entity.setRecognisedPages(5);

      assertThat(entity.requiresReview()).isFalse();
    }
  }
}
