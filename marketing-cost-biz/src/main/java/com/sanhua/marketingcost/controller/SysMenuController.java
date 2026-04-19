package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysMenuRequest;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.service.SysMenuService;
import com.sanhua.marketingcost.vo.MenuTreeSelectVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/system/menu")
public class SysMenuController {

    private final SysMenuService sysMenuService;

    public SysMenuController(SysMenuService sysMenuService) {
        this.sysMenuService = sysMenuService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:menu:list')")
    public CommonResult<List<SysMenu>> list() {
        return CommonResult.success(sysMenuService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:menu:query')")
    public CommonResult<SysMenu> get(@PathVariable("id") Long id) {
        SysMenu menu = sysMenuService.getById(id);
        if (menu == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "菜单不存在");
        }
        return CommonResult.success(menu);
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:menu:add')")
    // 菜单新增
    @OperationLog(module = "菜单管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysMenuRequest req) {
        SysMenu menu = toEntity(req);
        sysMenuService.createMenu(menu);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:menu:edit')")
    // 菜单编辑
    @OperationLog(module = "菜单管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysMenuRequest req) {
        SysMenu existing = sysMenuService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "菜单不存在");
        }
        SysMenu menu = toEntity(req);
        menu.setMenuId(id);
        sysMenuService.updateMenu(menu);
        return CommonResult.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:menu:remove')")
    // 菜单删除
    @OperationLog(module = "菜单管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        if (sysMenuService.hasChildren(id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "存在子菜单，不允许删除");
        }
        sysMenuService.deleteMenu(id);
        return CommonResult.success(null);
    }

    @GetMapping("/tree-select")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public CommonResult<List<MenuTreeSelectVO>> treeSelect() {
        List<SysMenu> menus = sysMenuService.listAll();
        return CommonResult.success(buildTree(menus));
    }

    private SysMenu toEntity(SysMenuRequest req) {
        SysMenu menu = new SysMenu();
        menu.setMenuName(req.getMenuName());
        menu.setParentId(req.getParentId() != null ? req.getParentId() : 0L);
        menu.setOrderNum(req.getOrderNum() != null ? req.getOrderNum() : 0);
        menu.setPath(req.getPath());
        menu.setComponent(req.getComponent());
        menu.setMenuType(req.getMenuType());
        menu.setVisible(req.getVisible() != null ? req.getVisible() : "0");
        menu.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        menu.setPerms(req.getPerms());
        menu.setIcon(req.getIcon());
        menu.setBusinessUnitType(req.getBusinessUnitType());
        menu.setRemark(req.getRemark());
        return menu;
    }

    private List<MenuTreeSelectVO> buildTree(List<SysMenu> menus) {
        Map<Long, MenuTreeSelectVO> map = new LinkedHashMap<>();
        for (SysMenu m : menus) {
            MenuTreeSelectVO vo = new MenuTreeSelectVO();
            vo.setId(m.getMenuId());
            vo.setLabel(m.getMenuName());
            map.put(m.getMenuId(), vo);
        }
        List<MenuTreeSelectVO> roots = new ArrayList<>();
        for (SysMenu m : menus) {
            MenuTreeSelectVO node = map.get(m.getMenuId());
            Long pid = m.getParentId();
            if (pid == null || pid == 0L || !map.containsKey(pid)) {
                roots.add(node);
            } else {
                MenuTreeSelectVO parent = map.get(pid);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        return roots;
    }
}
