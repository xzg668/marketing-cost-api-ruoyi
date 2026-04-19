package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.entity.system.SysDictType;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.mapper.SysDictTypeMapper;
import com.sanhua.marketingcost.service.SysDictTypeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 字典类型管理 Service 实现类
 */
@Service
public class SysDictTypeServiceImpl implements SysDictTypeService {

    private final SysDictTypeMapper sysDictTypeMapper;
    private final SysDictDataMapper sysDictDataMapper;

    public SysDictTypeServiceImpl(SysDictTypeMapper sysDictTypeMapper, SysDictDataMapper sysDictDataMapper) {
        this.sysDictTypeMapper = sysDictTypeMapper;
        this.sysDictDataMapper = sysDictDataMapper;
    }

    @Override
    public IPage<SysDictType> listPage(int pageNum, int pageSize, String dictName, String dictType, String status) {
        // 构建分页查询条件
        LambdaQueryWrapper<SysDictType> wrapper = Wrappers.lambdaQuery(SysDictType.class);
        if (StringUtils.hasText(dictName)) {
            wrapper.like(SysDictType::getDictName, dictName);
        }
        if (StringUtils.hasText(dictType)) {
            wrapper.like(SysDictType::getDictType, dictType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysDictType::getStatus, status);
        }
        wrapper.orderByAsc(SysDictType::getDictId);
        return sysDictTypeMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public List<SysDictType> listAll() {
        // 查询所有正常状态的字典类型
        return sysDictTypeMapper.selectList(
                Wrappers.lambdaQuery(SysDictType.class)
                        .eq(SysDictType::getStatus, "0")
                        .orderByAsc(SysDictType::getDictId)
        );
    }

    @Override
    public SysDictType getById(Long dictId) {
        return sysDictTypeMapper.selectById(dictId);
    }

    @Override
    @Transactional
    public void create(SysDictType dictType) {
        sysDictTypeMapper.insert(dictType);
    }

    @Override
    @Transactional
    public void update(SysDictType dictType) {
        sysDictTypeMapper.updateById(dictType);
    }

    @Override
    @Transactional
    public void delete(Long dictId) {
        // 先查出字典类型编码，再删除该类型下所有字典数据
        SysDictType dictType = sysDictTypeMapper.selectById(dictId);
        if (dictType != null) {
            sysDictDataMapper.delete(
                    Wrappers.lambdaQuery(SysDictData.class)
                            .eq(SysDictData::getDictType, dictType.getDictType())
            );
        }
        // 删除字典类型
        sysDictTypeMapper.deleteById(dictId);
    }

    @Override
    public boolean isDictTypeUnique(String dictType, Long excludeId) {
        // 校验字典类型编码唯一性
        LambdaQueryWrapper<SysDictType> wrapper = Wrappers.lambdaQuery(SysDictType.class)
                .eq(SysDictType::getDictType, dictType);
        if (excludeId != null) {
            wrapper.ne(SysDictType::getDictId, excludeId);
        }
        Long count = sysDictTypeMapper.selectCount(wrapper);
        return count == null || count == 0;
    }
}
