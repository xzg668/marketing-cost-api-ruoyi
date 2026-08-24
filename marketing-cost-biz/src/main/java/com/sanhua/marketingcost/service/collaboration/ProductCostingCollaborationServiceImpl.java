package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.PricePrepareCurrentStateService;
import com.sanhua.marketingcost.service.ProductCostingCollaborationService;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProductCostingCollaborationServiceImpl
    implements ProductCostingCollaborationService {

  private static final String WAIT_BOM = "WAIT_BOM";
  private static final String WAIT_PRICE_TYPE = "WAIT_PRICE_TYPE";
  private static final String WAIT_PRICE = "WAIT_PRICE";

  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final QuoteCollaborationScanService scanService;
  private final CollaborationTechnicianResolver technicianResolver;
  private final QuoteCollaborationTaskServiceImpl taskService;
  private final QuoteCollaborationTaskRepository repository;
  private final SysUserService userService;
  private final PricePrepareCurrentStateService priceStateService;

  public ProductCostingCollaborationServiceImpl(
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      QuoteCollaborationScanService scanService,
      CollaborationTechnicianResolver technicianResolver,
      QuoteCollaborationTaskServiceImpl taskService,
      QuoteCollaborationTaskRepository repository,
      SysUserService userService,
      PricePrepareCurrentStateService priceStateService) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.scanService = scanService;
    this.technicianResolver = technicianResolver;
    this.taskService = taskService;
    this.repository = repository;
    this.userService = userService;
    this.priceStateService = priceStateService;
  }

  @Override
  public CoordinationResult coordinate(CoordinationCommand command) {
    requireCommand(command);
    if (!List.of(WAIT_BOM, WAIT_PRICE_TYPE, WAIT_PRICE)
        .contains(command.blockingStatus())) {
      return CoordinationResult.notCreated("NOT_APPLICABLE", "当前阻塞不需要业务协作");
    }
    try {
      OaFormItem item = requireItem(command.oaFormItemId());
      OaForm form = requireForm(command.oaNo(), item.getOaFormId());
      QuoteCollaborationScanResult scan = scanService.scanQuoteItem(
          item.getId(), command.periodMonth());
      if (scan.action() == QuoteCollaborationScanAction.SYSTEM_BLOCKED) {
        return CoordinationResult.notCreated("SCAN_BLOCKED", scan.message());
      }
      if (scan.action() == QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE) {
        return new CoordinationResult(null, "EXTERNAL_MAINTENANCE", null, "财务报价",
            false, false, scan.message());
      }
      if (scan.action() == QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED) {
        String handler = WAIT_PRICE_TYPE.equals(command.blockingStatus())
            || WAIT_PRICE.equals(command.blockingStatus()) ? "财务报价" : null;
        return new CoordinationResult(null, "EXTERNAL_MAINTENANCE", null, handler,
            false, false, scan.message());
      }

      CollaborationTechnicianResolver.Resolution technician = null;
      if (scan.action() == QuoteCollaborationScanAction.CREATE_COLLABORATION) {
        technician = technicianResolver.resolve(form, item, scan.businessUnitType(), null);
        if (!technician.resolved()) {
          return CoordinationResult.notCreated("TECHNICIAN_UNASSIGNED", technician.error());
        }
      }
      CollaborationActor actor = actor(command.initiatedBy());
      QuoteCollaborationStartResult started = taskService.startAutomatically(
          new QuoteCollaborationStartCommand(
              item.getId(),
              technician == null ? null : technician.userId(),
              technician == null ? null : technician.userName(),
              null,
              null,
              command.periodMonth(),
              actor));
      boolean persisted = hasActiveGapFacts(started.productTaskId(), scan);
      boolean discarded = persisted && StringUtils.hasText(command.transientPrepareNo())
          && priceStateService.discardPromotedFailedAttempt(command.transientPrepareNo());
      return new CoordinationResult(
          started.productTaskId(),
          started.currentStatus(),
          started.currentAssigneeUserId(),
          started.currentAssigneeName(),
          persisted,
          discarded,
          started.message());
    } catch (RuntimeException exception) {
      return CoordinationResult.notCreated(
          "COORDINATION_FAILED", firstText(exception.getMessage(), "协作任务生成失败"));
    }
  }

  private boolean hasActiveGapFacts(Long productTaskId, QuoteCollaborationScanResult scan) {
    if (productTaskId == null) {
      return false;
    }
    CollaborationScope scope = new CollaborationScope(
        scan.businessUnitType(), scan.priceOrgCode());
    return repository.findGaps(productTaskId, scope).stream()
        .filter(gap -> gap != null && isActive(gap))
        .anyMatch(gap -> matchesCurrentStage(gap, scan));
  }

  private boolean matchesCurrentStage(
      QuoteCollaborationGap gap, QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == CollaborationCodes.PrimaryScope.PRICE_ONLY) {
      return CollaborationCodes.GapCategory.PRICE.code().equals(gap.getGapCategory())
          && scan.accountingMonth().equals(gap.getAccountingMonth());
    }
    return !CollaborationCodes.GapCategory.PRICE.code().equals(gap.getGapCategory());
  }

  private static boolean isActive(QuoteCollaborationGap gap) {
    return !List.of(
        CollaborationCodes.GapStatus.RESOLVED.code(),
        CollaborationCodes.GapStatus.WAIVED.code(),
        CollaborationCodes.GapStatus.OBSOLETE.code()).contains(gap.getGapStatus());
  }

  private CollaborationActor actor(String initiatedBy) {
    SysUser user = StringUtils.hasText(initiatedBy)
        ? userService.findByUsername(initiatedBy.trim()) : null;
    if (user != null && user.getUserId() != null && user.getUserId() > 0) {
      return new CollaborationActor(user.getUserId(), firstText(user.getNickName(), user.getUserName()));
    }
    return new CollaborationActor(0L,
        StringUtils.hasText(initiatedBy) ? "系统一键核算(" + initiatedBy.trim() + ")" : "系统一键核算");
  }

  private OaFormItem requireItem(Long itemId) {
    OaFormItem item = itemMapper.selectById(itemId);
    if (item == null) {
      throw new IllegalArgumentException("报价产品行不存在");
    }
    return item;
  }

  private OaForm requireForm(String oaNo, Long formId) {
    OaForm form = formMapper.selectOne(Wrappers.<OaForm>lambdaQuery()
        .eq(OaForm::getId, formId)
        .eq(OaForm::getOaNo, oaNo)
        .last("LIMIT 1"));
    if (form == null) {
      throw new IllegalArgumentException("报价产品不属于当前OA");
    }
    return form;
  }

  private static void requireCommand(CoordinationCommand command) {
    if (command == null || command.oaFormItemId() == null || command.oaFormItemId() <= 0
        || !StringUtils.hasText(command.oaNo()) || !StringUtils.hasText(command.periodMonth())
        || !StringUtils.hasText(command.blockingStatus())) {
      throw new IllegalArgumentException("产品协作命令缺少报价、产品、月份或阻塞状态");
    }
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
