package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.util.SysRoleDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SysUserServiceImpl implements SysUserService {

    private static final String ADMIN_ROLE_KEY = "ADMIN";
    private static final String ALL_PERMISSION = "*:*:*";

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysMenuMapper sysMenuMapper;

    public SysUserServiceImpl(SysUserMapper sysUserMapper,
                              SysRoleMapper sysRoleMapper,
                              SysUserRoleMapper sysUserRoleMapper,
                              SysRoleMenuMapper sysRoleMenuMapper,
                              SysMenuMapper sysMenuMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.sysMenuMapper = sysMenuMapper;
    }

    @Override
    public SysUser findByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    @Override
    public List<SysRole> findRolesByUserId(Long userId) {
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getUserId, userId)
        );
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toList());
        List<SysRole> roles = sysRoleMapper.selectList(
                Wrappers.lambdaQuery(SysRole.class)
                        .in(SysRole::getRoleId, roleIds)
                        .eq(SysRole::getStatus, "0")
                        .eq(SysRole::getDelFlag, "0")
        );
        roles.forEach(SysRoleDefaults::normalize);
        return roles;
    }

    @Override
    public Set<String> findPermissionsByUserId(Long userId) {
        // 1. 查询用户角色列表
        List<SysRole> roles = findRolesByUserId(userId);
        if (roles.isEmpty()) {
            return Collections.emptySet();
        }
        // 2. admin 角色直接返回通配权限
        boolean isAdmin = roles.stream()
                .anyMatch(r -> ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
        if (isAdmin) {
            return Set.of(ALL_PERMISSION);
        }
        // 3. 查询角色关联的菜单ID
        List<Long> roleIds = roles.stream().map(SysRole::getRoleId).collect(Collectors.toList());
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                Wrappers.lambdaQuery(SysRoleMenu.class)
                        .in(SysRoleMenu::getRoleId, roleIds)
        );
        if (roleMenus.isEmpty()) {
            return Collections.emptySet();
        }
        // 4. 查询菜单的权限标识（perms 字段）
        List<Long> menuIds = roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .collect(Collectors.toList());
        List<SysMenu> menus = sysMenuMapper.selectList(
                Wrappers.lambdaQuery(SysMenu.class)
                        .in(SysMenu::getMenuId, menuIds)
                        .eq(SysMenu::getStatus, "0")
        );
        // 5. 收集非空权限标识并去重
        return menus.stream()
                .map(SysMenu::getPerms)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    @Override
    public List<SysMenu> findVisibleMenus(Long userId, String businessUnitType) {
        // 1. 查询用户角色
        List<SysRole> roles = findRolesByUserId(userId);
        if (roles.isEmpty()) {
            return Collections.emptyList();
        }
        boolean isAdmin = roles.stream()
                .anyMatch(r -> ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
        // 2. 构建基础查询：menu_type M/C（目录/菜单），不含按钮 F；状态正常 + 可见
        var wrapper = Wrappers.lambdaQuery(SysMenu.class)
                .in(SysMenu::getMenuType, "M", "C")
                .eq(SysMenu::getStatus, "0")
                .eq(SysMenu::getVisible, "0")
                // business_unit_type 为 NULL（通用）或等于用户当前单元
                .and(w -> {
                    w.isNull(SysMenu::getBusinessUnitType);
                    if (businessUnitType != null && !businessUnitType.isEmpty()) {
                        w.or().eq(SysMenu::getBusinessUnitType, businessUnitType);
                    }
                })
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getOrderNum);
        // 3. admin 全部放行，非 admin 按 role_menu 过滤
        if (!isAdmin) {
            List<Long> roleIds = roles.stream().map(SysRole::getRoleId).collect(Collectors.toList());
            List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                    Wrappers.lambdaQuery(SysRoleMenu.class)
                            .in(SysRoleMenu::getRoleId, roleIds)
            );
            if (roleMenus.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> menuIds = roleMenus.stream()
                    .map(SysRoleMenu::getMenuId)
                    .distinct()
                    .collect(Collectors.toList());
            wrapper.in(SysMenu::getMenuId, menuIds);
        }
        return sysMenuMapper.selectList(wrapper);
    }

    @Override
    public Page<SysUser> listUsers(String userName, String phone, String status,
                                   String businessUnitType, int page, int size) {
        var wrapper = Wrappers.lambdaQuery(SysUser.class)
                .eq(SysUser::getDelFlag, "0")
                .like(StringUtils.hasText(userName), SysUser::getUserName, userName)
                .like(StringUtils.hasText(phone), SysUser::getPhone, phone)
                .eq(StringUtils.hasText(status), SysUser::getStatus, status)
                .eq(StringUtils.hasText(businessUnitType), SysUser::getBusinessUnitType, businessUnitType)
                .orderByDesc(SysUser::getCreateTime);
        return sysUserMapper.selectUserPage(new Page<>(page, size), wrapper);
    }

    @Override
    public SysUser getById(Long userId) {
        return sysUserMapper.selectOne(
                Wrappers.lambdaQuery(SysUser.class)
                        .eq(SysUser::getUserId, userId)
                        .eq(SysUser::getDelFlag, "0")
        );
    }

    @Override
    @Transactional
    public void createUser(SysUser user) {
        user.setDelFlag("0");
        sysUserMapper.insert(user);
    }

    @Override
    @Transactional
    public void updateUser(SysUser user) {
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setDelFlag("1");
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void resetPassword(Long userId, String encodedPassword) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setPassword(encodedPassword);
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        sysUserRoleMapper.delete(
                Wrappers.lambdaQuery(SysUserRole.class).eq(SysUserRole::getUserId, userId)
        );
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                sysUserRoleMapper.insert(ur);
            }
        }
    }

    @Override
    public List<SysRole> listAllRoles() {
        List<SysRole> roles = sysRoleMapper.selectList(
                Wrappers.lambdaQuery(SysRole.class)
                        .eq(SysRole::getStatus, "0")
                        .eq(SysRole::getDelFlag, "0")
                        .orderByAsc(SysRole::getRoleSort)
        );
        roles.forEach(SysRoleDefaults::normalize);
        return roles;
    }

    @Override
    public boolean isLastAdmin(Long userId) {
        List<SysRole> roles = findRolesByUserId(userId);
        boolean isAdmin = roles.stream()
                .anyMatch(r -> ADMIN_ROLE_KEY.equalsIgnoreCase(r.getRoleKey()));
        if (!isAdmin) return false;

        SysRole adminRole = sysRoleMapper.selectOne(
                Wrappers.lambdaQuery(SysRole.class)
                        .eq(SysRole::getRoleKey, ADMIN_ROLE_KEY)
                        .eq(SysRole::getStatus, "0")
                        .eq(SysRole::getDelFlag, "0")
        );
        if (adminRole == null) return false;
        SysRoleDefaults.normalize(adminRole);

        List<SysUserRole> adminUserRoles = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getRoleId, adminRole.getRoleId())
        );
        long activeAdmins = adminUserRoles.stream()
                .filter(ur -> {
                    SysUser u = sysUserMapper.selectById(ur.getUserId());
                    return u != null && "0".equals(u.getDelFlag()) && "0".equals(u.getStatus());
                })
                .count();
        return activeAdmins <= 1;
    }
}
