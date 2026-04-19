package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;

import java.util.List;

public interface SysRoleService {

    List<SysRole> listAll();

    SysRole getById(Long roleId);

    void createRole(SysRole role);

    void updateRole(SysRole role);

    void deleteRole(Long roleId);

    boolean hasUsers(Long roleId);

    void assignMenus(Long roleId, List<Long> menuIds);

    List<Long> getMenuIdsByRoleId(Long roleId);

    List<SysMenu> listAllMenus();
}
