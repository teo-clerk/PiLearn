package org.pianoml.backend.learning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.document.ScoreDocumentNotFoundException;
import org.pianoml.backend.identity.OwnerScope;
import org.pianoml.backend.identity.OwnerScopeResolver;
import org.pianoml.backend.profile.SkillLevel;
import org.pianoml.backend.profile.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Practice roadmap for a score.
 *
 * <p>Read-only in Phase 2: the roadmap is derived from the ScoreDocument on request. Per-user
 * plan persistence, editing and adaptation are Phase 3 (P3-T11 onwards) — deliberately not
 * stubbed here, because a persisted plan whose adaptation rules do not exist yet would be a
 * promise the system cannot keep.
 */
@RestController
@RequestMapping("/api/v1/scores")
@RequiredArgsConstructor
@Slf4j
public class RoadmapController {

  private final RoadmapService roadmapService;
  private final OwnerScopeResolver ownerResolver;
  private final UserProfileService profileService;

  /**
   * Generate the practice roadmap: chunks, hands-separate ladders and the tempo ramp.
   *
   * @param id score id
   * @param revision optional document revision; defaults to the latest
   * @param goalTempoPct target tempo as a fraction of the printed tempo
   * @param handsSeparateFirst whether to generate hands-separate stages before hands-together
   * @param skillLevel overrides the learner's stored level; omit to use their profile
   * @param guestSessionId identifies an anonymous visitor, so their profile is found
   */
  @GetMapping("/{id}/roadmap")
  public ResponseEntity<RoadmapResponse> getRoadmap(
      @PathVariable UUID id,
      @RequestParam(required = false) Integer revision,
      @RequestParam(defaultValue = "1.0") double goalTempoPct,
      @RequestParam(defaultValue = "true") boolean handsSeparateFirst,
      @RequestParam(required = false) String skillLevel,
      @RequestParam(required = false) String guestSessionId) {

    if (goalTempoPct <= 0 || goalTempoPct > 2.0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "goalTempoPct must be in (0, 2.0]");
    }

    try {
      SkillLevel level = resolveLevel(skillLevel, guestSessionId);
      RoadmapResponse roadmap = roadmapService.generate(
          id, revision, new RoadmapParams(goalTempoPct, handsSeparateFirst, level));

      // A roadmap built on a score with dropped pages teaches the wrong bars. Serve it —
      // partial practice is better than none — but never without saying so.
      if (roadmap.requiresReview()) {
        log.warn("serving roadmap for score {} that still requires review", id);
      }
      return ResponseEntity.ok(roadmap);

    } catch (ScoreDocumentNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  /**
   * Which ladder to build.
   *
   * <p>An explicit query parameter wins, so the surface can preview another level without
   * writing to the profile. Otherwise the learner's stored answer is used, and someone we
   * have never met gets the default — never an error, because a roadmap is more useful
   * than a prompt to fill in a form.
   */
  private SkillLevel resolveLevel(String requested, String guestSessionId) {
    if (requested != null && !requested.isBlank()) {
      return SkillLevel.parse(requested);
    }
    OwnerScope scope = ownerResolver.resolveExisting(guestSessionId);
    if (scope == null) {
      return SkillLevel.DEFAULT;
    }
    return profileService.find(scope)
        .map(profile -> profile.getSkillLevel())
        .orElse(SkillLevel.DEFAULT);
  }
}
