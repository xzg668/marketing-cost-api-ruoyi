package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlanEligibility {
  private String parentCode;
  private String period;
  private boolean directLaborAllowed;
  private boolean indirectLaborAllowed;
  private boolean auxSubjectAllowed;
  private String reason;
  private Long sourcePlanRawId;
  private LocalDate effectiveDate;
  private String effectivePeriod;

  public static PlanEligibility allowed(String parentCode, String reason) {
    PlanEligibility eligibility = new PlanEligibility();
    eligibility.setParentCode(parentCode);
    eligibility.setDirectLaborAllowed(true);
    eligibility.setIndirectLaborAllowed(true);
    eligibility.setAuxSubjectAllowed(true);
    eligibility.setReason(reason);
    return eligibility;
  }

  public static PlanEligibility allowed(String parentCode, String period, String reason) {
    PlanEligibility eligibility = allowed(parentCode, reason);
    eligibility.setPeriod(period);
    eligibility.setEffectivePeriod(period);
    return eligibility;
  }

  public static PlanEligibility fromPlanRow(
      String parentCode, Long sourcePlanRawId, LocalDate effectiveDate, boolean allowed, String reason) {
    PlanEligibility eligibility = new PlanEligibility();
    eligibility.setParentCode(parentCode);
    eligibility.setSourcePlanRawId(sourcePlanRawId);
    eligibility.setEffectiveDate(effectiveDate);
    eligibility.setEffectivePeriod(effectiveDate == null ? null : effectiveDate.toString().substring(0, 7));
    eligibility.setDirectLaborAllowed(allowed);
    eligibility.setIndirectLaborAllowed(allowed);
    eligibility.setAuxSubjectAllowed(true);
    eligibility.setReason(reason);
    return eligibility;
  }

  public static PlanEligibility fromPlanRow(
      String parentCode,
      String period,
      Long sourcePlanRawId,
      LocalDate effectiveDate,
      String effectivePeriod,
      boolean directLaborAllowed,
      boolean indirectLaborAllowed,
      boolean auxSubjectAllowed,
      String reason) {
    PlanEligibility eligibility = new PlanEligibility();
    eligibility.setParentCode(parentCode);
    eligibility.setPeriod(period);
    eligibility.setSourcePlanRawId(sourcePlanRawId);
    eligibility.setEffectiveDate(effectiveDate);
    eligibility.setEffectivePeriod(effectivePeriod);
    eligibility.setDirectLaborAllowed(directLaborAllowed);
    eligibility.setIndirectLaborAllowed(indirectLaborAllowed);
    eligibility.setAuxSubjectAllowed(auxSubjectAllowed);
    eligibility.setReason(reason);
    return eligibility;
  }
}
