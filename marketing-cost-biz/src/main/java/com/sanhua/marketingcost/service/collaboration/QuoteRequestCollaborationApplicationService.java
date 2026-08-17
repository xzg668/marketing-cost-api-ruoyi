package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteTechnicianCandidatesResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteRequestCollaborationApplicationService {
  private final QuoteItemCollaborationProjectionService projectionService;
  private final QuoteCollaborationTaskServiceImpl taskService;
  private final QuoteCollaborationScanService scanService;
  private final CollaborationTechnicianResolver technicianResolver;
  private final CollaborationCurrentActorProvider actorProvider;
  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;

  public QuoteRequestCollaborationApplicationService(
      QuoteItemCollaborationProjectionService projectionService,
      QuoteCollaborationTaskServiceImpl taskService,
      QuoteCollaborationScanService scanService,
      CollaborationTechnicianResolver technicianResolver,
      CollaborationCurrentActorProvider actorProvider,
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper) {
    this.projectionService = projectionService;
    this.taskService = taskService;
    this.scanService = scanService;
    this.technicianResolver = technicianResolver;
    this.actorProvider = actorProvider;
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
  }

  public QuoteCollaborationStartResponse start(
      String oaNo, Long itemId, QuoteCollaborationStartRequest request) {
    QuoteCollaborationStartRequest body = request == null
        ? new QuoteCollaborationStartRequest(null, null) : request;
    QuoteItemCollaborationResponse before = projectionService.project(oaNo, itemId);
    assertVersion(body.expectedProjectionVersion(), before);
    QuoteItemCollaborationAction action = parseAction(before.nextAction());
    boolean manualAssignment = action == QuoteItemCollaborationAction.ASSIGN_TECHNICIAN;
    if (!action.canStartCollaboration() && !manualAssignment) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "当前状态不能发起补录，请刷新页面后按唯一操作处理");
    }
    Long technicianId = null;
    String technicianName = null;
    if (manualAssignment && body.technicianUserId() == null) {
      throw new IllegalArgumentException("请选择技术负责人后再发起补录");
    }
    if (manualAssignment
        || action == QuoteItemCollaborationAction.START_BOM_SUPPLEMENT
        || action == QuoteItemCollaborationAction.START_PACKAGE_SUPPLEMENT
        || action == QuoteItemCollaborationAction.START_PRICE_SUPPLEMENT) {
      if (body.technicianUserId() == null) {
        technicianId = before.assigneeUserId();
        technicianName = before.assigneeName();
      } else {
        OaForm form = requireForm(oaNo);
        OaFormItem item = itemMapper.selectById(itemId);
        requireItem(form, item, itemId);
        QuoteCollaborationScanResult scan = scanService.scanQuoteItem(itemId);
        CollaborationTechnicianResolver.Resolution resolution =
            technicianResolver.resolve(form, item, scan.businessUnitType(), body.technicianUserId());
        if (!resolution.resolved()) throw new IllegalArgumentException(resolution.error());
        technicianId = resolution.userId();
        technicianName = resolution.userName();
      }
      if (technicianId == null || !StringUtils.hasText(technicianName)) {
        throw new IllegalArgumentException("技术负责人未匹配，不能发起补录");
      }
    }
    QuoteCollaborationStartResult result = taskService.start(new QuoteCollaborationStartCommand(
        itemId, technicianId, technicianName, null, null, actorProvider.current()));
    return new QuoteCollaborationStartResponse(result.action().name(), result.idempotentReplay(),
        result.message(), projectionService.project(oaNo, itemId));
  }

  public QuoteCollaborationBatchStartResponse batchStart(
      String oaNo, QuoteCollaborationBatchStartRequest request) {
    if (request == null || request.items().isEmpty()) {
      throw new IllegalArgumentException("请至少选择一个可发起协作的产品");
    }
    List<QuoteCollaborationBatchStartResponse.ItemResult> results = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (QuoteCollaborationBatchStartRequest.Item item : request.items()) {
      if (item == null || item.itemId() == null || !seen.add(item.itemId())) {
        results.add(failure(item == null ? null : item.itemId(), "INVALID_REQUEST", "产品行为空或重复"));
        continue;
      }
      try {
        QuoteCollaborationStartResponse response = start(oaNo, item.itemId(),
            new QuoteCollaborationStartRequest(item.technicianUserId(), item.expectedProjectionVersion()));
        results.add(new QuoteCollaborationBatchStartResponse.ItemResult(item.itemId(), true,
            response.resultAction(), response.replay(), null, response.message(), response.item()));
      } catch (CollaborationDomainException exception) {
        results.add(failure(item.itemId(), exception.code().name(), exception.getMessage()));
      } catch (RuntimeException exception) {
        results.add(failure(item.itemId(), "START_FAILED", exception.getMessage()));
      }
    }
    int success = (int) results.stream().filter(QuoteCollaborationBatchStartResponse.ItemResult::success).count();
    return new QuoteCollaborationBatchStartResponse(success, results.size() - success, results);
  }

  public QuoteTechnicianCandidatesResponse technicianCandidates(String oaNo, Long itemId) {
    QuoteItemCollaborationResponse projection = projectionService.project(oaNo, itemId);
    QuoteItemCollaborationAction action = parseAction(projection.nextAction());
    if (action != QuoteItemCollaborationAction.ASSIGN_TECHNICIAN) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "当前产品不需要指定技术负责人，请刷新页面后按唯一操作处理");
    }
    OaForm form = requireForm(oaNo);
    OaFormItem item = itemMapper.selectById(itemId);
    requireItem(form, item, itemId);
    QuoteCollaborationScanResult scan = scanService.scanQuoteItem(itemId);
    CollaborationTechnicianResolver.CandidateList result = technicianResolver.candidates(
        form, item, scan.businessUnitType());
    List<QuoteTechnicianCandidatesResponse.Candidate> candidates = result.candidates().stream()
        .map(candidate -> new QuoteTechnicianCandidatesResponse.Candidate(
            candidate.userId(), candidate.userName(), candidate.loginName(), candidate.oaUserId(),
            candidate.jobNo(), candidate.recommended(), candidate.ruleCode(), candidate.ruleName()))
        .toList();
    return new QuoteTechnicianCandidatesResponse(
        itemId, result.matchStatus(), result.message(), candidates);
  }

  private static void assertVersion(String expected, QuoteItemCollaborationResponse current) {
    if (StringUtils.hasText(expected) && !expected.equals(current.projectionVersion())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
          "产品状态已变化，请刷新后重试");
    }
  }
  private static QuoteItemCollaborationAction parseAction(String action) {
    try { return QuoteItemCollaborationAction.valueOf(action); }
    catch (RuntimeException exception) { return QuoteItemCollaborationAction.NONE; }
  }
  private static QuoteCollaborationBatchStartResponse.ItemResult failure(
      Long itemId, String code, String message) {
    return new QuoteCollaborationBatchStartResponse.ItemResult(itemId, false, null, false,
        code, StringUtils.hasText(message) ? message : "发起失败", null);
  }

  private OaForm requireForm(String oaNo) {
    if (!StringUtils.hasText(oaNo)) throw new IllegalArgumentException("报价单号不能为空");
    List<OaForm> forms = formMapper.selectList(Wrappers.<OaForm>lambdaQuery()
        .eq(OaForm::getOaNo, oaNo.trim()));
    if (forms.size() != 1) {
      throw new IllegalArgumentException(forms.isEmpty() ? "报价单不存在" : "报价单号不唯一");
    }
    return forms.get(0);
  }

  private static void requireItem(OaForm form, OaFormItem item, Long itemId) {
    if (itemId == null || item == null || !form.getId().equals(item.getOaFormId())) {
      throw new IllegalArgumentException("报价产品不属于当前报价单");
    }
  }
}
