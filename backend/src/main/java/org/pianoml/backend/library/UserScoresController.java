package org.pianoml.backend.library;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.Score;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.progress.ProgressRequest;
import org.pianoml.backend.progress.ScoreProgressService;
import org.pianoml.backend.repository.ScoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The learner's own scores and their progress through them.
 *
 * <p>Open to anonymous visitors, scoped by guest session. Note what that means: the
 * session id is the only credential a guest has, so anyone holding it can read that
 * visitor's library. That is inherent to letting people practise without an account —
 * the id is minted server-side, never guessable, and never shown outside the browser
 * that owns it — but it is the reason a guest library holds only what that browser
 * uploaded, and why signing up is what makes a library durable.
 */
@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
@Slf4j
public class UserScoresController {

  private final OwnerScopeResolver ownerResolver;
  private final LibraryService libraryService;
  private final ScoreProgressService progressService;
  private final ScoreRepository scoreRepository;

  /**
   * Every score this learner uploaded, with progress.
   *
   * <p>A caller we cannot identify gets an empty list, not a 401: a first-time visitor
   * genuinely has an empty library, and an error here would make the page look broken
   * rather than new.
   */
  @GetMapping("/library")
  public ResponseEntity<List<LibraryEntry>> library(
      @RequestParam(required = false) String guestSessionId) {

    OwnerScope scope = ownerResolver.resolveExisting(guestSessionId);
    if (scope == null) {
      return ResponseEntity.ok(List.of());
    }
    return ResponseEntity.ok(libraryService.forOwner(scope));
  }

  /**
   * Record a practice checkpoint.
   *
   * <p>Refuses to write progress against a score the caller does not own. Without that
   * check, anyone could attach rows to any score id and the library would fill with
   * pieces the learner never uploaded.
   */
  @PostMapping(value = "/{scoreId}/progress", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> recordProgress(
      @PathVariable UUID scoreId,
      @RequestBody ProgressRequest request) {

    OwnerScope scope = ownerResolver.resolve(request.guestSessionId());
    Score score = scoreRepository
        .findById(scoreId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "No score with id " + scoreId));

    if (!scope.matches(score.getOwner(), score.getGuestSessionId())) {
      log.warn("refusing progress write on score {} from a scope that does not own it", scoreId);
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This score belongs to a different library.");
    }

    progressService.record(scope, score, request.toUpdate());
    return ResponseEntity.noContent().build();
  }
}
