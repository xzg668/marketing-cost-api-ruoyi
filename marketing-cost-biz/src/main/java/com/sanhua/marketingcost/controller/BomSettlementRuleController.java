package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomSettlementRuleUpsertRequest;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BOM 结算规则 CRUD；读写 lp_bom_settlement_rule。 */
@RestController
@RequestMapping("/api/v1/bom/settlement-rules")
public class BomSettlementRuleController {

  private final BomSettlementRuleMapper ruleMapper;

  public BomSettlementRuleController(BomSettlementRuleMapper ruleMapper) {
    this.ruleMapper = ruleMapper;
  }

  @PreAuthorize("@ss.hasPermi('bom-data:settlement-rule:list')")
  @GetMapping
  public CommonResult<List<BomSettlementRule>> list(
      @RequestParam(required = false) Integer enabled,
      @RequestParam(required = false) String ruleCategory,
      @RequestParam(required = false) String settlementAction,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String bomPurpose) {
    return CommonResult.success(
        ruleMapper.selectList(
            Wrappers.<BomSettlementRule>lambdaQuery()
                .eq(enabled != null, BomSettlementRule::getEnabled, enabled)
                .eq(
                    StringUtils.hasText(ruleCategory),
                    BomSettlementRule::getRuleCategory,
                    trimToNull(ruleCategory))
                .eq(
                    StringUtils.hasText(settlementAction),
                    BomSettlementRule::getSettlementAction,
                    trimToNull(settlementAction))
                .eq(
                    StringUtils.hasText(businessUnitType),
                    BomSettlementRule::getBusinessUnitType,
                    trimToNull(businessUnitType))
                .eq(
                    StringUtils.hasText(bomPurpose),
                    BomSettlementRule::getBomPurpose,
                    trimToNull(bomPurpose))
                .orderByAsc(BomSettlementRule::getPriority)
                .orderByAsc(BomSettlementRule::getId)));
  }

  @PreAuthorize("@ss.hasPermi('bom-data:settlement-rule:add')")
  @PostMapping
  public CommonResult<BomSettlementRule> create(@RequestBody BomSettlementRuleUpsertRequest req) {
    BomSettlementRule entity = new BomSettlementRule();
    applyRequest(entity, req);
    ruleMapper.insert(entity);
    return CommonResult.success(entity);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:settlement-rule:edit')")
  @PutMapping("/{id}")
  public CommonResult<BomSettlementRule> update(
      @PathVariable Long id, @RequestBody BomSettlementRuleUpsertRequest req) {
    BomSettlementRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "BOM 结算规则不存在: id=" + id);
    }
    applyRequest(existing, req);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:settlement-rule:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    int affected = ruleMapper.deleteById(id);
    return CommonResult.success(affected > 0);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:settlement-rule:edit')")
  @PostMapping("/{id}/toggle")
  public CommonResult<BomSettlementRule> toggle(@PathVariable Long id) {
    BomSettlementRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "BOM 结算规则不存在: id=" + id);
    }
    existing.setEnabled(Integer.valueOf(1).equals(existing.getEnabled()) ? 0 : 1);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  private static void applyRequest(BomSettlementRule entity, BomSettlementRuleUpsertRequest req) {
    if (req.getRuleCode() != null) entity.setRuleCode(trimToNull(req.getRuleCode()));
    if (req.getRuleName() != null) entity.setRuleName(trimToNull(req.getRuleName()));
    if (req.getRuleCategory() != null) entity.setRuleCategory(trimToNull(req.getRuleCategory()));
    if (req.getSettlementAction() != null) {
      entity.setSettlementAction(trimToNull(req.getSettlementAction()));
    }
    if (req.getSettlementRowType() != null) {
      entity.setSettlementRowType(trimToNull(req.getSettlementRowType()));
    }
    if (req.getSubRefType() != null) entity.setSubRefType(trimToNull(req.getSubRefType()));
    if (req.getMatchConditionJson() != null) {
      entity.setMatchConditionJson(trimToNull(req.getMatchConditionJson()));
    }
    if (req.getMarkSubtreeCostRequired() != null) {
      entity.setMarkSubtreeCostRequired(req.getMarkSubtreeCostRequired());
    }
    if (req.getPriority() != null) entity.setPriority(req.getPriority());
    if (req.getEnabled() != null) entity.setEnabled(req.getEnabled());
    if (req.getBusinessUnitType() != null) {
      entity.setBusinessUnitType(trimToNull(req.getBusinessUnitType()));
    }
    if (req.getBomPurpose() != null) entity.setBomPurpose(trimToNull(req.getBomPurpose()));
    if (req.getEffectiveFrom() != null) entity.setEffectiveFrom(req.getEffectiveFrom());
    if (req.getEffectiveTo() != null) entity.setEffectiveTo(req.getEffectiveTo());
    if (req.getRemark() != null) entity.setRemark(trimToNull(req.getRemark()));
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
