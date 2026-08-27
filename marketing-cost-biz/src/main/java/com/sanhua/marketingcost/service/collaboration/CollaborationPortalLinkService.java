package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.CollaborationPortalAccessLinkResponse;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.CollaborationPortalAuthentication;
import com.sanhua.marketingcost.security.CollaborationPortalGrantCodec;
import com.sanhua.marketingcost.security.CollaborationPortalModule;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一生成 BOM、价格和 CMS 外部协作链接。
 *
 * <p>一个链接按“协作主任务 + 技术员”聚合该技术员负责的多个产品，不为每个产品重复发链接。
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CollaborationPortalLinkService {
  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationTokenService tokenService;
  private final CollaborationPortalGrantCodec grantCodec;
  private final SysUserService userService;
  private final String portalBaseUrl;
  private final int expireHours;

  public CollaborationPortalLinkService(
      QuoteCollaborationTaskRepository repository,
      CollaborationTokenService tokenService,
      CollaborationPortalGrantCodec grantCodec,
      SysUserService userService,
      @Value("${quote.collaboration.portal.base-url:http://localhost:5173}") String portalBaseUrl,
      @Value("${quote.collaboration.portal.token-expire-hours:168}") int expireHours) {
    this.repository = repository;
    this.tokenService = tokenService;
    this.grantCodec = grantCodec;
    this.userService = userService;
    this.portalBaseUrl = requireText(portalBaseUrl, "外部协作门户地址");
    if (expireHours <= 0) throw new IllegalArgumentException("协作令牌有效小时数必须大于0");
    this.expireHours = expireHours;
  }

  public CollaborationPortalAccessLinkResponse issue(Long productTaskId) {
    String businessUnit = CollaborationScope.requireBusinessUnit(
        BusinessUnitContext.getCurrentBusinessUnitType());
    QuoteCollaborationProductTask target = repository
        .findProductTaskByIdAndBusinessUnit(productTaskId, businessUnit)
        .orElseThrow(CollaborationPortalLinkService::notFound);
    Long technicianId = technicianId(target);
    SysUser technician = userService.getById(technicianId);
    if (!active(technician)
        || !Objects.equals(businessUnit, technician.getBusinessUnitType())) {
      throw new IllegalArgumentException("技术负责人账号无效、已停用或不属于当前业务单元");
    }

    List<QuoteCollaborationProductTask> assigned = repository
        .findProductTasksByCollaboration(target.getOriginCollaborationId(), businessUnit).stream()
        .filter(task -> Objects.equals(technicianId, technicianId(task)))
        .toList();
    Set<CollaborationPortalModule> modules = modules(assigned);
    if (modules.isEmpty()) {
      throw new IllegalArgumentException("该技术负责人当前没有可处理的协作内容");
    }
    String grant = grantCodec.encode(target.getOriginCollaborationId(), modules);
    LpCollaborationToken token = tokenService.getOrCreateReusableToken(
        technicianId, CollaborationPortalAuthentication.TOKEN_TYPE, grant, expireHours);
    String url = trimTrailingSlash(portalBaseUrl)
        + "/collaborate/tasks#access_token=" + token.getToken();
    String technicianName = StringUtils.hasText(technician.getNickName())
        ? technician.getNickName() : technician.getUserName();
    List<String> moduleCodes = modules.stream().map(Enum::name).sorted().toList();
    return new CollaborationPortalAccessLinkResponse(
        target.getOriginCollaborationId(), technicianId, technicianName,
        moduleCodes, url, token.getExpireTime());
  }

  private static Set<CollaborationPortalModule> modules(
      List<QuoteCollaborationProductTask> tasks) {
    EnumSet<CollaborationPortalModule> result = EnumSet.noneOf(CollaborationPortalModule.class);
    for (QuoteCollaborationProductTask task : tasks) {
      if (enabled(task.getNeedBom()) || enabled(task.getNeedPackage())) {
        result.add(CollaborationPortalModule.BOM);
      }
      if (enabled(task.getNeedPrice())) result.add(CollaborationPortalModule.PRICE);
      // CMS 使用同一授权模型；当前任务表尚无 need_cms，不能在这里伪造 CMS 待办。
    }
    return result;
  }

  private static Long technicianId(QuoteCollaborationProductTask task) {
    if (task == null) return null;
    return task.getOriginalTechnicianUserId() != null
        ? task.getOriginalTechnicianUserId() : task.getCurrentAssigneeUserId();
  }

  private static boolean active(SysUser user) {
    return user != null && user.getUserId() != null && "0".equals(user.getStatus())
        && (!StringUtils.hasText(user.getDelFlag()) || "0".equals(user.getDelFlag()));
  }

  private static boolean enabled(Integer value) {
    return Integer.valueOf(1).equals(value);
  }

  private static String trimTrailingSlash(String value) {
    return value.replaceAll("/+$", "");
  }

  private static String requireText(String value, String label) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + "不能为空");
    return value.trim();
  }

  private static CollaborationDomainException notFound() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_NOT_FOUND, "协作产品任务不存在");
  }
}
