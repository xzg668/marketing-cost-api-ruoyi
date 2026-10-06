package com.sanhua.marketingcost.service.electronicdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomPolicyActionResolver;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolverImpl;
import com.sanhua.marketingcost.service.materialshape.ShapePolicyFingerprint;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeResolver;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import java.util.Map;
import static org.mockito.Mockito.mock;

/** 各层单元测试共用真实形态回退和规则判断，避免用伪造 Plan 掩盖范围错误。 */
public final class ElectronicDrawingTestScope {
  private ElectronicDrawingTestScope() {}
  public static ElectronicDrawingBomScope create(MaterialMasterRawMapper materials, ElectronicDrawingU9SubBomPort u9) {
    var json = new ObjectMapper();
    var shapes = new MaterialQuoteShapeResolverImpl(mock(MaterialQuoteShapePolicyMapper.class), new ShapePolicyFingerprint(json));
    return new ElectronicDrawingBomScope(materials, u9, shapes, mock(SupplierRatioShapeResolver.class),
        new EffectiveBomPolicyActionResolver(json), mock(BomSettlementRuleQueryService.class),
        new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(json)), (codes, org) -> Map.of());
  }
}
