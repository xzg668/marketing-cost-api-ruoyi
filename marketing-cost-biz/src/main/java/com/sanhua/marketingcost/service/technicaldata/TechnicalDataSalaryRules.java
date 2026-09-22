package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 工资补录的完整性；本成品 CMS 单项免补由来源检查判断，不套到参考成品上。 */
public final class TechnicalDataSalaryRules {
  private TechnicalDataSalaryRules() {}

  public static List<String> validate(List<QuoteTechSalaryItem> rows, TechnicalDataVersionContentCodec codec) {
    var issues = new ArrayList<String>();
    var types = new HashSet<String>();
    String reference = null;
    for (var row : rows) {
      if (!Set.of("DIRECT", "INDIRECT").contains(Objects.toString(row.getLaborType(), "")) || !types.add(row.getLaborType())) {
        issues.add("工资人工类型无效或重复");
      }
      if (row.getAmount() == null || row.getAmount().signum() < 0) issues.add("工资金额不能为空或小于零");
      var evidence = codec.salaryEvidence(row);
      if (evidence == null || !Objects.equals(evidence.laborType(), row.getLaborType())
          || evidence.sourceAmount() == null || row.getAmount() == null
          || evidence.sourceAmount().compareTo(row.getAmount()) != 0) {
        issues.add("工资原始依据不完整，请重新选择参考成品或上传工时表");
        continue;
      }
      if (evidence.upload() != null) {
        var upload = evidence.upload();
        if (!"DIRECT".equals(row.getLaborType()) || evidence.reference() != null || !upload.issues().isEmpty()
            || upload.items().isEmpty() || !TechnicalDataSalaryUploadParser.CALCULATION_RULE.equals(upload.calculationRule())
            || upload.amountFen() == null || upload.amountFen().movePointLeft(2).compareTo(upload.amountYuan()) != 0
            || !Objects.equals("SALARY_UPLOAD:" + upload.fileSha256(), row.getSourceReferenceId())
            || !Objects.equals(upload.fileSha256(), row.getSourceReferenceVersion())) {
          issues.add("工时表来源或分转元依据不完整，请重新上传");
        }
        continue;
      }
      var source = evidence.cmsItem();
      if (source == null
          || !Objects.equals("CMS_SALARY:" + source.sourceId(), row.getSourceReferenceId())
          || !Objects.equals(source.sourcePeriod(), row.getSourceReferenceVersion())) {
        issues.add("工资原始依据不完整，请重新选择参考成品");
        continue;
      }
      if (!evidence.reference().issues().isEmpty()) issues.add("工资参考来源尚未核实完整");
      if (reference != null && !reference.equals(evidence.reference().fingerprint())) issues.add("两项工资必须来自同一个参考成品");
      reference = evidence.reference().fingerprint();
    }
    if (!types.equals(Set.of("DIRECT", "INDIRECT"))) issues.add("工资必须同时取得直接人工和辅助人员两项");
    return issues.stream().distinct().toList();
  }
}
