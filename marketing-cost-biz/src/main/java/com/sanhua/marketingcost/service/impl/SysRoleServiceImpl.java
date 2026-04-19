package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysRoleMenu;
import com.sanhua.marketingcost.entity.SysUserRole;
import com.sanhua.marketingcost.mapper.SysMenuMapper;
import com.sanhua.marketingcost.mapper.SysRoleMapper;
import com.sanhua.marketingcost.mapper.SysRoleMenuMapper;
import com.sanhua.marketingcost.mapper.SysUserRoleMapper;
import com.sanhua.marketingcost.service.SysRoleService;
import com.sanhua.marketingcost.util.SysRoleDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysRoleServiceImpl implements SysRoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysMenuMapper sysMenuMapper;

    public SysRoleServiceImpl(SysRoleMapper sysRoleMapper,
                              SysRoleMenuMapper sysRoleMenuMapper,
                              SysUserRoleMapper sysUserRoleMapper,
                              SysMenuMapper sysMenuMapper) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysMenuMapper = sysMenuMapper;
    }

    @Override
    public List<SysRole> listAll() {
        List<SysRole> roles = sysRoleMapper.selectList(
                Wrappers.lambdaQuery(SysRole.class)
                        .eq(SysRole::getDelFlag, "0")
                        .orderByAsc(SysRole::getRoleSort)
        );
        roles.forEach(SysRoleDefaults::normalize);
        return roles;
    }

    @Override
    public SysRole getById(Long roleId) {
        SysRole role = sysRoleMapper.selectOne(
                Wrappers.lambdaQuery(SysRole.class)
                        .eq(SysRole::getRoleId, roleId)
                        .eq(SysRole::getDelFlag, "0")
        );
        return SysRoleDefaults.normalize(role);
    }

    @Override
    @Transactional
    public void createRole(SysRole role) {
        role.setDelFlag("0");
        sysRoleMapper.insert(role);
    }

    @Override
    @Transactional
    public void updateRole(SysRole role) {
        sysRoleMapper.updateById(role);
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setDelFlag("1");
        sysRoleMapper.updateById(role);
        sysRoleMenuMapper.delete(
                Wrappers.lambdaQuery(SysRoleMenu.class).eq(SysRoleMenu::getRoleId, roleId)
        );
    }

    @Override
    public boolean hasUsers(Long roleId) {
        Long count = sysUserRoleMapper.selectCount(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getRoleId, roleId)
        );
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void assignMenus(Long roleId, List<Long> menuIds) {
        sysRoleMenuMapper.delete(
                Wrappers.lambdaQuery(SysRoleMenu.class).eq(SysRoleMenu::getRoleId, roleId)
        );
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }
        }
    }

    @Override
    public List<Long> getMenuIdsByRoleId(Long roleId) {
        List<SysRoleMenu> list = sysRoleMenuMapper.selectList(
                Wrappers.lambdaQuery(SysRoleMenu.class).eq(SysRoleMenu::getRoleId, roleId)
        );
        return list.stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
    }

    @Override
    public List<SysMenu> listAllMenus() {
        return sysMenuMapper.selectList(
                Wrappers.lambdaQuery(SysMenu.class)
                        .eq(SysMenu::getStatus, "0")
                        .orderByAsc(SysMenu::getParentId)
                        .orderByAsc(SysMenu::getOrderNum)
        );
    }
}
