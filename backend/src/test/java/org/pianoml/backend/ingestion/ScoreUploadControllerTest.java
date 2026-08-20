package org.pianoml.backend.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.Author;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.entity.User;
import org.pianoml.backend.omr.OmrSubmission;
import org.pianoml.backend.omr.OmrSubmitRequest;
import org.pianoml.backend.omr.OmrSubmitResponse;
import org.pianoml.backend.omr.OmrWorkerClient;
import org.pianoml.backend.repository.ScoreRepository;
import org.pianoml.backend.storage.ScoreStorageException;
import org.pianoml.backend.storage.ScoreStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Upload endpoint behaviour.
 *
 * <p>The properties pinned here are the ones whose absence would lose a file or hand the
 * learner a broken score: the original is stored BEFORE the worker is called, a rejected
 * submission marks the score FAILED rather than leaving it stuck in QUEUED, and every
 * validation failure carries the status a client can act on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoreUploadControllerTest {

  @Mock private ScoreStorageService storageService;
  @Mock private ScoreIngestionService ingestionService;
  @Mock private OmrWorkerClient workerClient;
  @Mock private ScoreRepository scoreRepository;
  @Mock private OwnerScopeResolver ownerResolver;
  @Mock private AuthorResolver authorResolver;

  private ScoreUploadController controller;
  private User owner;
  private Author author;
  private UUID scoreId;

  @BeforeEach
  void setUp() {
    controller = new ScoreUploadController(
        storageService, ingestionService, workerClient, scoreRepository, ownerResolver,
        authorResolver);

    owner = new User();
    owner.setId(UUID.randomUUID());
    scoreId = UUID.randomUUID();

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(owner.getId().toString(), null));
    when(ownerResolver.resolve(any()))
        .thenReturn(new OwnerScope(owner, null, false));

    author = new Author();
    author.setId(UUID.randomUUID());
    author.setName("Unknown");
    when(authorResolver.resolve(any())).thenReturn(author);

    when(scoreRepository.save(any(Score.class))).thenAnswer(invocation -> {
      Score score = invocation.getArgument(0);
      if (score.getId() == null) score.setId(scoreId);
      return score;
    });
  }

  private MockMultipartFile pdf(String name, byte[] content) {
    return new MockMultipartFile("file", name, "application/pdf", content);
  }

  private void workerAccepts() {
    when(workerClient.submitBytes(any(), any(), any()))
        .thenReturn(OmrSubmission.accepted(
            new OmrSubmitResponse("job_abc", scoreId.toString(), "QUEUED", false)));
  }

  @Nested
  @DisplayName("happy path")
  class HappyPath {

    @Test
    @DisplayName("returns 202 with scoreId, jobId and QUEUED")
    void acceptsUpload() {
      workerAccepts();

      var response = controller.upload(pdf("score.pdf", "%PDF-1.7 data".getBytes()),
          "My Piece", "Chopin", false, null);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().jobId()).isEqualTo("job_abc");
      assertThat(response.getBody().status()).isEqualTo(ScoreIngestionService.STATUS_QUEUED);
      assertThat(response.getBody().scoreId()).isEqualTo(scoreId.toString());
    }

    @Test
    @DisplayName("stores the original BEFORE calling the worker")
    void storesBeforeSubmitting() {
      workerAccepts();

      controller.upload(pdf("score.pdf", "%PDF-1.7".getBytes()), null, null, false, null);

      // Order matters: if the worker call ran first and storage then failed, the file
      // would be gone while a job was already running against it.
      var order = inOrder(storageService, workerClient);
      order.verify(storageService).putRaw(any(), eq(".pdf"), any(), any());
      order.verify(workerClient).submitBytes(any(), any(), any());
    }

    @Test
    @DisplayName("marks the score queued with the returned job id")
    void marksQueued() {
      workerAccepts();

      controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null);

      verify(ingestionService).markQueued(scoreId, "job_abc");
    }

    @Test
    @DisplayName("uploads are private by default")
    void privateByDefault() {
      workerAccepts();

      controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null);

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(captor.capture());
      assertThat(captor.getValue().getRights()).isEqualTo("USER_UPLOAD_PRIVATE");
      assertThat(captor.getValue().getPublicDomain()).isFalse();
    }

    @Test
    @DisplayName("falls back to the filename when no title is given")
    void derivesTitleFromFilename() {
      workerAccepts();

      controller.upload(pdf("Nocturne in E flat.pdf", "%PDF".getBytes()), null, null, false, null);

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(captor.capture());
      assertThat(captor.getValue().getTitle()).isEqualTo("Nocturne in E flat");
    }

    @Test
    @DisplayName("accepts MusicXML and MIDI, not only PDF")
    void acceptsSymbolicFormats() {
      workerAccepts();

      for (String name : new String[] {"a.musicxml", "b.xml", "c.mxl", "d.mid", "e.midi"}) {
        var file = new MockMultipartFile("file", name, "application/octet-stream", "x".getBytes());
        assertThat(controller.upload(file, null, null, false, null).getStatusCode())
            .isEqualTo(HttpStatus.ACCEPTED);
      }
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("rejects an empty upload")
    void rejectsEmpty() {
      var empty = new MockMultipartFile("file", "score.pdf", "application/pdf", new byte[0]);

      assertThatThrownBy(() -> controller.upload(empty, null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("400");
    }

    @Test
    @DisplayName("rejects an unsupported extension with 415")
    void rejectsWrongExtension() {
      var exe = new MockMultipartFile("file", "virus.exe", "application/octet-stream", "x".getBytes());

      assertThatThrownBy(() -> controller.upload(exe, null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("415");
    }

    @Test
    @DisplayName("rejects an image even when named .pdf")
    void rejectsImageContentType() {
      var image = new MockMultipartFile("file", "score.pdf", "image/png", "x".getBytes());

      assertThatThrownBy(() -> controller.upload(image, null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("415");
    }

    @Test
    @DisplayName("rejects a file over 50 MB with 413")
    void rejectsOversized() {
      var big = new MockMultipartFile(
          "file", "huge.pdf", "application/pdf",
          new byte[(int) ScoreUploadController.MAX_BYTES + 1]);

      assertThatThrownBy(() -> controller.upload(big, null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("413");
    }

    @Test
    @DisplayName("nothing is stored or submitted when validation fails")
    void rejectionTouchesNothing() {
      var exe = new MockMultipartFile("file", "x.exe", "application/octet-stream", "x".getBytes());

      assertThatThrownBy(() -> controller.upload(exe, null, null, false, null))
          .isInstanceOf(ResponseStatusException.class);

      verifyNoInteractions(storageService, workerClient, ingestionService);
      verify(scoreRepository, never()).save(any());
    }

    @Test
    @DisplayName("the score is given an author — author_id is NOT NULL in the database")
    void scoreAlwaysHasAnAuthor() {
      workerAccepts();

      controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null);

      ArgumentCaptor<Score> saved = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(saved.capture());
      assertThat(saved.getValue().getAuthor()).isSameAs(author);
    }

    @Test
    @DisplayName("an anonymous visitor can upload, and the session id comes back")
    void guestUploadIsAccepted() {
      User guest = new User();
      guest.setId(OwnerScopeResolver.GUEST_USER_ID);
      when(ownerResolver.resolve(any()))
          .thenReturn(new OwnerScope(guest, "guest_abc123def456", true));
      workerAccepts();

      var response =
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null);

      assertThat(response.getStatusCode().value()).isEqualTo(202);
      // Returned so the browser can keep the same identity across uploads.
      assertThat(response.getBody().guestSessionId()).isEqualTo("guest_abc123def456");

      ArgumentCaptor<Score> saved = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(saved.capture());
      assertThat(saved.getValue().getGuestSessionId()).isEqualTo("guest_abc123def456");
      assertThat(saved.getValue().getOwner().getId()).isEqualTo(OwnerScopeResolver.GUEST_USER_ID);
    }

    @Test
    @DisplayName("a signed-in upload carries no guest session")
    void signedInUploadHasNoGuestSession() {
      workerAccepts();

      var response =
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null);

      assertThat(response.getBody().guestSessionId()).isNull();

      ArgumentCaptor<Score> saved = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(saved.capture());
      assertThat(saved.getValue().getGuestSessionId()).isNull();
      assertThat(saved.getValue().getOwner()).isSameAs(owner);
    }
  }

  @Nested
  @DisplayName("downstream failures")
  class DownstreamFailures {

    @Test
    @DisplayName("a worker outage is reported as retryable (503)")
    void workerUnreachable() {
      when(workerClient.submitBytes(any(), any(), any()))
          .thenReturn(OmrSubmission.failure(
              scoreId.toString(), "WORKER_UNREACHABLE", "connection refused"));

      assertThatThrownBy(() ->
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("503");
    }

    @Test
    @DisplayName("a worker rejection is reported as 502, not retryable")
    void workerRejects() {
      when(workerClient.submitBytes(any(), any(), any()))
          .thenReturn(OmrSubmission.failure(
              scoreId.toString(), "WORKER_REJECTED", "encrypted PDF"));

      assertThatThrownBy(() ->
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("502");
    }

    @Test
    @DisplayName("a rejected submission leaves the score FAILED, not stuck in QUEUED")
    void rejectionMarksFailed() {
      when(workerClient.submitBytes(any(), any(), any()))
          .thenReturn(OmrSubmission.failure(scoreId.toString(), "WORKER_REJECTED", "bad"));

      assertThatThrownBy(() ->
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null))
          .isInstanceOf(ResponseStatusException.class);

      var captor = ArgumentCaptor.forClass(Score.class);
      verify(scoreRepository, atLeastOnce()).save(captor.capture());
      assertThat(captor.getValue().getProcessingStatus())
          .isEqualTo(ScoreIngestionService.STATUS_FAILED);
      verify(ingestionService, never()).markQueued(any(), any());
    }

    @Test
    @DisplayName("a storage outage is 503 and never reaches the worker")
    void storageDown() {
      doThrow(new ScoreStorageException("bucket unavailable"))
          .when(storageService).putRaw(any(), any(), any(), any());

      assertThatThrownBy(() ->
          controller.upload(pdf("score.pdf", "%PDF".getBytes()), null, null, false, null))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("503");

      verifyNoInteractions(workerClient);
    }
  }
}
