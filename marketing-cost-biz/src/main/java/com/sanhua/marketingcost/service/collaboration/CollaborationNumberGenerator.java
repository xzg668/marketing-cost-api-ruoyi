package com.sanhua.marketingcost.service.collaboration;

public interface CollaborationNumberGenerator {
  String nextTaskNo();

  String nextProductTaskNo();

  String nextGapNo();

  String nextPriceDraftNo();

  String nextReviewNo();

  String nextApprovedResultNo();
}
