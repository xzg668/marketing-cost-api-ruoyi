package com.sanhua.marketingcost.integration.oa;

import java.util.Set;

/** 由服务端调用方配置确定；不能从请求正文获得角色、租户或授权范围。 */
public record OaPeer(String sourceSystem, String environment, Set<String> businessUnits) {}
