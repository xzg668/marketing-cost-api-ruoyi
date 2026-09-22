package com.sanhua.marketingcost.service.electronicdrawing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把接口取得的电子图库 Excel 绑定到报价产品并保存不可变源版本。
 *
 * <p>本服务只负责解析和源证据入库，不查询 U9、不发布 BOM、也不触发价格核算。
 */
@Service
public class ElectronicDrawingSourceImportService {
  private static final String BOM_SOURCE = "ELECTRONIC_DRAWING_EXCEL";
  private static final String VERSION_DRAFT = "DRAFT";

  private final ElectronicDrawingExcelParser parser;
  private final ElectronicDrawingWorkflowContextPort contextPort;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomPreparationRecordMapper preparationMapper;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  private final ElectronicDrawingProductLookup productLookup;

  public ElectronicDrawingSourceImportService(
      ElectronicDrawingExcelParser parser,
      ElectronicDrawingWorkflowContextPort contextPort,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomPreparationRecordMapper preparationMapper,
      QuoteBomSupplementVersionMapper versionMapper,
      ElectronicDrawingSourceNodeRepository sourceNodeRepository,
      ElectronicDrawingProductLookup productLookup) {
    this.parser = parser;
    this.contextPort = contextPort;
    this.oaFormItemMapper = oaFormItemMapper;
    this.preparationMapper = preparationMapper;
    this.versionMapper = versionMapper;
    this.sourceNodeRepository = sourceNodeRepository;
    this.productLookup = productLookup;
  }

