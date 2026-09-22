package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TechnicalDataAssigneeResolver {
  private static final Set<String> EDIT_PERMISSIONS = Set.of(
      "*:*:*", "technical:data:task:edit", "technical:data:admin:operate");
  private final SysUserService userService;

  public TechnicalDataAssigneeResolver(SysUserService userService) {
    this.userService = userService;
  }

  public SysUser resolve(Long userId) {
    SysUser user = userService.getById(userId);
    if (user == null || !"0".equals(user.getStatus())
        || (StringUtils.hasText(user.getDelFlag()) && !"0".equals(user.getDelFlag()))) {
      throw invalid("技术员不存在或已停用：" + userId);
    }
    var permissions = userService.findPermissionsByUserId(userId);
    if (permissions == null || permissions.stream().noneMatch(EDIT_PERMISSIONS::contains)) {
      throw invalid("所选用户没有补录办理权限：" + userId);
    }
    return user;
  }

  private TechnicalDataTaskException invalid(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }
}
