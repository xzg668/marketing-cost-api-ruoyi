package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.entity.PriceVariableBindingChangeLog;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingChangeLogMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 行局部变量绑定服务实现。
 *
 * <p>写路径全部在一个事务里完成：版本切换时"旧行 expiry + 新行 insert"必须原子。 新计算从已提交版本读取绑定。
 */
@Service
public class PriceVariableBindingServiceImpl implements PriceVariableBindingService {

  private static final Logger log = LoggerFactory.getLogger(PriceVariableBindingServiceImpl.class);

  /** 合法 token_name —— 与 FormulaNormalizer / Registry 里的 ROW_LOCAL_TOKEN_NAMES 必须对齐 */
  private static final Set<String> VALID_TOKEN_NAMES = Set.of("材料含税价格", "材料价格", "废料含税价格", "废料价格");

  private final PriceVariableBindingMapper bindingMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;

  @Autowired(required = false)
  private PriceVariableBindingChangeLogMapper bindingChangeLogMapper;

  public PriceVariableBindingServiceImpl(
      PriceVariableBindingMapper bindingMapper,
      PriceVariableMapper priceVariableMapper,
      PriceLinkedItemMapper priceLinkedItemMapper) {
    this.bindingMapper = bindingMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
  }

  void setBindingChangeLogMapper(PriceVariableBindingChangeLogMapper bindingChangeLogMapper) {
    this.bindingChangeLogMapper = bindingChangeLogMapper;
  }

  // ============================ 查询 ============================

  @Override
  public List<PriceVariableBindingDto> listByLinkedItem(Long linkedItemId) {
    if (linkedItemId == null) {
      return List.of();
    }
    List<PriceVariableBinding> rows = bindingMapper.findCurrentByLinkedItemId(linkedItemId);
    return attachFactorNames(rows);
  }

  @Override
  public List<PriceVariableBindingDto> getHistory(Long linkedItemId, String tokenName) {
    if (linkedItemId == null || !StringUtils.hasText(tokenName)) {
      return List.of();
    }
    List<PriceVariableBinding> rows = bindingMapper.findHistory(linkedItemId, tokenName);
    return attachFactorNames(rows);
  }

  // ============================ 写入 ============================

  @Override
  @Transactional
  public Long save(PriceVariableBindingRequest request) {
    validate(request);

    Long itemId = request.getLinkedItemId();
    String tokenName = request.getTokenName().trim();
    String factorCode = request.getFactorCode().trim();

    PriceLinkedItem item = priceLinkedItemMapper.selectById(itemId);
    if (item == null) {
      throw new IllegalArgumentException("联动行不存在：linkedItemId=" + itemId);
    }
    requirePublicItem(item);
    // factor_code 必须在 lp_price_variable 已登记，避免写入孤儿绑定
    PriceVariable variable =
        priceVariableMapper.selectOne(
            Wrappers.lambdaQuery(PriceVariable.class)
                .eq(PriceVariable::getVariableCode, factorCode));
    if (variable == null) {
      throw new IllegalArgumentException("factor_code 未在 lp_price_variable 登记：" + factorCode);
    }

    LocalDate effective =
        request.getEffectiveDate() == null ? LocalDate.now() : request.getEffectiveDate();

    PriceVariableBinding current =
        bindingMapper.findCurrentByLinkedItemIdAndToken(itemId, tokenName);

    Long resultId;
    PriceVariableBinding oldBinding = snapshot(current);
    PriceVariableBinding newBinding;
    String action;
    if (current == null) {
      newBinding = doInsert(request, variable, effective);
      resultId = newBinding.getId();
      action = "INSERT";
    } else if (effective.equals(current.getEffectiveDate())) {
      doUpdateInPlace(current, request, variable);
      resultId = current.getId();
      newBinding = current;
      action = "UPDATE";
    } else if (effective.isAfter(current.getEffectiveDate())) {
      // 版本切换：旧行失效 + 新行插入
      bindingMapper.expireById(current.getId(), effective.minusDays(1));
      newBinding = doInsert(request, variable, effective);
      resultId = newBinding.getId();
      action = "VERSION_SWITCH";
    } else {
      // 新 effective 早于当前生效 —— 业务上不合理（回溯），直接拒
      throw new IllegalArgumentException(
          String.format(
              "新 effective_date=%s 早于当前生效版本 effective_date=%s；" + "如需补录历史，请通过管理端回退后重写。",
              effective, current.getEffectiveDate()));
    }

    logBindingChange(action, oldBinding, newBinding, request, "保存行级变量绑定");

    return resultId;
  }

