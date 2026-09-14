package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataSalaryItemRequest {
  private String processCode;
  private String processName;
  private String laborType;
  private BigDecimal workingHours;
  private String timeUnit;
  private BigDecimal wageRate;
  private String rateUnit;
  private BigDecimal personCoefficient;
  private String remark;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getProcessCode() { return processCode; }
  public void setProcessCode(String value) { processCode = value; }
  public String getProcessName() { return processName; }
  public void setProcessName(String value) { processName = value; }
  public String getLaborType() { return laborType; }
  public void setLaborType(String value) { laborType = value; }
  public BigDecimal getWorkingHours() { return workingHours; }
  public void setWorkingHours(BigDecimal value) { workingHours = value; }
  public String getTimeUnit() { return timeUnit; }
  public void setTimeUnit(String value) { timeUnit = value; }
  public BigDecimal getWageRate() { return wageRate; }
  public void setWageRate(BigDecimal value) { wageRate = value; }
  public String getRateUnit() { return rateUnit; }
  public void setRateUnit(String value) { rateUnit = value; }
  public BigDecimal getPersonCoefficient() { return personCoefficient; }
  public void setPersonCoefficient(BigDecimal value) { personCoefficient = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { remark = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
