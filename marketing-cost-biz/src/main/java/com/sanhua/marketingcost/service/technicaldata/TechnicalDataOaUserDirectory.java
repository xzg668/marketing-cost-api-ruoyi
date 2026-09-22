package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaPeer;
import com.sanhua.marketingcost.integration.oa.OaUserMappingRepository;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryRepository;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 外部账号只决定映射身份；办理和审批权限始终来自报价系统当前有效账号。 */
@Service
public class TechnicalDataOaUserDirectory {
  private final OaUserMappingRepository mappings;
  private final SysUserService users;
  private final OaPersonDirectoryRepository directory;
  private final OaPersonDirectoryProperties directoryProperties;

  public TechnicalDataOaUserDirectory(
      OaUserMappingRepository mappings,
      SysUserService users,
      OaPersonDirectoryRepository directory,
      OaPersonDirectoryProperties directoryProperties) {
    this.mappings = mappings;
    this.users = users;
    this.directory = directory;
    this.directoryProperties = directoryProperties;
  }

  public TechnicalDataActor actor(OaPeer peer, String externalId) {
    var mapping = mappings.findExternal(peer, externalId);
    Long userId = mapping == null ? directoryUserId(peer, externalId) : mapping.userId();
    if (userId == null) {
      throw OaIntegrationException.conflict("OA_USER_NOT_MAPPED", "外部人员未关联报价系统账号");
    }
    var user = activeUser(userId);
    return new TechnicalDataActor(user.getUserId(), user.getNickName(), users.findPermissionsByUserId(user.getUserId()));
  }

  public String externalId(OaPeer peer, long userId) {
    activeUser(userId);
    var mapping = mappings.findInternal(peer, userId);
    if (mapping != null) {
      return mapping.externalUserId();
    }
    if (supportsDirectory(peer)) {
      var identity = directory.findActiveBySystemUserId(userId);
      if (identity != null) {
        return identity.employeeNo();
      }
    }
    throw OaIntegrationException.conflict("OA_USER_NOT_MAPPED", "报价系统账号尚未关联 OA 人员：" + userId);
  }

  public SysUser activeUser(long userId) {
    var user = users.findIdentityById(userId);
    if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) {
      throw OaIntegrationException.conflict("OA_USER_DISABLED", "报价系统账号不存在、已删除或停用");
    }
    return user;
  }

  public List<OaUserMappingRepository.Mapping> list(OaPeer peer) { return mappings.list(peer); }

  private Long directoryUserId(OaPeer peer, String externalId) {
    if (!supportsDirectory(peer)) {
      return null;
    }
    var identity = directory.findActiveByEmployeeNo(externalId);
    return identity == null ? null : identity.userId();
  }

  private boolean supportsDirectory(OaPeer peer) {
    return peer != null
        && Objects.equals(peer.sourceSystem(), directoryProperties.getSourceSystem())
        && Objects.equals(peer.environment(), directoryProperties.getEnvironment());
  }

  @Transactional
  public void bind(OaPeer peer, String externalId, long userId, TechnicalDataActor operator) {
    if (operator == null || !operator.admin() || operator.shortSession()) {
      throw OaIntegrationException.conflict("OA_MAPPING_FORBIDDEN", "仅系统管理员可维护人员对应");
    }
    if (externalId == null || !externalId.matches("[A-Za-z0-9._:@-]{1,128}")) {
      throw OaIntegrationException.invalid("INVALID_EXTERNAL_USER", "外部人员编号不合法");
    }
    activeUser(userId);
    var existing = mappings.findExternal(peer, externalId);
    if (existing != null) {
      if (!Objects.equals(existing.userId(), userId)) {
        throw OaIntegrationException.conflict("OA_MAPPING_CONFLICT", "此 OA 账号已关联其他人员，不能覆盖正在使用的身份对应");
      }
      return;
    }
    try { mappings.insert(peer, externalId, userId, operator.userId()); }
    catch (DuplicateKeyException exception) {
      var concurrent = mappings.findExternal(peer, externalId);
      if (concurrent == null || !Objects.equals(concurrent.userId(), userId)) {
        throw OaIntegrationException.conflict("OA_MAPPING_CONFLICT", "人员已关联其他 OA 账号");
      }
    }
  }
}
