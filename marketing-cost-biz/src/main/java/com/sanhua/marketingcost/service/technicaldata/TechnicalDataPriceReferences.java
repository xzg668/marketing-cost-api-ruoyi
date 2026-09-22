package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceReference;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceParameters;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class TechnicalDataPriceReferences {
  private final PriceLinkedItemMapper rows;
  private final OaMessageCodec json;
  private final com.sanhua.marketingcost.mapper.PriceVariableBindingMapper bindings;
  public TechnicalDataPriceReferences(PriceLinkedItemMapper rows, OaMessageCodec json,
      com.sanhua.marketingcost.mapper.PriceVariableBindingMapper bindings) {
    this.rows = rows; this.json = json; this.bindings = bindings;
  }

  public List<TechnicalDataPriceReference> search(QuoteTechTask task, String searchBy, String keyword) {
    if (keyword == null || keyword.isBlank() || keyword.length() > 100) throw new IllegalArgumentException("请输入料号或型号");
    if (!List.of("CODE", "MODEL").contains(searchBy)) throw new IllegalArgumentException("不支持的查询方式");
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getBusinessUnitType, task.getBusinessUnitType());
    if ("CODE".equals(searchBy)) query.eq(PriceLinkedItem::getMaterialCode, keyword.trim());
    else query.like(PriceLinkedItem::getSpecModel, keyword.trim());
    return rows.selectList(query.orderByDesc(PriceLinkedItem::getPricingMonth).orderByDesc(PriceLinkedItem::getId).last("LIMIT 50"))
        .stream().map(this::snapshot).toList();
  }

  public TechnicalDataPriceReference require(QuoteTechTask task, Long id, String fingerprint) {
    var row = id == null ? null : rows.selectById(id);
    if (row == null || !Objects.equals(row.getBusinessUnitType(), task.getBusinessUnitType())) throw new IllegalArgumentException("参考公式不存在或不属于当前业务单元");
    var value = snapshot(row);
    if (!Objects.equals(value.fingerprint(), fingerprint)) throw new TechnicalDataTaskException(
        TechnicalDataTaskErrorCode.VERSION_CONFLICT, "参考公式已变化，请重新查询；本次输入仍保留");
    return value;
  }

  private TechnicalDataPriceReference snapshot(PriceLinkedItem row) {
    // 旧价格行没有重量、费用单位列，不能默认为克；使用者须明确本次参数单位。
    var params = new PriceParameters(row.getBlankWeight(), row.getNetWeight(), null, row.getProcessFee(), row.getAgentFee(), null);
    var copiedBindings = bindings.findCurrentByLinkedItemId(row.getId()).stream()
        .sorted(java.util.Comparator.comparing(com.sanhua.marketingcost.entity.PriceVariableBinding::getTokenName))
        .map(binding -> new TechnicalDataPriceReference.Binding(binding.getTokenName(), binding.getFactorCode(),
            binding.getPriceSource(), binding.getFactorIdentityId(), binding.getBuScoped())).toList();
    if (copiedBindings.stream().map(TechnicalDataPriceReference.Binding::tokenName).distinct().count() != copiedBindings.size()) {
      throw new IllegalArgumentException("参考公式存在重复影响因素绑定，请先核实原公式");
    }
    var value = new TechnicalDataPriceReference(row.getId(), row.getMaterialCode(), row.getMaterialName(), row.getSpecModel(),
        row.getUnit(), row.getOrgCode(), row.getBusinessUnitType(), row.getPricingMonth(), row.getFormulaExpr(), row.getFormulaExprCn(),
        params, row.getTaxIncluded(), row.getSupplierName(), row.getSourceInputSnapshotJson(), null, copiedBindings);
    return new TechnicalDataPriceReference(value.id(), value.materialNo(), value.name(), value.model(), value.unit(), value.organizationCode(),
        value.businessUnit(), value.month(), value.formula(), value.formulaText(), value.parameters(), value.taxIncluded(),
        value.supplierName(), value.inputSnapshotJson(), json.canonicalHash(value), copiedBindings);
  }
}
