/**
 * Canonical ScoreDocument — client-side types.
 *
 * DERIVED, NOT AUTHORED. The source of truth is
 * `worker/pilearn_worker/models/score_document.py`. Regenerate with:
 *
 *   cd worker && python -m pilearn_worker.models.score_document > ../schema/score-document.schema.json
 *   npx json-schema-to-typescript schema/score-document.schema.json \
 *       -o frontend/src/app/core/score/score-document.model.ts
 *
 * Hand-editing this file creates a second definition that will drift from the one the
 * worker validates against. Change the Pydantic model instead.
 *
 * Field names are snake_case because that is what the worker emits; renaming them here
 * would mean a mapping layer that has to be kept in sync — a third place to drift.
 */

export type Hand = 'RIGHT' | 'LEFT';
export type Mode = 'major' | 'minor';
export type SourceKind = 'PDF' | 'IMAGE' | 'MUSICXML' | 'MIDI';
export type OmrEngine = 'homr' | 'audiveris' | 'none';
export type ReviewStatus = 'OK' | 'REVIEW_SUGGESTED' | 'REVIEW_REQUIRED';
export type Severity = 'ERROR' | 'WARNING';

export type TechnicalPattern =
  | 'SCALE_RUN' | 'ARPEGGIO' | 'BROKEN_CHORD' | 'OCTAVE_LEAP'
  | 'TRILL' | 'CROSS_HAND' | 'SYNCOPATION' | 'POLYRHYTHM';

export interface TimeSignature {
  numerator: number;
  denominator: number;
}

export interface KeySignature {
  /** Sharp count; negative is flats (music21 convention). */
  fifths: number;
  mode: Mode;
}

export interface ScoreNote {
  /** Stable and content-derived: survives re-analysis, so old attempts still resolve. */
  id: string;
  midi: number;
  /** Notated spelling, e.g. "C#4". Distinct from `midi`: C#4 and Db4 share pitch 61. */
  spelled: string;
  start_tick: number;
  duration_ticks: number;
  start_sec: number;
  duration_sec: number;
  /** Beats from the bar line, 0-based. 0 is the downbeat. */
  beat_offset: number;
  hand: Hand;
  staff: number;
  voice: number;
  finger: number | null;
  finger_source: 'score' | 'generated' | null;
  /** Set when this note sounds with others in the same hand and voice. */
  chord_id: string | null;
  tied_from_id: string | null;
  tied_to_id: string | null;
  is_grace: boolean;
  is_ornament: boolean;
  articulations: string[];
  dynamic: string | null;
  confidence: number;
}

export interface Voice {
  number: number;
  staff: number;
  hand: Hand;
  notes: ScoreNote[];
}

export interface MeasureDifficulty {
  note_density: number;
  min_ioi: number;
  max_span: number;
  polyphony: number;
  hand_independence: number;
  accidental_rate: number;
  leap_size: number;
  rhythm_complexity: number;
  position_shifts: number;
  ornament_count: number;
  /** 0..10. */
  score: number;
  patterns: TechnicalPattern[];
  weights_version: string;
}

export interface RepeatInfo {
  starts_repeat: boolean;
  ends_repeat: boolean;
  repeat_times: number;
  volta: number | null;
  jump: 'DC' | 'DS' | 'CODA' | 'FINE' | null;
  is_segno: boolean;
  is_coda: boolean;
}

export interface Measure {
  /** 0-based, notation order (NOT performance order — see `playback_order`). */
  index: number;
  /** Printed number; a string because "12a" is legal. */
  number: string;
  start_tick: number;
  end_tick: number;
  start_sec: number;
  end_sec: number;
  time_signature: TimeSignature;
  key_signature: KeySignature;
  tempo_bpm: number;
  is_pickup: boolean;
  repeat: RepeatInfo;
  voices: Voice[];
  difficulty: MeasureDifficulty | null;
  segment_id: string | null;
  confidence: number;
}

export interface Part {
  id: string;
  name: string | null;
  staff_count: number;
  /** Staff number -> hand. Typically { 1: RIGHT, 2: LEFT }. */
  hand_mapping: Record<number, Hand>;
  measures: Measure[];
}

/**
 * One cursor position in PERFORMANCE order (repeats unrolled).
 *
 * Replaces `OsmdArrayElement` from the legacy `desktop/model/model.ts`. The difference
 * that matters: `osmd_cursor_index` is precomputed server-side, so advancing the cursor
 * is a map lookup rather than the multi-pass search the old cursor service ran per load.
 */
export interface TimelineStep {
  index: number;
  /** Notation measure this step belongs to. */
  measure_index: number;
  /** OSMD cursor iterator position. */
  osmd_cursor_index: number;
  start_tick: number;
  duration_ticks: number;
  start_sec: number;
  /** Parallel arrays — same length, same order. */
  note_ids: string[];
  pitches: number[];
  hands: Hand[];
  is_repeat_jump: boolean;
  jump_target_index: number | null;
  alignment_confidence: number;
}

export interface AlignmentIndex {
  ppq: number;
  steps: TimelineStep[];
  /** MIDI tick -> index into `steps`. */
  by_tick: Record<number, number>;
  /** Measure index -> index of that measure's first step. */
  by_measure: Record<number, number>;
  mean_confidence: number;
}

