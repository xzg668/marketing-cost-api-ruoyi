package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;

import java.util.List;
import java.util.Set;

public interface SysUserService {
    SysUser findByUsername(String username);

    List<SysRole> findRolesByUserId(Long userId);

    Set<String> findPermissionsByUserId(Long userId);

    List<SysMenu> findVisibleMenus(Long userId, String businessUnitType);

    Page<SysUser> listUsers(String userName, String phone, String status,
                            String businessUnitType, int page, int size);

    SysUser getById(Long userId);

    void createUser(SysUser user);

    void updateUser(SysUser user);

    void deleteUser(Long userId);

    void resetPassword(Long userId, String encodedPassword);

    void assignRoles(Long userId, List<Long> roleIds);

    List<SysRole> listAllRoles();

    boolean isLastAdmin(Long userId);
}