  private PriceVariableBinding doInsert(
      PriceVariableBindingRequest req, PriceVariable variable, LocalDate effective) {
    PriceVariableBinding entity = new PriceVariableBinding();
    entity.setLinkedItemId(req.getLinkedItemId());
    entity.setTokenName(req.getTokenName().trim());
    entity.setFactorCode(variable.getVariableCode());
    entity.setPriceSource(req.getPriceSource());
    entity.setFactorIdentityId(req.getFactorIdentityId());
    entity.setFactorMonthlyPriceId(req.getFactorMonthlyPriceId());
    entity.setFactorUploadBatchId(req.getFactorUploadBatchId());
    entity.setStandardBindingId(req.getStandardBindingId());
    entity.setExcelSourceSheetName(req.getExcelSourceSheetName());
    entity.setExcelSourceCellRef(req.getExcelSourceCellRef());
    entity.setExcelFormula(req.getExcelFormula());
    entity.setBuScoped(req.getBuScoped() == null ? 1 : req.getBuScoped());
    entity.setEffectiveDate(effective);
    entity.setExpiryDate(null);
    entity.setSource(resolvedSource(req));
    entity.setConfirmedBy(req.getConfirmedBy());
    entity.setRemark(req.getRemark());
    entity.setDeleted(0);
    bindingMapper.insert(entity);
    log.info(
        "新增行局部绑定：linkedItemId={} token={} factor={} effective={}",
        entity.getLinkedItemId(),
        entity.getTokenName(),
        entity.getFactorCode(),
        entity.getEffectiveDate());
    return entity;
  }

  private void doUpdateInPlace(
      PriceVariableBinding current, PriceVariableBindingRequest req, PriceVariable variable) {
    current.setFactorCode(variable.getVariableCode());
    current.setPriceSource(req.getPriceSource());
    current.setFactorIdentityId(req.getFactorIdentityId());
    current.setFactorMonthlyPriceId(req.getFactorMonthlyPriceId());
    current.setFactorUploadBatchId(req.getFactorUploadBatchId());
    current.setStandardBindingId(req.getStandardBindingId());
    current.setExcelSourceSheetName(req.getExcelSourceSheetName());
    current.setExcelSourceCellRef(req.getExcelSourceCellRef());
    current.setExcelFormula(req.getExcelFormula());
    current.setBuScoped(req.getBuScoped() == null ? 1 : req.getBuScoped());
    current.setSource(resolvedSource(req));
    if (StringUtils.hasText(req.getConfirmedBy())) {
      current.setConfirmedBy(req.getConfirmedBy());
    }
    if (req.getRemark() != null) {
      current.setRemark(req.getRemark());
    }
    bindingMapper.updateById(current);
    log.info(
        "原地更新行局部绑定：id={} linkedItemId={} token={}",
        current.getId(),
        current.getLinkedItemId(),
        current.getTokenName());
  }

  @Override
  public void softDelete(Long id) {
    PriceVariableBinding existing = bindingMapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("绑定不存在：id=" + id);
    }
    requirePublicItem(priceLinkedItemMapper.selectById(existing.getLinkedItemId()));
    // BaseMapper.deleteById + @TableLogic = UPDATE deleted=1
    bindingMapper.deleteById(id);
    logBindingChange("DELETE", existing, null, null, "软删行级变量绑定");