/**
 * A pedagogical practice unit — what a learner works on in one sitting.
 *
 * Distinct from `Segment`: a segment is a musical phrase, a chunk is a practice window.
 * They derive from the same analysis but are not 1:1 — a long phrase splits into several
 * chunks, and a hard bar becomes a chunk of its own inside a phrase.
 */
export interface Chunk {
  id: string;
  ordinal: number;
  /** Inclusive, notation index. */
  start_measure: number;
  /** Inclusive, notation index. */
  end_measure: number;
  measure_count: number;
  /** 0..10, mean of the member measures. */
  difficulty: number;
  kind: 'PRIMARY' | 'MICRO' | 'JOIN' | 'REVIEW';
  label: string;
  boundary_reason: string;
  segment_ids: string[];
  patterns: TechnicalPattern[];
}

export interface Segment {
  id: string;
  start_measure: number;
  end_measure: number;
  kind: 'PHRASE' | 'SECTION' | 'REPEAT_BLOCK';
  boundary_reason: string;
  cadence: string | null;
  confidence: number;
}

export interface ScoreMeta {
  title: string;
  composer: string;
  arranger: string | null;
  key: KeySignature;
  /** [measureIndex, signature] at each change. */
  time_signatures: [number, TimeSignature][];
  /** [measureIndex, bpm] at each change. */
  tempo_map: [number, number][];
  /** Practice target: 100% of the tempo ramp. */
  target_tempo_bpm: number;
  measure_count: number;
  duration_sec: number;
  divisions: number;
  ppq: number;
  has_pickup: boolean;
  has_lyrics: boolean;
}

export interface DifficultySummary {
  global_grade: number;
  mean_measure_difficulty: number;
  p90_measure_difficulty: number;
  hardest_measures: number[];
  weights_version: string;
}

export interface PageResult {
  page: number;
  engine: OmrEngine;
  recognised: boolean;
  confidence: number;
  reason: string | null;
}

export interface Issue {
  code: string;
  severity: Severity;
  detail: string;
  measure: number | null;
  page: number | null;
}

export interface ConfidenceReport {
  document_confidence: number;
  status: ReviewStatus;
  pages: PageResult[];
  issues: Issue[];
}

export interface SourceInfo {
  kind: SourceKind;
  input_hash: string;
  page_count: number | null;
  omr_engine: OmrEngine;
  omr_engine_version: string | null;
  pipeline_version: string;
}

export interface ScoreDocument {
  score_id: string;
  revision: number;
  schema_version: '1.0';
  analysis_version: string;
  source: SourceInfo;
  meta: ScoreMeta;
  parts: Part[];
  /** Measure indices in performance order, repeats and voltas unrolled. */
  playback_order: number[];
  alignment: AlignmentIndex;
  /** Musical phrases — where the music breathes. */
  segments: Segment[];
  /** Pedagogical practice units, consumed by the roadmap. */
  chunks: Chunk[];
  harmony: unknown[];
  difficulty: DifficultySummary | null;
  confidence: ConfidenceReport;
}

// ─────────────────────────────────────────────────────────────────────────────
// Roadmap (from the Spring API, not the worker)
// ─────────────────────────────────────────────────────────────────────────────

export type HandMode = 'RIGHT' | 'LEFT' | 'BOTH';
/**
 * How the transport behaves during a stage.
 *
 * - `RHYTHM` — any key counts; pitch is ignored entirely. The first thing a complete
 *   novice can succeed at, because it asks for one new skill instead of two.
 * - `WAIT` — the transport holds at the current note until the learner plays it. No time
 *   pressure at all; the piece simply does not move on without them.
 * - `FLOW` — runs at tempo regardless, and the learner keeps up.
 */
export type PracticeMode = 'RHYTHM' | 'WAIT' | 'FLOW';

export interface MasteryCriterion {
  minPitchAccuracy: number;
  maxTimingRmsMs: number;
  consecutiveCleanRuns: number;
  maxErrorsPerMeasure: number;
}

export interface RoadmapStage {
  ordinal: number;
  handMode: HandMode;
  tempoBpm: number;
  mode: PracticeMode;
  useMetronome: boolean;
  /** Draw pitch names on the keyboard and the cursor for this stage. */
  showNoteNames: boolean;
  /** Play the opposing hand back while the learner plays theirs. */
  guideOpposingHand: boolean;
  /** What the learner is being asked to do, in their words. */
  label: string;
  criterion: MasteryCriterion;
  estimatedMinutes: number;
}

export interface RoadmapChunk {
  ordinal: number;
  startMeasure: number;
  endMeasure: number;
  measureCount: number;
  difficulty: number;
  label: string;
  startTempoBpm: number;
  stages: RoadmapStage[];
}

export interface Roadmap {
  scoreId: string;
  revision: number;
  title: string;
  composer: string;
  measureCount: number;
  targetTempoBpm: number;
  globalGrade: number | null;
  totalStages: number;
  estimatedMinutes: number;
  estimatedWeeks: number;
  /** True when the score has dropped pages — a roadmap on it teaches the wrong bars. */
  requiresReview: boolean;
  reviewStatus: string;
  chunks: RoadmapChunk[];
}

/** A stage plus the chunk it belongs to — what the practice surface actually needs. */
export interface ResolvedStage {
  chunk: RoadmapChunk;
  stage: RoadmapStage;
  /** Position across the whole roadmap, not within the chunk. */
  globalIndex: number;
}
