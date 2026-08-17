package org.pianoml.backend.omr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Worker readiness. `checks` names the specific capability that is missing. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OmrHealth(String status, String version, Map<String, Boolean> checks) {}