    log.info("软删行局部绑定：id={} linkedItemId={}", id, existing.getLinkedItemId());
  }

  private void requirePublicItem(PriceLinkedItem item) {
    if (item != null && OriginalLinkedRuleSnapshot.VERSION.equals(item.getExpressionVersion()))
      throw new IllegalArgumentException("已发布原稿因素来自完整规则快照，请发布新原稿修订，不可修改历史绑定");
    if (item != null && "TECH_SUPPLEMENTAL".equals(item.getSourceKind())) {
      throw new IllegalArgumentException("技术审批价格的因素绑定不可在公共价格页修改");
    }
  }

  // ============================ 待绑定 ============================

  @Override
  public PriceVariableBindingPendingResponse getPending() {
    List<PriceVariableBindingMapper.PendingItemRow> rows = bindingMapper.findPendingItems();
    PriceVariableBindingPendingResponse resp = new PriceVariableBindingPendingResponse();
    for (var r : rows) {
      PriceVariableBindingPendingResponse.PendingItem item =
          new PriceVariableBindingPendingResponse.PendingItem();
      item.setLinkedItemId(r.getId());
      item.setMaterialCode(r.getMaterialCode());
      item.setSpecModel(r.getSpecModel());
      item.setFormulaExpr(r.getFormulaExpr());
      item.setFormulaExprCn(r.getFormulaExprCn());
      resp.addItem(item);
    }
    return resp;
  }

  private String resolvedSource(PriceVariableBindingRequest req) {
    return req != null && StringUtils.hasText(req.getSource()) ? req.getSource().trim() : "MANUAL";
  }

  private PriceVariableBinding snapshot(PriceVariableBinding source) {
    if (source == null) {
      return null;
    }
    PriceVariableBinding copy = new PriceVariableBinding();
    copy.setId(source.getId());
    copy.setLinkedItemId(source.getLinkedItemId());
    copy.setTokenName(source.getTokenName());
    copy.setFactorCode(source.getFactorCode());
    copy.setPriceSource(source.getPriceSource());
    copy.setFactorIdentityId(source.getFactorIdentityId());
    copy.setFactorMonthlyPriceId(source.getFactorMonthlyPriceId());
    copy.setExcelFormula(source.getExcelFormula());
    copy.setSource(source.getSource());
    return copy;
  }

  private void logBindingChange(
      String action,
      PriceVariableBinding oldBinding,
      PriceVariableBinding newBinding,
      PriceVariableBindingRequest request,
      String message) {
    if (bindingChangeLogMapper == null) {
      return;
    }
    PriceVariableBindingChangeLog changeLog = new PriceVariableBindingChangeLog();
    changeLog.setBindingId(
        newBinding != null ? newBinding.getId() : oldBinding == null ? null : oldBinding.getId());
    changeLog.setLinkedItemId(
        newBinding != null
            ? newBinding.getLinkedItemId()
            : oldBinding == null ? null : oldBinding.getLinkedItemId());
    changeLog.setTokenName(
        newBinding != null
            ? newBinding.getTokenName()
            : oldBinding == null ? null : oldBinding.getTokenName());
    changeLog.setAction(action);
    changeLog.setOldSource(oldBinding == null ? null : oldBinding.getSource());
    changeLog.setNewSource(newBinding == null ? null : newBinding.getSource());
    changeLog.setOldFactorCode(oldBinding == null ? null : oldBinding.getFactorCode());
    changeLog.setNewFactorCode(newBinding == null ? null : newBinding.getFactorCode());
    changeLog.setOldFactorIdentityId(oldBinding == null ? null : oldBinding.getFactorIdentityId());
    changeLog.setNewFactorIdentityId(newBinding == null ? null : newBinding.getFactorIdentityId());
    changeLog.setOldFactorMonthlyPriceId(
        oldBinding == null ? null : oldBinding.getFactorMonthlyPriceId());
    changeLog.setNewFactorMonthlyPriceId(
        newBinding == null ? null : newBinding.getFactorMonthlyPriceId());
    changeLog.setOldPriceSource(oldBinding == null ? null : oldBinding.getPriceSource());
    changeLog.setNewPriceSource(newBinding == null ? null : newBinding.getPriceSource());
    changeLog.setOldExcelFormula(oldBinding == null ? null : oldBinding.getExcelFormula());
    changeLog.setNewExcelFormula(newBinding == null ? null : newBinding.getExcelFormula());
    changeLog.setChangeSource(
        newBinding != null && StringUtils.hasText(newBinding.getSource())
            ? newBinding.getSource()
            : "SYSTEM");
    changeLog.setChangedBy(request == null ? null : request.getConfirmedBy());
    changeLog.setMessage(message);
    changeLog.setCreatedAt(LocalDateTime.now());
    bindingChangeLogMapper.insert(changeLog);
  }

  // ============================ 私有工具 ============================

  private void validate(PriceVariableBindingRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (req.getLinkedItemId() == null) {
      throw new IllegalArgumentException("linkedItemId 必填");
    }
    if (!StringUtils.hasText(req.getTokenName())
        || !VALID_TOKEN_NAMES.contains(req.getTokenName().trim())) {
      throw new IllegalArgumentException(
          "tokenName 非法：" + req.getTokenName() + "，合法值：" + VALID_TOKEN_NAMES);
    }
    if (!StringUtils.hasText(req.getFactorCode())) {
      throw new IllegalArgumentException("factorCode 必填");
    }
  }

  /** 批量查变量名回填 factorName —— 一次 IN 查询避免 N+1 */
  private List<PriceVariableBindingDto> attachFactorNames(List<PriceVariableBinding> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    Set<String> codes = new HashSet<>();
    for (PriceVariableBinding b : rows) {
      if (StringUtils.hasText(b.getFactorCode())) {
        codes.add(b.getFactorCode());
      }
    }
    Map<String, String> codeToName = new HashMap<>();
    if (!codes.isEmpty()) {
      List<PriceVariable> vars =
          priceVariableMapper.selectList(
              Wrappers.lambdaQuery(PriceVariable.class).in(PriceVariable::getVariableCode, codes));
      for (PriceVariable v : vars) {
        codeToName.put(v.getVariableCode(), v.getVariableName());
      }
    }
    List<PriceVariableBindingDto> out = new ArrayList<>(rows.size());
    for (PriceVariableBinding b : rows) {
      PriceVariableBindingDto d = PriceVariableBindingDto.fromEntity(b);
      d.setFactorName(codeToName.get(b.getFactorCode()));
      out.add(d);
    }
    return out;
  }
}
