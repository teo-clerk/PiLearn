package org.pianoml.backend.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentNotFoundException;
import org.pianoml.backend.document.ScoreDocumentService;
import org.pianoml.backend.profile.SkillLevel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the practice roadmap from a ScoreDocument.
 *
 * <p>Implements the stage ladder from PRODUCT_SPEC §5.2. The chunk boundaries themselves are
 * computed by the worker (pedagogy/difficulty.py) and carried in the document — chunking needs
 * phrase and cadence analysis that lives with music21, and duplicating it here would create a
 * second implementation that drifts from the tested one.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoadmapService {

  private final ScoreDocumentService documentService;
  private final ObjectMapper objectMapper;
  private final ChunkPlanner chunkPlanner;
  private final StageLadderBuilder ladderBuilder;

  private static final int TEMPO_GRID_BPM = 5;
  private static final double MIN_START_PCT = 0.45;
  private static final double MAX_START_PCT = 0.85;
  private static final double DIFFICULTY_TEMPO_PENALTY = 0.06;

  @Transactional(readOnly = true)
  public RoadmapResponse generate(UUID scoreId, Integer revision, RoadmapParams params) {
    ScoreDocumentEntity entity = documentService.find(scoreId, revision)
        .orElseThrow(() -> new ScoreDocumentNotFoundException(scoreId, revision));

    JsonNode document = documentService.parsedDocument(scoreId, revision);
    JsonNode meta = document.path("meta");

    double targetTempo = meta.path("target_tempo_bpm").asDouble(120.0) * params.goalTempoPct();
    int measureCount = meta.path("measure_count").asInt(0);

    List<RoadmapChunk> chunks = buildChunks(document, targetTempo, params);

    int totalStages = chunks.stream().mapToInt(c -> c.stages().size()).sum();
    int estimatedMinutes = chunks.stream()
        .flatMap(c -> c.stages().stream())
        .mapToInt(RoadmapStage::estimatedMinutes)
        .sum();

    return new RoadmapResponse(
        scoreId.toString(),
        entity.getRevision(),
        meta.path("title").asText(""),
        meta.path("composer").asText(""),
        measureCount,
        Math.round(targetTempo * 10.0) / 10.0,
        entity.getGlobalGrade(),
        totalStages,
        estimatedMinutes,
        estimateWeeks(estimatedMinutes),
        entity.requiresReview(),
        entity.getReviewStatus(),
        chunks);
  }

  /**
   * Plan the practice units, then build a stage ladder for each.
   *
   * <p>Two steps rather than one because they answer different questions: what to
   * practise and in what size pieces ({@link ChunkPlanner}), and what to do with each
   * piece ({@link StageLadderBuilder}). Both depend on the learner's level, and neither
   * is a variation of the other.
   */
  private List<RoadmapChunk> buildChunks(
      JsonNode document, double targetTempo, RoadmapParams params) {

    List<ScoreDocumentChunk> documentChunks = readChunks(document);
    int measureCount = document.path("meta").path("measure_count").asInt(0);
    boolean bothHands = hasBothHands(document);

    // Fallback for documents analysed before chunking existed. Deliberately WARN, not
    // INFO: this path produces a single undifferentiated practice unit for the whole
    // piece, which is a badly degraded roadmap. It ran silently for every score while
    // the worker emitted `segments` and no `chunks` field at all, and nobody noticed.
    if (documentChunks.isEmpty()) {
      if (measureCount == 0) {
        log.warn("document has neither chunks nor measures; returning an empty roadmap");
        return List.of();
      }
      log.warn(
          "document has no 'chunks' field — falling back to one whole-piece chunk over "
              + "{} measures. Re-ingest the score to get a real practice breakdown.",
          measureCount);
      documentChunks = List.of(new ScoreDocumentChunk(
          "fallback-0", 0, 0, measureCount - 1, measureCount, 5.0, "PHRASE",
          "Bars 1-" + measureCount, "FALLBACK", List.of(), List.of()));
    }

    List<PracticeUnit> units =
        chunkPlanner.plan(documentChunks, measureCount, params.skillLevel());

    List<RoadmapChunk> chunks = new ArrayList<>();
    for (PracticeUnit unit : units) {
      int startTempo = startTempoFor(unit.difficulty(), targetTempo, params.skillLevel());
      List<RoadmapStage> stages = ladderBuilder.build(
          unit, params.skillLevel(), targetTempo, startTempo,
          bothHands, params.handsSeparateFirst());

      chunks.add(new RoadmapChunk(
          unit.ordinal(), unit.startMeasure(), unit.endMeasure(), unit.measureCount(),
          Math.round(unit.difficulty() * 100.0) / 100.0, unit.label(), startTempo, stages));
    }
    return chunks;
  }

  /**
   * Where the tempo ladder starts for one unit.
   *
   * <p>Harder music starts slower. A beginner starts slower still and ignores the
   * difficulty adjustment entirely — at 40% of target, a difficult bar and an easy one
   * are both simply "slow", and scaling further would drop below a playable pulse.
   */
  private int startTempoFor(double difficulty, double targetTempo, SkillLevel level) {
    if (!level.atLeast(SkillLevel.INTERMEDIATE)) {
      return Math.max(30, roundToGrid(targetTempo * 0.40));
    }
    double startPct = Math.clamp(
        1.0 - DIFFICULTY_TEMPO_PENALTY * difficulty, MIN_START_PCT, MAX_START_PCT);
    return roundToGrid(targetTempo * startPct);
  }

  /**
   * Deserialise the worker's pedagogical chunks.
   *
   * <p>A malformed chunk array is treated as absent rather than fatal: a roadmap built
   * from the fallback is degraded but usable, whereas a 500 makes the score unopenable.
   */
  private List<ScoreDocumentChunk> readChunks(JsonNode document) {
    JsonNode node = document.path("chunks");
    if (!node.isArray() || node.isEmpty()) {
      return List.of();
    }
    try {
      return objectMapper.convertValue(node, new TypeReference<List<ScoreDocumentChunk>>() {});
    } catch (IllegalArgumentException e) {
      log.warn("could not deserialise document chunks; using the fallback: {}", e.getMessage());
      return List.of();
    }
  }

  private boolean hasBothHands(JsonNode document) {
    for (JsonNode part : document.path("parts")) {
      JsonNode mapping = part.path("hand_mapping");
      if (mapping.isObject() && mapping.size() >= 2) {
        return true;
      }
    }
    return false;
  }

  private int estimateWeeks(int totalMinutes) {
    final int minutesPerWeek = 150;   // ~5 sessions of 30 minutes
    return Math.max(1, (int) Math.ceil((double) totalMinutes / minutesPerWeek));
  }

  private int roundToGrid(double bpm) {
    return (int) (Math.round(bpm / TEMPO_GRID_BPM) * TEMPO_GRID_BPM);
  }
}
