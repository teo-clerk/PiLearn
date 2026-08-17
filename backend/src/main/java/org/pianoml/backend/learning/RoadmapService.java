package org.pianoml.backend.learning;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.document.ScoreDocumentEntity;
import org.pianoml.backend.document.ScoreDocumentNotFoundException;
import org.pianoml.backend.document.ScoreDocumentService;
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

  /** Tempo ladder multiplier per rung (PRODUCT_SPEC §5.2). */
  private static final double TEMPO_RAMP_FACTOR = 1.10;
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

  private List<RoadmapChunk> buildChunks(
      JsonNode document, double targetTempo, RoadmapParams params) {

    JsonNode chunkNodes = document.path("chunks");
    List<RoadmapChunk> chunks = new ArrayList<>();

    // Documents analysed before chunking existed still have measures; fall back to a
    // single whole-piece chunk rather than returning an empty roadmap.
    if (!chunkNodes.isArray() || chunkNodes.isEmpty()) {
      int measureCount = document.path("meta").path("measure_count").asInt(0);
      if (measureCount == 0) {
        return chunks;
      }
      log.info("document has no chunks; falling back to a single whole-piece chunk");
      chunks.add(buildChunk(0, 0, measureCount - 1, 5.0,
          "Bars 1-" + measureCount, targetTempo, params, hasBothHands(document)));
      return chunks;
    }

    int ordinal = 0;
    for (JsonNode node : chunkNodes) {
      chunks.add(buildChunk(
          ordinal++,
          node.path("start_measure").asInt(),
          node.path("end_measure").asInt(),
          node.path("difficulty").asDouble(5.0),
          node.path("label").asText(""),
          targetTempo,
          params,
          hasBothHands(document)));
    }
    return chunks;
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

  private RoadmapChunk buildChunk(
      int ordinal, int startMeasure, int endMeasure, double difficulty,
      String label, double targetTempo, RoadmapParams params, boolean bothHands) {

    double startPct = Math.clamp(
        1.0 - DIFFICULTY_TEMPO_PENALTY * difficulty, MIN_START_PCT, MAX_START_PCT);
    int startTempo = roundToGrid(targetTempo * startPct);
    int measures = endMeasure - startMeasure + 1;

    List<RoadmapStage> stages = new ArrayList<>();
    int stageOrdinal = 0;

    if (params.handsSeparateFirst() && bothHands) {
      stages.add(stage(stageOrdinal++, "RIGHT", startTempo, "WAIT",
          criterion(0.95, 0, 2), measures));
      stages.add(stage(stageOrdinal++, "LEFT", startTempo, "WAIT",
          criterion(0.95, 0, 2), measures));
    }

    stages.add(stage(stageOrdinal++, "BOTH", startTempo, "WAIT",
        criterion(0.95, 0, 2), measures));
    stages.add(stage(stageOrdinal++, "BOTH", startTempo, "FLOW",
        criterion(0.92, 120, 3), measures));

    // Tempo ramp: multiplicative rungs up to the target, each on the bpm grid.
    int rung = startTempo;
    while (rung < roundToGrid(targetTempo)) {
      rung = Math.max(rung + TEMPO_GRID_BPM, roundToGrid(rung * TEMPO_RAMP_FACTOR));
      int capped = Math.min(rung, roundToGrid(targetTempo));
      boolean isFinal = capped >= roundToGrid(targetTempo);
      stages.add(stage(
          stageOrdinal++, "BOTH", capped, "FLOW",
          isFinal ? criterion(0.95, 80, 1) : criterion(0.90, 100, 1),
          measures));
      if (isFinal) {
        break;
      }
    }

    return new RoadmapChunk(
        ordinal, startMeasure, endMeasure, measures,
        Math.round(difficulty * 100.0) / 100.0, label, startTempo, stages);
  }

  private RoadmapStage stage(
      int ordinal, String hand, int tempoBpm, String mode,
      MasteryCriterion criterion, int measures) {
    return new RoadmapStage(
        ordinal, hand, tempoBpm, mode, !"WAIT".equals(mode), criterion,
        estimateStageMinutes(measures, criterion.consecutiveCleanRuns()));
  }

  private MasteryCriterion criterion(double accuracy, int maxRmsMs, int cleanRuns) {
    return new MasteryCriterion(accuracy, maxRmsMs, cleanRuns, 2);
  }

  /** Rough: a run takes roughly 4 s per bar, and a stage needs several attempts. */
  private int estimateStageMinutes(int measures, int cleanRuns) {
    double runSeconds = measures * 4.0;
    double attempts = Math.max(cleanRuns, 1) * 2.5;
    return Math.max(2, (int) Math.ceil(runSeconds * attempts / 60.0));
  }

  private int estimateWeeks(int totalMinutes) {
    final int minutesPerWeek = 150;   // ~5 sessions of 30 minutes
    return Math.max(1, (int) Math.ceil((double) totalMinutes / minutesPerWeek));
  }

  private int roundToGrid(double bpm) {
    return (int) (Math.round(bpm / TEMPO_GRID_BPM) * TEMPO_GRID_BPM);
  }
}
