package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingImportResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 行局部变量绑定服务实现。
 *
 * <p>写路径全部在一个事务里完成：版本切换时"旧行 expiry + 新行 insert"必须原子。
 * 缓存失效放在事务提交后 —— 复用 {@link FactorVariableRegistryImpl#invalidateBinding(Long)}
 * 清空对应 linked_item_id 的绑定缓存。
 *
 * <p>CSV 解析：单字节 UTF-8，逗号分隔，不支持含逗号的单元；首行表头。
 * 列顺序固定：
 * <pre>物料编码, 规格型号, token名, factor_code, price_source, 生效日期, 备注</pre>
 * 扩展字段通过 remark 吸收，不动表头。
 */
@Service
public class PriceVariableBindingServiceImpl implements PriceVariableBindingService {

  private static final Logger log =
      LoggerFactory.getLogger(PriceVariableBindingServiceImpl.class);

  /** 合法 token_name —— 与 FormulaNormalizer / Registry 里的 ROW_LOCAL_TOKEN_NAMES 必须对齐 */
  private static final Set<String> VALID_TOKEN_NAMES =
      Set.of("材料含税价格", "材料价格", "废料含税价格", "废料价格");

  /** CSV 日期格式 —— yyyy-MM-dd */
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  private final PriceVariableBindingMapper bindingMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final FactorVariableRegistryImpl factorVariableRegistry;

  public PriceVariableBindingServiceImpl(
      PriceVariableBindingMapper bindingMapper,
      PriceVariableMapper priceVariableMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      FactorVariableRegistryImpl factorVariableRegistry) {
    this.bindingMapper = bindingMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.factorVariableRegistry = factorVariableRegistry;
  }

  // ============================ 查询 ============================

  @Override
  public List<PriceVariableBindingDto> listByLinkedItem(Long linkedItemId) {
    if (linkedItemId == null) {
      return List.of();
    }
    List<PriceVariableBinding> rows =
        bindingMapper.findCurrentByLinkedItemId(linkedItemId);
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
    // factor_code 必须在 lp_price_variable 已登记，避免写入孤儿绑定
    PriceVariable variable = priceVariableMapper.selectOne(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, factorCode));
    if (variable == null) {
      throw new IllegalArgumentException(
          "factor_code 未在 lp_price_variable 登记：" + factorCode);
    }

    LocalDate effective = request.getEffectiveDate() == null
        ? LocalDate.now() : request.getEffectiveDate();

    PriceVariableBinding current =
        bindingMapper.findCurrentByLinkedItemIdAndToken(itemId, tokenName);

    Long resultId;
    if (current == null) {
      resultId = doInsert(request, variable, effective);
    } else if (effective.equals(current.getEffectiveDate())) {
      doUpdateInPlace(current, request, variable);
      resultId = current.getId();
    } else if (effective.isAfter(current.getEffectiveDate())) {
      // 版本切换：旧行失效 + 新行插入
      bindingMapper.expireById(current.getId(), effective.minusDays(1));
      resultId = doInsert(request, variable, effective);
    } else {
      // 新 effective 早于当前生效 —— 业务上不合理（回溯），直接拒
      throw new IllegalArgumentException(
          String.format("新 effective_date=%s 早于当前生效版本 effective_date=%s；"
                  + "如需补录历史，请通过管理端回退后重写。",
              effective, current.getEffectiveDate()));
    }

    factorVariableRegistry.invalidateBinding(itemId);
    return resultId;
  }

  private Long doInsert(
      PriceVariableBindingRequest req, PriceVariable variable, LocalDate effective) {
    PriceVariableBinding entity = new PriceVariableBinding();
    entity.setLinkedItemId(req.getLinkedItemId());
    entity.setTokenName(req.getTokenName().trim());
    entity.setFactorCode(variable.getVariableCode());
    entity.setPriceSource(req.getPriceSource());
    entity.setBuScoped(req.getBuScoped() == null ? 1 : req.getBuScoped());
    entity.setEffectiveDate(effective);
    entity.setExpiryDate(null);
    entity.setSource(StringUtils.hasText(req.getSource()) ? req.getSource() : "MANUAL");
    entity.setConfirmedBy(req.getConfirmedBy());
    entity.setRemark(req.getRemark());
    entity.setDeleted(0);
    bindingMapper.insert(entity);
    log.info("新增行局部绑定：linkedItemId={} token={} factor={} effective={}",
        entity.getLinkedItemId(), entity.getTokenName(),
        entity.getFactorCode(), entity.getEffectiveDate());
    return entity.getId();
  }

  private void doUpdateInPlace(
      PriceVariableBinding current, PriceVariableBindingRequest req, PriceVariable variable) {
    current.setFactorCode(variable.getVariableCode());
    current.setPriceSource(req.getPriceSource());
    current.setBuScoped(req.getBuScoped() == null ? 1 : req.getBuScoped());
    if (StringUtils.hasText(req.getSource())) {
      current.setSource(req.getSource());
    }
    if (StringUtils.hasText(req.getConfirmedBy())) {
      current.setConfirmedBy(req.getConfirmedBy());
    }
    if (req.getRemark() != null) {
      current.setRemark(req.getRemark());
    }
    bindingMapper.updateById(current);
    log.info("原地更新行局部绑定：id={} linkedItemId={} token={}",
        current.getId(), current.getLinkedItemId(), current.getTokenName());
  }

  @Override
  public void softDelete(Long id) {
    PriceVariableBinding existing = bindingMapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("绑定不存在：id=" + id);
    }
    // BaseMapper.deleteById + @TableLogic = UPDATE deleted=1
    bindingMapper.deleteById(id);
    factorVariableRegistry.invalidateBinding(existing.getLinkedItemId());
    log.info("软删行局部绑定：id={} linkedItemId={}", id, existing.getLinkedItemId());
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

  // ============================ CSV 导入 ============================

  @Override
  @Transactional
  public PriceVariableBindingImportResponse importCsv(InputStream csv) {
    PriceVariableBindingImportResponse resp = new PriceVariableBindingImportResponse();
    if (csv == null) {
      return resp;
    }
    Set<Long> touchedItemIds = new HashSet<>();

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(csv, StandardCharsets.UTF_8))) {
      String line;
      int lineNo = 0;
      boolean headerSeen = false;
      while ((line = reader.readLine()) != null) {
        lineNo++;
        if (line.isBlank()) {
          continue;
        }
        // 跳过 BOM
        if (lineNo == 1 && line.startsWith("﻿")) {
          line = line.substring(1);
        }
        if (!headerSeen) {
          headerSeen = true;
          continue;
        }
        resp.setTotal(resp.getTotal() + 1);
        try {
          processCsvRow(line, lineNo, resp, touchedItemIds);
        } catch (IllegalArgumentException e) {
          // 已被 processCsvRow 内部消化 —— 这里保底
          log.debug("CSV 行 {} 处理失败: {}", lineNo, e.getMessage());
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("CSV 读取失败：" + e.getMessage(), e);
    }

    for (Long id : touchedItemIds) {
      factorVariableRegistry.invalidateBinding(id);
    }
    log.info("CSV 导入完成：total={} inserted={} updated={} expired={} errors={}",
        resp.getTotal(), resp.getInserted(), resp.getUpdated(),
        resp.getExpired(), resp.getErrors().size());
    return resp;
  }

  /** 处理 CSV 一行，所有已知异常转为 errors 追加；不抛给事务层（避免整体回滚） */
  private void processCsvRow(
      String raw, int lineNo, PriceVariableBindingImportResponse resp,
      Set<Long> touchedItemIds) {
    String[] cols = raw.split(",", -1);
    String materialCode = col(cols, 0);
    String specModel = col(cols, 1);
    String tokenName = col(cols, 2);
    String factorCode = col(cols, 3);
    String priceSource = col(cols, 4);
    String effectiveStr = col(cols, 5);
    String remark = col(cols, 6);

    if (!StringUtils.hasText(tokenName) || !VALID_TOKEN_NAMES.contains(tokenName)) {
      resp.addError(lineNo, materialCode, specModel,
          "token_name 不识别：" + tokenName + "，合法值：" + VALID_TOKEN_NAMES);
      return;
    }
    if (!StringUtils.hasText(factorCode)) {
      resp.addError(lineNo, materialCode, specModel, "factor_code 不能为空");
      return;
    }
    LocalDate effective;
    try {
      effective = StringUtils.hasText(effectiveStr)
          ? LocalDate.parse(effectiveStr, DATE_FMT) : LocalDate.now();
    } catch (Exception e) {
      resp.addError(lineNo, materialCode, specModel,
          "生效日期格式错误（需 yyyy-MM-dd）：" + effectiveStr);
      return;
    }
    if (!StringUtils.hasText(materialCode) || !StringUtils.hasText(specModel)) {
      resp.addError(lineNo, materialCode, specModel, "物料编码 / 规格型号 不能为空");
      return;
    }

    // 按 (material_code, spec_model) 查联动行 —— 可能一对多
    List<PriceLinkedItem> items = priceLinkedItemMapper.selectList(
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getMaterialCode, materialCode)
            .eq(PriceLinkedItem::getSpecModel, specModel));
    if (items.isEmpty()) {
      resp.addError(lineNo, materialCode, specModel, "联动行未找到（material_code+spec_model）");
      return;
    }

    PriceVariable variable = priceVariableMapper.selectOne(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, factorCode));
    if (variable == null) {
      resp.addError(lineNo, materialCode, specModel,
          "factor_code 未在 lp_price_variable 登记：" + factorCode);
      return;
    }

    // 对每一条联动行单独 UPSERT
    for (PriceLinkedItem item : items) {
      touchedItemIds.add(item.getId());
      PriceVariableBinding current =
          bindingMapper.findCurrentByLinkedItemIdAndToken(item.getId(), tokenName);
      PriceVariableBindingRequest syntheticReq = new PriceVariableBindingRequest();
      syntheticReq.setLinkedItemId(item.getId());
      syntheticReq.setTokenName(tokenName);
      syntheticReq.setFactorCode(factorCode);
      syntheticReq.setPriceSource(priceSource);
      syntheticReq.setEffectiveDate(effective);
      syntheticReq.setSource("SUPPLY_CONFIRMED");
      syntheticReq.setRemark(remark);

      if (current == null) {
        doInsert(syntheticReq, variable, effective);
        resp.setInserted(resp.getInserted() + 1);
      } else if (effective.equals(current.getEffectiveDate())) {
        doUpdateInPlace(current, syntheticReq, variable);
        resp.setUpdated(resp.getUpdated() + 1);
      } else if (effective.isAfter(current.getEffectiveDate())) {
        bindingMapper.expireById(current.getId(), effective.minusDays(1));
        resp.setExpired(resp.getExpired() + 1);
        doInsert(syntheticReq, variable, effective);
        resp.setInserted(resp.getInserted() + 1);
      } else {
        resp.addError(lineNo, materialCode, specModel,
            String.format("新 effective=%s 早于当前生效 %s，忽略",
                effective, current.getEffectiveDate()));
      }
    }
  }

  private static String col(String[] cols, int i) {
    if (i >= cols.length) {
      return "";
    }
    String s = cols[i];
    return s == null ? "" : s.trim();
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
      List<PriceVariable> vars = priceVariableMapper.selectList(
          Wrappers.lambdaQuery(PriceVariable.class)
              .in(PriceVariable::getVariableCode, codes));
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
