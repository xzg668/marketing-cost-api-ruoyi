package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysUserAssignRolesRequest;
import com.sanhua.marketingcost.dto.system.SysUserCreateRequest;
import com.sanhua.marketingcost.dto.system.SysUserResetPasswordRequest;
import com.sanhua.marketingcost.dto.system.SysUserUpdateRequest;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.vo.SysUserVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/system/user")
public class SysUserController {

    private static final String ADMIN_ROLE_KEY = "ADMIN";
    private static final Set<String> BU_DIRECTOR_ASSIGNABLE = Set.of("BU_STAFF");

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

    public SysUserController(SysUserService sysUserService, PasswordEncoder passwordEncoder) {
        this.sysUserService = sysUserService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public CommonResult<Map<String, Object>> list(
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessUnitType,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        Page<SysUser> result = sysUserService.listUsers(userName, phone, status,
                businessUnitType, page, pageSize);
        List<SysUserVO> voList = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("rows", voList);
        return CommonResult.success(data);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:user:query')")
    public CommonResult<SysUserVO> get(@PathVariable("id") Long id) {
        SysUser user = sysUserService.getById(id);
        if (user == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!canOperateUser(user)) {
            return forbiddenCrossBusinessUnit();
        }
        return CommonResult.success(toVO(user));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    // 用户新增：记录审计日志，保存入参便于后续溯源
    @OperationLog(module = "用户管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysUserCreateRequest req) {
        if (sysUserService.findByUsername(req.getUserName()) != null) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUserName(req.getUserName());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickName(req.getNickName());
        user.setPhone(req.getPhone());
        user.setSex(req.getSex());
        user.setDeptId(req.getDeptId());
        // 岗位列表转换为逗号分隔字符串（null 保持为 null，空集合存空串）
        user.setPostIds(joinPostIds(req.getPostIds()));
        user.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        user.setRemark(req.getRemark());
        user.setBusinessUnitType(BusinessUnitContext.getCurrentBusinessUnitType());
        sysUserService.createUser(user);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    // 用户编辑：记录目标ID与差异
    @OperationLog(module = "用户管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysUserUpdateRequest req) {
        SysUser existing = sysUserService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!canOperateUser(existing)) {
            return forbiddenCrossBusinessUnit();
        }
        if (sysUserService.isLastAdmin(id) && "1".equals(req.getStatus())) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "系统至少需要保留一个有效的管理员账户");
        }
        SysUser user = new SysUser();
        user.setUserId(id);
        if (req.getNickName() != null) user.setNickName(req.getNickName());
        if (req.getPhone() != null) user.setPhone(req.getPhone());
        if (req.getSex() != null) user.setSex(req.getSex());
        if (req.getDeptId() != null) user.setDeptId(req.getDeptId());
        // postIds==null 表示不更新；非 null 时统一重置为当前选择（含空集合清空）
        if (req.getPostIds() != null) user.setPostIds(joinPostIds(req.getPostIds()));
        if (req.getStatus() != null) user.setStatus(req.getStatus());
        if (req.getRemark() != null) user.setRemark(req.getRemark());
        if (req.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
        user.setBusinessUnitType(BusinessUnitContext.getCurrentBusinessUnitType());
        sysUserService.updateUser(user);
        return CommonResult.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:user:remove')")
    // 用户删除：记录目标ID
    @OperationLog(module = "用户管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        SysUser existing = sysUserService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!canOperateUser(existing)) {
            return forbiddenCrossBusinessUnit();
        }
        if (sysUserService.isLastAdmin(id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "系统至少需要保留一个有效的管理员账户");
        }
        sysUserService.deleteUser(id);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}/reset-password")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    // 用户重置密码：记录目标ID（不记 recordDiff 避免密码入库）
    @OperationLog(module = "用户管理-重置密码", operationType = OperationType.UPDATE, targetIdParam = "id")
    public CommonResult<Void> resetPassword(@PathVariable("id") Long id,
                                            @Valid @RequestBody SysUserResetPasswordRequest req) {
        SysUser existing = sysUserService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!canOperateUser(existing)) {
            return forbiddenCrossBusinessUnit();
        }
        sysUserService.resetPassword(id, passwordEncoder.encode(req.getNewPassword()));
        return CommonResult.success(null);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    // 用户分配角色：记录目标ID与角色ID列表
    @OperationLog(module = "用户管理-分配角色", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> assignRoles(@PathVariable("id") Long id,
                                          @Valid @RequestBody SysUserAssignRolesRequest req,
                                          Authentication authentication) {
        SysUser existing = sysUserService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "用户不存在");
        }
        if (!canOperateUser(existing)) {
            return forbiddenCrossBusinessUnit();
        }
        boolean isLastAdminCheck = sysUserService.isLastAdmin(id);
        if (isLastAdminCheck) {
            boolean stillHasAdmin = req.getRoleIds().stream().anyMatch(roleId -> {
                List<SysRole> allRoles = sysUserService.listAllRoles();
                return allRoles.stream()
                        .anyMatch(r -> r.getRoleId().equals(roleId)
                                && ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
            });
            if (!stillHasAdmin) {
                return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                        "系统至少需要保留一个有效的管理员账户");
            }
        }
        if (!BusinessUnitContext.isAdmin()) {
            List<SysRole> allRoles = sysUserService.listAllRoles();
            Set<String> requestedKeys = req.getRoleIds().stream()
                    .map(roleId -> allRoles.stream()
                            .filter(r -> r.getRoleId().equals(roleId))
                            .findFirst()
                            .map(SysRole::getRoleKey)
                            .orElse(""))
                    .collect(Collectors.toSet());
            boolean forbidden = requestedKeys.stream()
                    .anyMatch(key -> !BU_DIRECTOR_ASSIGNABLE.contains(key.toUpperCase()));
            if (forbidden) {
                return CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN.getCode(),
                        "非管理员只能分配 BU_STAFF 角色");
            }
        }
        sysUserService.assignRoles(id, req.getRoleIds());
        return CommonResult.success(null);
    }

