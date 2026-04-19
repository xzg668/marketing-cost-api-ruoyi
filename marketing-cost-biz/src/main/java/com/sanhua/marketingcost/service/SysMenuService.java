package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.SysMenu;

import java.util.List;

public interface SysMenuService {

    List<SysMenu> listAll();

    SysMenu getById(Long menuId);

    void createMenu(SysMenu menu);

    void updateMenu(SysMenu menu);

    void deleteMenu(Long menuId);

    boolean hasChildren(Long menuId);
}
