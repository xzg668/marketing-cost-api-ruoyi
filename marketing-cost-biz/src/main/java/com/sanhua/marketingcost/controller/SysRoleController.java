package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysRoleMenuRequest;
import com.sanhua.marketingcost.dto.system.SysRoleRequest;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.service.SysRoleService;
import com.sanhua.marketingcost.vo.MenuTreeSelectVO;
import com.sanhua.marketingcost.vo.SysRoleVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/system/role")
public class SysRoleController {

    private final SysRoleService sysRoleService;

    public SysRoleController(SysRoleService sysRoleService) {
        this.sysRoleService = sysRoleService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public CommonResult<List<SysRoleVO>> list() {
        List<SysRole> roles = sysRoleService.listAll();
        List<SysRoleVO> voList = roles.stream().map(this::toVO).collect(Collectors.toList());
        return CommonResult.success(voList);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:role:query')")
    public CommonResult<SysRoleVO> get(@PathVariable("id") Long id) {
        SysRole role = sysRoleService.getById(id);
        if (role == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "角色不存在");
        }
        return CommonResult.success(toVO(role));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:role:add')")
    // 角色新增：记录审计日志
    @OperationLog(module = "角色管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysRoleRequest req) {
        SysRole role = new SysRole();
        role.setRoleName(req.getRoleName());
        role.setRoleKey(req.getRoleKey());
        role.setRoleSort(req.getRoleSort() != null ? req.getRoleSort() : 0);
        role.setDataScope(req.getDataScope());
        role.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        role.setRemark(req.getRemark());
        sysRoleService.createRole(role);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    // 角色编辑
    @OperationLog(module = "角色管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysRoleRequest req) {
        SysRole existing = sysRoleService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "角色不存在");
        }
        SysRole role = new SysRole();
        role.setRoleId(id);
        role.setRoleName(req.getRoleName());
        role.setRoleKey(req.getRoleKey());
        role.setRoleSort(req.getRoleSort());
        role.setDataScope(req.getDataScope());
        role.setStatus(req.getStatus());
        role.setRemark(req.getRemark());
        sysRoleService.updateRole(role);
        return CommonResult.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:role:remove')")
    // 角色删除
    @OperationLog(module = "角色管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        SysRole existing = sysRoleService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "角色不存在");
        }
        if (sysRoleService.hasUsers(id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "该角色已关联用户，请先取消关联后再删除");
        }
        sysRoleService.deleteRole(id);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}/menus")
    @PreAuthorize("@ss.hasPermi('system:role:edit')")
    // 角色分配菜单权限
    @OperationLog(module = "角色管理-分配权限", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> assignMenus(@PathVariable("id") Long id,
                                          @Valid @RequestBody SysRoleMenuRequest req) {
        SysRole existing = sysRoleService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "角色不存在");
        }
        sysRoleService.assignMenus(id, req.getMenuIds());
        return CommonResult.success(null);
    }

    @GetMapping("/{id}/menus")
    @PreAuthorize("@ss.hasPermi('system:role:query')")
    public CommonResult<List<Long>> getRoleMenuIds(@PathVariable("id") Long id) {
        return CommonResult.success(sysRoleService.getMenuIdsByRoleId(id));
    }

    private SysRoleVO toVO(SysRole role) {
        SysRoleVO vo = new SysRoleVO();
        vo.setRoleId(role.getRoleId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleKey(role.getRoleKey());
        vo.setRoleSort(role.getRoleSort());
        vo.setDataScope(role.getDataScope());
        vo.setStatus(role.getStatus());
        vo.setRemark(role.getRemark());
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }
}
