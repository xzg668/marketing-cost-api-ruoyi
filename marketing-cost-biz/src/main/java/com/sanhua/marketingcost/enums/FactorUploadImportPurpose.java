package com.sanhua.marketingcost.enums;

/** 影响因素/联动价上传批次用途，对齐 lp_factor_upload_batch.import_purpose。 */
public enum FactorUploadImportPurpose {
  MONTHLY_LINKED_FACTOR("MONTHLY_LINKED_FACTOR", "月度联动价与影响因素导入"),
  LINKED_APPEND_ONLY("LINKED_APPEND_ONLY", "联动价与影响因素仅新增导入"),
  LINKED_OVERRIDE_EFFECTIVE("LINKED_OVERRIDE_EFFECTIVE", "联动价与影响因素覆盖生效导入"),
  MONTHLY_ADJUST("MONTHLY_ADJUST", "月度调价导入");

  private final String code;
  private final String label;

  FactorUploadImportPurpose(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }
}
