package com.sanhua.marketingcost.service.collaboration;

public record CollaborationScope(String businessUnitType, String applicableOrgCode) {

  public CollaborationScope {
    businessUnitType = requireText(businessUnitType, "业务单元");
    applicableOrgCode = requireText(applicableOrgCode, "适用组织");
  }

  public static String requireBusinessUnit(String value) {
    return requireText(value, "业务单元");
  }

  static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "不能为空");
    }
    return value.trim();
  }
}