    @GetMapping("/roles")
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    public CommonResult<List<SysUserVO.RoleSimple>> listRoles() {
        List<SysRole> roles = sysUserService.listAllRoles();
        if (!BusinessUnitContext.isAdmin()) {
            roles = roles.stream()
                    .filter(r -> BU_DIRECTOR_ASSIGNABLE.contains(normalizeRoleKey(r.getRoleKey())))
                    .collect(Collectors.toList());
        }
        List<SysUserVO.RoleSimple> result = roles.stream().map(r -> {
            SysUserVO.RoleSimple s = new SysUserVO.RoleSimple();
            s.setRoleId(r.getRoleId());
            s.setRoleName(r.getRoleName());
            s.setRoleKey(r.getRoleKey());
            return s;
        }).collect(Collectors.toList());
        return CommonResult.success(result);
    }

    private SysUserVO toVO(SysUser user) {
        SysUserVO vo = new SysUserVO();
        vo.setUserId(user.getUserId());
        vo.setUserName(user.getUserName());
        vo.setNickName(user.getNickName());
        vo.setPhone(user.getPhone());
        vo.setSex(user.getSex());
        vo.setDeptId(user.getDeptId());
        // 岗位 ID 字符串转列表，方便前端多选回显
        vo.setPostIds(splitPostIds(user.getPostIds()));
        vo.setBusinessUnitType(user.getBusinessUnitType());
        vo.setStatus(user.getStatus());
        vo.setRemark(user.getRemark());
        vo.setCreateTime(user.getCreateTime());
        List<SysRole> roles = sysUserService.findRolesByUserId(user.getUserId());
        vo.setRoles(roles.stream().map(r -> {
            SysUserVO.RoleSimple s = new SysUserVO.RoleSimple();
            s.setRoleId(r.getRoleId());
            s.setRoleName(r.getRoleName());
            s.setRoleKey(r.getRoleKey());
            return s;
        }).collect(Collectors.toList()));
        return vo;
    }

    private boolean canOperateUser(SysUser target) {
        String currentUnit = BusinessUnitContext.getCurrentBusinessUnitType();
        if (!StringUtils.hasText(currentUnit)) {
            return true;
        }
        if (currentUnit.equals(target.getBusinessUnitType())) {
            return true;
        }
        // 兼容历史 admin 账号未绑定业务单元的场景；非 admin 不能操作跨单元或空单元账号。
        return BusinessUnitContext.isAdmin()
                && !StringUtils.hasText(target.getBusinessUnitType())
                && hasAdminRole(target.getUserId());
    }

    private boolean hasAdminRole(Long userId) {
        return Optional.ofNullable(sysUserService.findRolesByUserId(userId))
                .orElse(Collections.emptyList())
                .stream()
                .anyMatch(r -> ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
    }

    private <T> CommonResult<T> forbiddenCrossBusinessUnit() {
        return CommonResult.error(GlobalErrorCodeConstants.FORBIDDEN.getCode(),
                "不能操作其他业务单元的用户");
    }

    private String normalizeRoleKey(String roleKey) {
        return roleKey == null ? "" : roleKey.toUpperCase();
    }

    /** 岗位 ID 列表 → 逗号分隔字符串（null 原样返回，空集合返回空串） */
    private String joinPostIds(List<Long> postIds) {
        if (postIds == null) return null;
        if (postIds.isEmpty()) return "";
        return postIds.stream().filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /** 岗位 ID 字符串 → 列表（空串/null 返回空列表，忽略非法段） */
    private List<Long> splitPostIds(String postIds) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyList();
        List<Long> result = new ArrayList<>();
        for (String s : postIds.split(",")) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            try {
                result.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignore) {
                // 历史脏数据忽略
            }
        }
        return result;
    }
}
