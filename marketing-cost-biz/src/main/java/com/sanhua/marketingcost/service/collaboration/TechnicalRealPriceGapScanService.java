package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * QCBP-13：对技术刚补齐的完整BOM或裸品组合BOM做只读真实取价。
 * 不创建价格批次，不写 EasyData，不把价格服务异常伪装成技术缺价。
 */
@Service
public class TechnicalRealPriceGapScanService {

  private static final String FULL_BOM = "FULL_BOM";
  private static final String BARE_PACKAGE = "BARE_PACKAGE";
  private static final String STATUS_READY = "READY";
  private static final String STATUS_FAILED = "FAILED";

  private final FormalBomReadService formalBomReadService;
  private final QuoteBomSupplementDetailMapper supplementDetailMapper;
  private final QuoteBomPackageReferenceDetailMapper packageDetailMapper;
  private final NormalMaterialPricePrepareStrategy priceStrategy;

  public TechnicalRealPriceGapScanService(
      FormalBomReadService formalBomReadService,
      QuoteBomSupplementDetailMapper supplementDetailMapper,
      QuoteBomPackageReferenceDetailMapper packageDetailMapper,
      NormalMaterialPricePrepareStrategy priceStrategy) {
    this.formalBomReadService = formalBomReadService;
    this.supplementDetailMapper = supplementDetailMapper;
    this.packageDetailMapper = packageDetailMapper;
    this.priceStrategy = priceStrategy;
  }

  public CollaborationPriceScanResult scan(
      QuoteCollaborationProductTask task, QuoteCollaborationQuoteLink owner) {
    return scan(task, owner, task == null ? null : task.getAccountingMonth());
  }

  /** 关联报价按自己的月份复验，避免半年复用时沿用原任务月份。 */
  public CollaborationPriceScanResult scan(
      QuoteCollaborationProductTask task, QuoteCollaborationQuoteLink owner,
      String accountingMonth) {
    try {
      requireContext(task, owner, accountingMonth);
      List<Candidate> rawCandidates = switch (task.getPrimaryScope()) {
        case FULL_BOM -> fullBomCandidates(task);
        case BARE_PACKAGE -> barePackageCandidates(task, accountingMonth);
        default -> throw new IllegalArgumentException(
            "当前任务不是补BOM或补包装任务，不能使用技术结构缺价扫描");
      };
      List<Candidate> candidates = aggregate(rawCandidates);
      if (candidates.isEmpty()) {
        return CollaborationPriceScanResult.error("完整候选BOM没有可检查价格的底层物料");
      }
      List<CollaborationPriceScanResult.PriceGap> gaps = new ArrayList<>();
      for (Candidate candidate : candidates) {
        NormalMaterialPricePrepareResult result = priceStrategy.calculate(
            owner.getOaNo(), task.getBusinessUnitType(), accountingMonth,
            LocalDateTime.now(), null, planItem(task, owner, candidate, accountingMonth));
        if (result == null || STATUS_FAILED.equals(result.getStatus())) {
          return CollaborationPriceScanResult.error(
              "底层物料价格检查异常：" + candidate.materialCode() + " / "
                  + firstText(result == null ? null : result.getMessage(), "价格服务未返回结果"));
        }
        if (!STATUS_READY.equals(result.getStatus())) {
          String type = firstText(result.getGapType(), "MISSING_PRICE");
          String reason = firstText(result.getMessage(), "当前报价条件下无法取得有效价格");
          if (StringUtils.hasText(result.getSourceTable())) {
            reason += "（取价来源：" + result.getSourceTable().trim() + "）";
          }
          gaps.add(new CollaborationPriceScanResult.PriceGap(
              candidate.materialCode(), type, "MAINTAIN_PRICE", reason,
              result.getSourceTable(), null, candidate.sourceType(), candidate.sourceId(),
              candidate.nodeKey(), candidate.path(), candidate.materialName(),
              candidate.materialSpec(), candidate.materialModel(), candidate.materialRole(),
              candidate.quantity(), candidate.unit(), accountingMonth,
              task.getApplicableOrgCode()));
        }
      }
      return gaps.isEmpty()
          ? CollaborationPriceScanResult.ready(candidates.size())
          : CollaborationPriceScanResult.gaps(candidates.size(), gaps);
    } catch (RuntimeException exception) {
      return CollaborationPriceScanResult.error(
          "完整候选BOM缺价检查失败：" + exceptionMessage(exception));
    }
  }

