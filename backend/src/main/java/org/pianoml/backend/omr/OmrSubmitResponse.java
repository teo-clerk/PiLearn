package org.pianoml.backend.omr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The worker's 202 payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OmrSubmitResponse(
    String jobId,
    String scoreId,
    String status,
    boolean deduplicated) {}
