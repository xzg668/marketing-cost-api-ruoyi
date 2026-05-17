package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class BindingCandidateBuildResult {
  private final List<BindingCandidate> candidates = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();

  public List<BindingCandidate> getCandidates() {
    return candidates;
  }

  public List<String> getWarnings() {
    return warnings;
  }
}