  @Transactional
  public ImportResult importSource(
      ImportCommand command, ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired) {
    ValidCommand validCommand = validateCommand(command);
    byte[] content = validateAcquired(validCommand.requestedDrawingNo(), acquired);
    ElectronicDrawingWorkContext context = contextPort.load(
        validCommand.workflowId(), validCommand.businessUnitType(),
        validCommand.applicableOrgCode(), validCommand.accountingMonth());
    validateContext(context);

    OaFormItem quoteItem = oaFormItemMapper.selectById(context.oaFormItemId());
    validateQuoteBinding(context, quoteItem);
    String quoteDrawingNo = productLookup.requireDrawing(context, validCommand.requestedDrawingNo());
    requireSameDrawing(validCommand.requestedDrawingNo(), acquired.drawingNo(), "请求图号与接口响应图号不一致");
    requireSameDrawing(validCommand.requestedDrawingNo(), quoteDrawingNo, "请求图号与报价产品图号不一致");

    ElectronicDrawingExcelParseResult parsed = parser.parse(
        acquired.fileName(), new ByteArrayInputStream(content));
    if (!parsed.valid()) {
      throw new ElectronicDrawingSourceImportException(
          ElectronicDrawingSourceImportException.PARSE_INVALID,
          "电子图库 Excel 解析失败：" + parsed.issues().getFirst().message(),
          parsed.issues());
    }

    QuoteBomPreparationRecord preparation = preparationMapper
        .selectForElectronicDrawingImport(context.preparationId());
    validatePreparation(context, preparation);
    String supplementScope = supplementScope(context);

    QuoteBomSupplementVersion existing = findSameSource(
        preparation.getId(), supplementScope, context, quoteDrawingNo, acquired.sha256());
    if (existing != null) {
      List<ElectronicDrawingSourceNode> storedNodes = sourceNodeRepository.findByVersionId(existing.getId());
      if (!sameImmutableNodes(storedNodes, parsed.nodes())) {
        throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID,
            "相同 SHA 的电子图库源版本与已保存原始行不一致");
      }
      if (sameWeightUnits(storedNodes, parsed.nodes())) {
        boolean current = Objects.equals(context.sourceVersionId(), existing.getId());
        if (!current) {
          attach(context, existing.getId());
          current = true;
        }
        return result(existing, storedNodes.size(), true, current);
      }
      // 同一文件按新单位约定重查时另存版本，不回填或覆盖旧源节点及其审批引用。
    }

    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    QuoteBomSupplementVersion version = newVersion(
        context, preparation, parsed, acquired, quoteDrawingNo, supplementScope, now);
    if (versionMapper.insert(version) != 1 || version.getId() == null) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库源版本创建失败");
    }
    List<ElectronicDrawingSourceNode> sourceNodes = parsed.nodes().stream()
        .map(node -> sourceNode(version.getId(), node, now))
        .toList();
    sourceNodeRepository.insertAll(version.getId(), sourceNodes);
    attach(context, version.getId());
    return result(version, sourceNodes.size(), false, true);
  }

  private ValidCommand validateCommand(ImportCommand command) {
    if (command == null || command.workflowId() == null || command.workflowId() <= 0) {
      throw error(ElectronicDrawingSourceImportException.COMMAND_INVALID, "电子图库工作上下文ID不能为空");
    }
    return new ValidCommand(
        command.workflowId(),
        requiredText(command.businessUnitType(), "业务单元",
            ElectronicDrawingSourceImportException.COMMAND_INVALID),
        requiredText(command.applicableOrgCode(), "适用组织",
            ElectronicDrawingSourceImportException.COMMAND_INVALID),
        requiredText(command.requestedDrawingNo(), "请求图号",
            ElectronicDrawingSourceImportException.COMMAND_INVALID),
        parseMonth(command.accountingMonth()).toString());
  }

  private byte[] validateAcquired(
      String requestedDrawingNo, ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired) {
    if (acquired == null) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库接口响应不能为空");
    }
    requireSameDrawing(requestedDrawingNo, acquired.drawingNo(), "请求图号与接口响应图号不一致");
    byte[] content = acquired.content();
    if (content.length == 0 || acquired.fileSize() != content.length) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库文件大小证据不一致");
    }
    String fileName = requiredText(
        acquired.fileName(), "电子图库文件名", ElectronicDrawingSourceImportException.SOURCE_INVALID);
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库文件必须为 xlsx");
    }
    String expectedSha = requiredText(
        acquired.sha256(), "电子图库文件 SHA-256", ElectronicDrawingSourceImportException.SOURCE_INVALID)
        .toLowerCase(Locale.ROOT);
    if (!expectedSha.matches("[0-9a-f]{64}") || !expectedSha.equals(sha256(content))) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库文件 SHA-256 证据不一致");
    }
    requiredText(acquired.requestId(), "电子图库请求标识",
        ElectronicDrawingSourceImportException.SOURCE_INVALID);
    if (acquired.acquiredAt() == null) {
      throw error(ElectronicDrawingSourceImportException.SOURCE_INVALID, "电子图库取数时间不能为空");
    }
    return content;
  }

  private void validateContext(ElectronicDrawingWorkContext context) {
    if (context == null || !context.bomRequired() || !context.active()) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "当前报价产品不是活动的 BOM 处理对象");
    }
    if (context.preparationId() == null || context.revision() == null
        || context.oaFormItemId() == null || context.oaFormId() == null) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "报价产品缺少 BOM 准备记录、报价产品行或上下文版本");
    }
    requiredText(context.taskNo(), "业务任务号",
        ElectronicDrawingSourceImportException.BINDING_INVALID);
    requiredText(context.quoteProductCode(), "顶层料号",
        ElectronicDrawingSourceImportException.BINDING_INVALID);
    requiredText(context.materialOrgCode(), "物料组织",
        ElectronicDrawingSourceImportException.BINDING_INVALID);
    parseMonth(context.accountingMonth());
  }

  private void validateQuoteBinding(
      ElectronicDrawingWorkContext context,
      OaFormItem quoteItem) {
    if (quoteItem == null
        || !Objects.equals(quoteItem.getId(), context.oaFormItemId())
        || !Objects.equals(quoteItem.getOaFormId(), context.oaFormId())
        || !sameText(quoteItem.getBusinessUnitType(), context.businessUnitType())
        || !sameText(com.sanhua.marketingcost.util.QuoteProductIdentityUtils.resolveCostingCode(quoteItem),
            context.quoteProductCode())) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "电子图库上下文、报价产品行和顶层料号绑定不一致");
    }
  }

  private void validatePreparation(
      ElectronicDrawingWorkContext context,
      QuoteBomPreparationRecord preparation) {
    if (preparation == null
        || !Objects.equals(preparation.getId(), context.preparationId())
        || !Objects.equals(preparation.getOaFormItemId(), context.oaFormItemId())
        || !Objects.equals(preparation.getOaFormId(), context.oaFormId())
        || !sameText(preparation.getOaNo(), context.oaNo())
        || !sameText(preparation.getQuoteProductCode(), context.quoteProductCode())
        || !sameText(preparation.getCostPeriodMonth(), context.accountingMonth())
        || !sameText(preparation.getMaterialOrganizationCode(), context.materialOrgCode())
        || Objects.equals(preparation.getActiveFlag(), 0)) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "BOM 准备记录与报价产品行、物料组织或核算月份不一致");
    }
    requiredText(preparation.getProductType(), "产品类型",
        ElectronicDrawingSourceImportException.BINDING_INVALID);
  }

  private QuoteBomSupplementVersion findSameSource(
      Long preparationId,
      String supplementScope,
      ElectronicDrawingWorkContext context,
      String drawingNo,
      String sha) {
    List<QuoteBomSupplementVersion> rows = versionMapper.selectList(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getPreparationId, preparationId)
            .eq(QuoteBomSupplementVersion::getSupplementScope, supplementScope)
            .eq(QuoteBomSupplementVersion::getBomSource, BOM_SOURCE)
            .eq(QuoteBomSupplementVersion::getTaskNo, context.taskNo())
            .eq(QuoteBomSupplementVersion::getPeriodMonth, context.accountingMonth())
            .eq(QuoteBomSupplementVersion::getElectronicDrawingNo, drawingNo)
            .eq(QuoteBomSupplementVersion::getMaterialOrgCode, context.materialOrgCode())
            .eq(QuoteBomSupplementVersion::getSourceFileSha256, sha.toLowerCase(Locale.ROOT))
            .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
            .orderByDesc(QuoteBomSupplementVersion::getId)
            .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private QuoteBomSupplementVersion newVersion(
      ElectronicDrawingWorkContext context,
      QuoteBomPreparationRecord preparation,
      ElectronicDrawingExcelParseResult parsed,
      ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired,
      String quoteDrawingNo,
      String supplementScope,
      LocalDateTime now) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setPreparationId(preparation.getId());
    version.setTaskNo(context.taskNo());
    version.setOaNo(context.oaNo());
    version.setOaFormItemId(context.oaFormItemId());
    version.setQuoteProductCode(context.quoteProductCode().trim());
    version.setProductType("NON_BARE");
    version.setSupplementScope(supplementScope);
    version.setBomSource(BOM_SOURCE);
    version.setElectronicDrawingNo(quoteDrawingNo.trim());
    version.setSourceFileName(acquired.fileName().trim());
    version.setSourceFileSha256(acquired.sha256().trim().toLowerCase(Locale.ROOT));
    version.setSourceFileSize(acquired.fileSize());
    version.setSourceSheetName(parsed.sourceSheetName());
    version.setSourceAcquiredAt(acquired.acquiredAt());
    version.setSourceRequestId(acquired.requestId().trim());
    version.setMaterialOrgCode(context.materialOrgCode().trim());
    version.setVersionNo(nextVersionNo(preparation.getId(), supplementScope));
    version.setVersionStatus(VERSION_DRAFT);
    version.setActiveFlag(1);
    version.setPeriodMonth(context.accountingMonth().trim());
    version.setEffectiveFrom(parseMonth(context.accountingMonth()).atDay(1));
    version.setCreatedAt(now);
    version.setUpdatedAt(now);
    return version;
  }

  private int nextVersionNo(Long preparationId, String supplementScope) {
    List<QuoteBomSupplementVersion> rows = versionMapper.selectList(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getPreparationId, preparationId)
            .eq(QuoteBomSupplementVersion::getSupplementScope, supplementScope)
            .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
            .last("LIMIT 1"));
    return rows.isEmpty() || rows.getFirst().getVersionNo() == null
        ? 1 : Math.addExact(rows.getFirst().getVersionNo(), 1);
  }

  private ElectronicDrawingSourceNode sourceNode(
      Long versionId, ElectronicDrawingExcelParseResult.SourceNode source, LocalDateTime now) {
    ElectronicDrawingSourceNode node = new ElectronicDrawingSourceNode();
    node.setSupplementVersionId(versionId);
    node.setSourceRowNo(source.sourceRowNumber());
    node.setSourceSequence(source.sourceSequence());
    node.setParentSourceSequence(source.parentSourceSequence());
    node.setDrawingCode(source.drawingCode());
    node.setSourceName(source.sourceName());
    node.setQty(source.quantity());
    node.setMaterial(source.sourceMaterial());
    node.setImportanceClass(source.importanceClass());
    node.setHsfRiskClass(source.hsfRiskClass());
    node.setReferenceWeight(source.referenceWeight());
    node.setReferenceWeightUnit(source.referenceWeightUnit());
    node.setSourceRemark(source.remark());
    node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    node.setCreatedAt(now);
    node.setUpdatedAt(now);
    return node;
  }

  private void attach(ElectronicDrawingWorkContext context, Long versionId) {
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    try {
      contextPort.attachSourceVersion(context, versionId, now);
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      throw error(ElectronicDrawingSourceImportException.TASK_VERSION_CONFLICT,
          "产品任务版本已变化，请重新检查后重试");
    }
  }

  private boolean sameWeightUnits(
      List<ElectronicDrawingSourceNode> stored,
      List<ElectronicDrawingExcelParseResult.SourceNode> parsed) {
    for (int index = 0; index < stored.size(); index++) {
      if (!Objects.equals(stored.get(index).getReferenceWeightUnit(),
          parsed.get(index).referenceWeightUnit())) return false;
    }
    return true;
  }

  private boolean sameImmutableNodes(
      List<ElectronicDrawingSourceNode> stored,
      List<ElectronicDrawingExcelParseResult.SourceNode> parsed) {
    if (stored.size() != parsed.size()) return false;
    for (int index = 0; index < stored.size(); index++) {
      ElectronicDrawingSourceNode left = stored.get(index);
      ElectronicDrawingExcelParseResult.SourceNode right = parsed.get(index);
      if (!Objects.equals(left.getSourceRowNo(), right.sourceRowNumber())
          || !Objects.equals(left.getSourceSequence(), right.sourceSequence())
          || !Objects.equals(left.getParentSourceSequence(), right.parentSourceSequence())
          || !Objects.equals(left.getDrawingCode(), right.drawingCode())
          || !Objects.equals(left.getSourceName(), right.sourceName())
          || !decimalEquals(left.getQty(), right.quantity())
          || !Objects.equals(left.getMaterial(), right.sourceMaterial())
          || !Objects.equals(left.getImportanceClass(), right.importanceClass())
          || !Objects.equals(left.getHsfRiskClass(), right.hsfRiskClass())
          || !decimalEquals(left.getReferenceWeight(), right.referenceWeight())
          || !Objects.equals(left.getSourceRemark(), right.remark())) {
        return false;
      }
    }
    return true;
  }

  private static ImportResult result(
      QuoteBomSupplementVersion version, int nodeCount, boolean reused, boolean current) {
    return new ImportResult(
        version.getId(), version.getVersionNo(), version.getVersionStatus(), nodeCount,
        version.getSourceFileSha256(), reused, current);
  }

  private static void requireSameDrawing(String left, String right, String message) {
    if (!sameText(left, right)) {
      throw error(ElectronicDrawingSourceImportException.DRAWING_MISMATCH, message);
    }
  }

  private static YearMonth parseMonth(String value) {
    try {
      return YearMonth.parse(requiredText(value, "核算月份",
          ElectronicDrawingSourceImportException.BINDING_INVALID));
    } catch (DateTimeParseException exception) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "核算月份格式必须为 YYYY-MM");
    }
  }

  private static String supplementScope(ElectronicDrawingWorkContext context) {
    if (context == null || !"FULL_BOM".equalsIgnoreCase(context.primaryScope())) {
      throw error(ElectronicDrawingSourceImportException.BINDING_INVALID,
          "电子图库兜底只允许处理 U9 根 BOM 明确缺失的完整 BOM 任务");
    }
    // 电子图库兜底得到的是本次报价的完整混合 BOM。即使 U9 料品主档把顶层料号归为裸品，
    // 也不能再次要求包装参考，否则已合成的 18+10 计价叶会在六步核算入口被错误阻断。
    return "NON_BARE_FULL_BOM";
  }

  private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static boolean sameText(String left, String right) {
    String normalizedLeft = trim(left);
    String normalizedRight = trim(right);
    return normalizedLeft != null && normalizedRight != null
        && normalizedLeft.equalsIgnoreCase(normalizedRight);
  }

  private static String requiredText(String value, String label, String code) {
    String normalized = trim(value);
    if (normalized == null) throw error(code, label + "不能为空");
    return normalized;
  }

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }

  private static ElectronicDrawingSourceImportException error(String code, String message) {
    return new ElectronicDrawingSourceImportException(code, message);
  }

  public record ImportCommand(
      Long workflowId,
      String businessUnitType,
      String applicableOrgCode,
      String requestedDrawingNo,
      String accountingMonth) {}

  public record ImportResult(
      Long supplementVersionId,
      Integer versionNo,
      String versionStatus,
      int sourceNodeCount,
      String sourceFileSha256,
      boolean reused,
      boolean currentVersion) {}

  private record ValidCommand(
      Long workflowId,
      String businessUnitType,
      String applicableOrgCode,
      String requestedDrawingNo,
      String accountingMonth) {}
}
