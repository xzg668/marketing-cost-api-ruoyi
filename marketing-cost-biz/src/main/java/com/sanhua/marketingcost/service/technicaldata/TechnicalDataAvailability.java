package com.sanhua.marketingcost.service.technicaldata;

/** 来源检查与办理状态分开；查询失败、未核实都不能当成资料不存在。 */
public enum TechnicalDataAvailability {
  AVAILABLE,
  MISSING,
  UNCONFIRMED,
  ERROR
}
