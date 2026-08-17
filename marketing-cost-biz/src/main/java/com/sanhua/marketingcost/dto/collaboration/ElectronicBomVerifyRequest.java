package com.sanhua.marketingcost.dto.collaboration;

/** 前端只能请求回取；不存在 completed/valid 等可伪造成功字段。 */
public record ElectronicBomVerifyRequest(
    Integer expectedVersion,
    String bomPurpose,
    String remark) {}
