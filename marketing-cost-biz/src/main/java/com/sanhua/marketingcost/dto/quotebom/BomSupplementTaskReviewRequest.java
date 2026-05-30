package com.sanhua.marketingcost.dto.quotebom;

public record BomSupplementTaskReviewRequest(
    Long reviewerUserId,
    String reviewerName,
    String comment) {}
