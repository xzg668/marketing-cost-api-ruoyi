package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 辅料为元/只的直接金额，上传资料的财务科目不阻挡技术送审。 */
public final class TechnicalDataAuxiliaryRules {
  private TechnicalDataAuxiliaryRules() {}

  public static BigDecimal amount(BigDecimal value) {
    if (value == null) return null;
    if (value.signum() < 0 || value.stripTrailingZeros().scale() > 8 || value.precision() - value.scale() > 12) {
      throw new IllegalArgumentException("本次金额须大于等于 0，最多 12 位整数和 8 位小数");
    }
    return value;
  }

  public static List<String> validate(List<QuoteTechAuxItem> items, TechnicalDataVersionContentCodec codec) {
    if (items == null || items.isEmpty()) return List.of("请选择参考成品或上传完整辅料明细");
    var issues = new ArrayList<String>();
    var keys = new HashSet<String>();
    for (var item : items) {
      String prefix = "第 " + item.getLineNo() + " 行";
      try {
        if (amount(item.getAmount()) == null) issues.add(prefix + "请填写本次金额（元/只）");
        var evidence = codec.auxiliaryEvidence(item);
        if (evidence == null || evidence.itemKey() == null || !keys.add(evidence.itemKey())
            || !Objects.equals(evidence.itemKey(), item.getSourceReferenceId())) {
          issues.add(prefix + "辅料来源不完整或重复，请重新选取"); continue;
        }
        if (evidence.cms() != null && evidence.upload() == null && "CMS_AMOUNT".equals(item.getPricingMethod())) {
          var source = evidence.cms().item();
          if (source == null || source.subjectCode() == null || source.subjectCode().isBlank()
              || !Objects.equals(source.subjectCode(), item.getSubjectCode())
              || !Objects.equals(source.subjectName(), item.getAuxiliaryName())) issues.add(prefix + "CMS 科目来源不完整");
        } else if (evidence.upload() != null && evidence.cms() == null && "UPLOAD_AMOUNT".equals(item.getPricingMethod())) {
          var source = evidence.upload().item();
          if (source == null || !Objects.equals(source.itemKey(), evidence.itemKey())
              || !Objects.equals(source.name(), item.getAuxiliaryName()) || evidence.upload().fileSha256() == null) issues.add(prefix + "上传原表来源不完整");
        } else issues.add(prefix + "请重新确认辅料来源，旧资料保留供对照");
      } catch (IllegalArgumentException exception) { issues.add(prefix + exception.getMessage()); }
    }
    return List.copyOf(issues);
  }
}
