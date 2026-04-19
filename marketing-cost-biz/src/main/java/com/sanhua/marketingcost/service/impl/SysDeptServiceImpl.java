package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.system.SysDept;
import com.sanhua.marketingcost.mapper.SysDeptMapper;
import com.sanhua.marketingcost.service.SysDeptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysDeptServiceImpl implements SysDeptService {

    private final SysDeptMapper sysDeptMapper;

    public SysDeptServiceImpl(SysDeptMapper sysDeptMapper) {
        this.sysDeptMapper = sysDeptMapper;
    }

    @Override
    public List<SysDept> listAll() {
        return sysDeptMapper.selectList(
                Wrappers.lambdaQuery(SysDept.class)
                        .orderByAsc(SysDept::getParentId)
                        .orderByAsc(SysDept::getOrderNum)
        );
    }

    @Override
    public SysDept getById(Long deptId) {
        return sysDeptMapper.selectById(deptId);
    }

    @Override
    @Transactional
    public void createDept(SysDept dept) {
        buildAncestors(dept);
        sysDeptMapper.insert(dept);
    }

    @Override
    @Transactional
    public void updateDept(SysDept dept) {
        buildAncestors(dept);
        sysDeptMapper.updateById(dept);
    }

    @Override
    @Transactional
    public void deleteDept(Long deptId) {
        sysDeptMapper.deleteById(deptId);
    }

    @Override
    public boolean hasChildren(Long deptId) {
        Long count = sysDeptMapper.selectCount(
                Wrappers.lambdaQuery(SysDept.class).eq(SysDept::getParentId, deptId)
        );
        return count != null && count > 0;
    }

    private void buildAncestors(SysDept dept) {
        Long parentId = dept.getParentId();
        if (parentId == null || parentId == 0L) {
            dept.setAncestors("0");
            dept.setParentId(0L);
        } else {
            SysDept parent = sysDeptMapper.selectById(parentId);
            if (parent != null) {
                dept.setAncestors(parent.getAncestors() + "," + parentId);
            } else {
                dept.setAncestors("0");
            }
        }
    }
}
