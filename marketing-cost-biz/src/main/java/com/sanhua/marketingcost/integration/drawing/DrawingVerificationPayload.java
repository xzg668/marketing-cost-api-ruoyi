package com.sanhua.marketingcost.integration.drawing;

/** 已校验的摘要及完整证据；原文与可计算模型分开，日志脱敏不能改写它。 */
public record DrawingVerificationPayload(
    DrawingVerificationSummary summary,
    String rawJson,
    String dataJson,
    String plmDataJson,
    String contentFingerprint) {}