  private List<Candidate> fullBomCandidates(QuoteCollaborationProductTask task) {
    if (task.getSupplementVersionId() == null
        || !StringUtils.hasText(task.getElectronicBomFingerprint())) {
      throw new IllegalArgumentException("请先完成电子图库BOM回取校验");
    }
    List<QuoteBomSupplementDetail> rows = supplementDetailMapper.selectList(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, task.getSupplementVersionId())
            .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("电子图库BOM明细为空");
    }
    List<Candidate> result = new ArrayList<>();
    for (QuoteBomSupplementDetail row : rows) {
      if (row == null || isRoot(row.getLevel(), row.getParentCode())) continue;
      if (hasChild(row.getMaterialCode(), row.getPath(), rows.stream()
          .map(this::supplementEdge).toList())) continue;
      rejectInvalidLeaf(row.getMaterialCode(), row.getShapeAttr(), row.getSourceCategory());
      result.add(new Candidate("ELECTRONIC_DRAWING_BOM", row.getId(),
          nodeKey("ELECTRONIC", row.getId(), row.getPath()), row.getPath(),
          required(row.getMaterialCode(), "电子图库底层物料料号"), row.getMaterialName(),
          row.getMaterialSpec(), row.getMaterialModel(), role(row.getMaterialName(),
              row.getShapeAttr(), row.getSourceCategory(), row.getCostElementCode(), false),
          positive(row.getQtyPerTop(), "电子图库底层物料累计用量"), row.getUnit()));
    }
    return result;
  }

  private List<Candidate> barePackageCandidates(
      QuoteCollaborationProductTask task, String accountingMonth) {
    if (task.getPackageReferenceId() == null) {
      throw new IllegalArgumentException("请先保存裸品包装草稿");
    }
    FormalBomReadResult formal = formalBomReadService.read(
        task.getProductCode(), accountingMonth, null, LocalDate.now(),
        new QuoteDataOrganization(task.getPriceOrgCode(), task.getMaterialOrgCode()));
    if (formal == null || !formal.found() || formal.lines() == null || formal.lines().isEmpty()) {
      throw new IllegalArgumentException(firstText(
          formal == null ? null : formal.gapMessage(), "U9裸品本体BOM当前不可用"));
    }
    List<Candidate> result = u9LeafCandidates(formal.lines());
    List<QuoteBomPackageReferenceDetail> packageRows = packageDetailMapper.selectList(
        Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
            .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId,
                task.getPackageReferenceId())
            .eq(QuoteBomPackageReferenceDetail::getSelectedFlag, 1)
            .orderByAsc(QuoteBomPackageReferenceDetail::getLineNo));
    if (packageRows == null || packageRows.isEmpty()) {
      throw new IllegalArgumentException("裸品包装草稿没有有效明细");
    }
    List<Edge> edges = packageRows.stream().map(this::packageEdge).toList();
    for (QuoteBomPackageReferenceDetail row : packageRows) {
      if (row == null || hasChild(row.getPackageMaterialCode(), row.getSourcePath(), edges)) {
        continue;
      }
      String path = packagePath(task.getProductCode(), row);
      result.add(new Candidate("PACKAGE_REFERENCE", row.getId(),
          nodeKey("PACKAGE", row.getId(), path), path,
          required(row.getPackageMaterialCode(), "包装底层物料料号"),
          row.getPackageMaterialName(), row.getPackageMaterialSpec(),
          row.getPackageMaterialModel(), "PACKAGE_MATERIAL",
          positive(first(row.getAdjustedChildQtyPerTop(), row.getQtyPerTop(),
              row.getChildQtyPerTop()), "包装底层物料累计用量"),
          firstText(row.getUnit(), row.getPackageMaterialUnit())));
    }
    return result;
  }

  private List<Candidate> u9LeafCandidates(List<QuoteBomSourceLineDto> rows) {
    List<Edge> edges = rows.stream().map(this::u9Edge).toList();
    List<Candidate> result = new ArrayList<>();
    for (QuoteBomSourceLineDto row : rows) {
      if (row == null || isRoot(row.level(), row.parentCode())) continue;
      if (hasChild(row.materialCode(), row.path(), edges)) continue;
      rejectInvalidLeaf(row.materialCode(), row.shapeAttr(), row.sourceCategory());
      Long sourceId = row.sourceRawHierarchyId() == null ? row.sourceId()
          : row.sourceRawHierarchyId();
      result.add(new Candidate("U9_BOM", sourceId,
          nodeKey("U9", sourceId, row.path()), row.path(),
          required(row.materialCode(), "U9底层物料料号"), row.materialName(),
          row.materialSpec(), row.materialModel(), role(row.materialName(), row.shapeAttr(),
              row.sourceCategory(), row.costElementCode(), false),
          positive(row.qtyPerTop(), "U9底层物料累计用量"), row.unit()));
    }
    return result;
  }

  private List<Candidate> aggregate(List<Candidate> candidates) {
    Map<String, Candidate> result = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      String key = String.join("|", candidate.sourceType(), candidate.materialCode(),
          firstText(candidate.path(), "NO_PATH"), candidate.materialRole());
      result.merge(key, candidate, (left, right) -> left.withQuantity(
          left.quantity().add(right.quantity())));
    }
    return result.values().stream()
        .sorted(Comparator.comparing(Candidate::path,
            Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  private PricePreparePlanItem planItem(
      QuoteCollaborationProductTask task,
      QuoteCollaborationQuoteLink owner,
      Candidate candidate,
      String accountingMonth) {
    BomCostingRow row = new BomCostingRow();
    row.setId(candidate.sourceId());
    row.setOaNo(owner.getOaNo());
    row.setOaFormItemId(owner.getOaFormItemId());
    row.setTopProductCode(task.getProductCode());
    row.setMaterialCode(candidate.materialCode());
    row.setMaterialName(candidate.materialName());
    row.setMaterialSpec(candidate.materialSpec());
    row.setPath(candidate.path());
    row.setQtyPerTop(candidate.quantity());
    row.setUnit(candidate.unit());
    row.setPriceOrgCode(task.getPriceOrgCode());
    row.setMaterialOrganizationCode(task.getMaterialOrgCode());
    row.setPeriodMonth(accountingMonth);
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setBomRowId(candidate.sourceId());
    item.setTopProductCode(task.getProductCode());
    item.setMaterialCode(candidate.materialCode());
    item.setMaterialName(candidate.materialName());
    item.setItemType("NORMAL");
    item.setStatus("READY");
    return item;
  }

  private boolean hasChild(String materialCode, String path, List<Edge> edges) {
    String code = trimToNull(materialCode);
    if (code == null) return false;
    String normalizedPath = trimToNull(path);
    return edges.stream().anyMatch(edge -> code.equals(trimToNull(edge.parentCode()))
        && (normalizedPath == null || !StringUtils.hasText(edge.path())
            || edge.path().startsWith(normalizedPath)));
  }

  private Edge supplementEdge(QuoteBomSupplementDetail row) {
    return new Edge(row == null ? null : row.getParentCode(), row == null ? null : row.getPath());
  }

  private Edge u9Edge(QuoteBomSourceLineDto row) {
    return new Edge(row == null ? null : row.parentCode(), row == null ? null : row.path());
  }

  private Edge packageEdge(QuoteBomPackageReferenceDetail row) {
    return new Edge(row == null ? null : row.getPackageParentCode(),
        row == null ? null : row.getSourcePath());
  }

  private String packagePath(
      String productCode, QuoteBomPackageReferenceDetail row) {
    String source = trimToNull(row.getSourcePath());
    if (source != null && source.contains(row.getPackageMaterialCode())) return source;
    return "/" + required(productCode, "目标产品料号") + "/"
        + required(row.getPackageParentCode(), "包装父项料号") + "/"
        + required(row.getPackageMaterialCode(), "包装底层物料料号") + "/";
  }

  private void rejectInvalidLeaf(String code, String shape, String category) {
    String value = (safe(shape) + " " + safe(category)).trim();
    if (value.contains("制造") || value.contains("自制") || value.contains("虚拟")) {
      throw new IllegalArgumentException("非采购底层物料缺少下级：" + code);
    }
  }

  private String role(
      String name, String shape, String category, String costElement, boolean packageMaterial) {
    if (packageMaterial) return "PACKAGE_MATERIAL";
    String value = String.join(" ", safe(name), safe(shape), safe(category), safe(costElement));
    if (value.contains("废料") || value.contains("废铜") || value.contains("边角")) return "SCRAP";
    if (value.contains("原材料") || value.contains("原料") || value.contains("原材料联动")) {
      return "RAW";
    }
    return "NORMAL";
  }

  private void requireContext(
      QuoteCollaborationProductTask task, QuoteCollaborationQuoteLink owner,
      String accountingMonth) {
    if (task == null || owner == null) throw new IllegalArgumentException("技术任务或报价来源为空");
    required(task.getProductCode(), "产品料号");
    required(accountingMonth, "核算月份");
    required(task.getBusinessUnitType(), "业务单元");
    required(task.getApplicableOrgCode(), "适用组织");
    required(task.getPriceOrgCode(), "价格组织");
    required(task.getMaterialOrgCode(), "料品组织");
    required(owner.getOaNo(), "报价单号");
    if (owner.getOaFormItemId() == null) throw new IllegalArgumentException("报价产品行为空");
  }

  private boolean isRoot(Integer level, String parentCode) {
    return Objects.equals(level, 0) || !StringUtils.hasText(parentCode);
  }

  private BigDecimal positive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + "必须大于0");
    }
    return value.stripTrailingZeros();
  }

  private BigDecimal first(BigDecimal... values) {
    if (values != null) {
      for (BigDecimal value : values) if (value != null) return value;
    }
    return null;
  }

  private String nodeKey(String prefix, Long id, String path) {
    return id != null ? prefix + ":" + id : trimToNull(path);
  }

  private String required(String value, String name) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(name + "不能为空");
    return value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first.trim() : fallback;
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private String exceptionMessage(RuntimeException exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage().trim() : exception.getClass().getSimpleName();
  }

  private record Edge(String parentCode, String path) {}

  private record Candidate(
      String sourceType,
      Long sourceId,
      String nodeKey,
      String path,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialRole,
      BigDecimal quantity,
      String unit) {

    Candidate withQuantity(BigDecimal value) {
      return new Candidate(sourceType, sourceId, nodeKey, path, materialCode, materialName,
          materialSpec, materialModel, materialRole, value, unit);
    }
  }
}
