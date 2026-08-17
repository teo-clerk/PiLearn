package org.pianoml.backend.learning;

import java.util.List;

/** A contiguous measure range practised as a unit, with its stage ladder. */
public record RoadmapChunk(
    int ordinal,
    int startMeasure,
    int endMeasure,
    int measureCount,
    double difficulty,
    String label,
    int startTempoBpm,
    List<RoadmapStage> stages) {}
