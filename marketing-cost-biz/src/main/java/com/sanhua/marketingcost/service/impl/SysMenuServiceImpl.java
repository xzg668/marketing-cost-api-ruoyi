package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.mapper.SysMenuMapper;
import com.sanhua.marketingcost.service.SysMenuService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysMenuServiceImpl implements SysMenuService {

    private final SysMenuMapper sysMenuMapper;

    public SysMenuServiceImpl(SysMenuMapper sysMenuMapper) {
        this.sysMenuMapper = sysMenuMapper;
    }

    @Override
    public List<SysMenu> listAll() {
        return sysMenuMapper.selectList(
                Wrappers.lambdaQuery(SysMenu.class)
                        .eq(SysMenu::getStatus, "0")
                        .orderByAsc(SysMenu::getParentId)
                        .orderByAsc(SysMenu::getOrderNum)
        );
    }

    @Override
    public SysMenu getById(Long menuId) {
        return sysMenuMapper.selectById(menuId);
    }

    @Override
    @Transactional
    public void createMenu(SysMenu menu) {
        sysMenuMapper.insert(menu);
    }

    @Override
    @Transactional
    public void updateMenu(SysMenu menu) {
        sysMenuMapper.updateById(menu);
    }

    @Override
    @Transactional
    public void deleteMenu(Long menuId) {
        sysMenuMapper.deleteById(menuId);
    }

    @Override
    public boolean hasChildren(Long menuId) {
        Long count = sysMenuMapper.selectCount(
                Wrappers.lambdaQuery(SysMenu.class).eq(SysMenu::getParentId, menuId)
        );
        return count != null && count > 0;
    }
}
