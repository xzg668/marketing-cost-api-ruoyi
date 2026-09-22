package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.NetLoss;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TechnicalDataNetLossRules {
  private TechnicalDataNetLossRules() {}

  public static BigDecimal costingInput(NetLoss content) {
    var issues = validate(content);
    if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
    return content.rate();
  }

  public static BigDecimal ratio(String percent) {
    if (percent == null || percent.isBlank()) return null;
    if (!percent.trim().matches("[0-9]{1,2}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException("净损失率请输入 0 至 100（不含）的百分数，最多三位小数");
    return new BigDecimal(percent.trim()).movePointLeft(2);
  }

  public static List<String> validate(NetLoss content) {
    if (content == null || content.rate() == null) return List.of("请填写或参考净损失率");
    if (content.rate().signum() < 0 || content.rate().compareTo(BigDecimal.ONE) >= 0) return List.of("净损失率必须在 0% 至 100%（不含）之间");
    if (!Set.of("REFERENCE", "MANUAL").contains(Objects.toString(content.entryMode(), ""))) return List.of("请选择参考或直接填写");
    if ("MANUAL".equals(content.entryMode())) {
      if (content.reference() != null || content.bareMaterialNo() != null || content.sourceReference() != null) return List.of("直接填写不能混入参考来源");
      if (content.rate().stripTrailingZeros().scale() > 5) return List.of("直接填写的百分数最多三位小数");
    } else {
      var reference = content.reference();
      if (reference == null || reference.source() == null || reference.fingerprint() == null) return List.of("参考费率缺少来源，请重新选择成品");
      var source = reference.source();
      if (!"AVAILABLE".equals(source.status()) || source.configurationId() == null
          || !com.sanhua.marketingcost.service.NetLossRateQuery.validPublicRate(source.rate())
          || !Objects.equals(content.bareMaterialNo(), source.bareMaterialNo())
          || !Objects.equals(content.sourceReference(), "QUALITY_LOSS_RATE:" + source.configurationId())
          || content.rate().compareTo(source.rate()) != 0) return List.of("参考费率与来源不一致，请重新选择成品");
    }
    return List.of();
  }
}
