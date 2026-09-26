package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionRemark;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import java.math.BigDecimal;
import java.util.List;

/** 本地和真实拒绝回执测试共用的人工样例；通过生产快照编码及 remark 生成器生成说明。 */
final class OaTechnicalSubmissionFixtures {
  private OaTechnicalSubmissionFixtures() {}

  static String remark(ObjectMapper json) {
    var codec = new TechnicalDataVersionContentCodec(json);
    var generator = new TechnicalDataSubmissionRemark(json);
    var salaryVersion = new QuoteTechDataVersion();
    salaryVersion.setContentSchemaVersion(2);
    var direct = salary("DIRECT", "1.25", 1);
    var indirect = salary("INDIRECT", "0.30", 2);
    var salary = submission(codec, salaryVersion, "SALARY", List.of(direct, indirect));
    var lossVersion = new QuoteTechDataVersion();
    lossVersion.setContentSchemaVersion(2);
    lossVersion.setNetLossJson("{\"entryMode\":\"MANUAL\",\"rate\":0.02}");
    var loss = submission(codec, lossVersion, "NET_LOSS", List.of());
    return generator.generate("I03测试产品A", salary) + generator.generate("I03测试产品B", loss);
  }

  private static QuoteTechSubmission submission(TechnicalDataVersionContentCodec codec,
      QuoteTechDataVersion version, String module, List<QuoteTechSalaryItem> salaries) {
    var scope = List.of(new TechnicalDataVersionContentCodec.ModuleSnapshot(
        module, true, "SUPPLEMENTAL", "READY", null, null, null, null, null, "VALID", "已校验"));
    var submission = new QuoteTechSubmission();
    submission.setContentSchemaVersion(2);
    submission.setModuleTypesJson("[\"" + module + "\"]");
    submission.setContentSnapshotJson(codec.versionContentJson(version, scope, List.of(), List.of(), salaries));
    return submission;
  }

  private static QuoteTechSalaryItem salary(String type, String amount, int line) {
    var item = new QuoteTechSalaryItem();
    item.setLineNo(line);
    item.setLaborType(type);
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
