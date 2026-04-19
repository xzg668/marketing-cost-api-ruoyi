package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.service.SysDictDataService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 字典数据管理 Service 实现类
 */
@Service
public class SysDictDataServiceImpl implements SysDictDataService {

    private final SysDictDataMapper sysDictDataMapper;

    public SysDictDataServiceImpl(SysDictDataMapper sysDictDataMapper) {
        this.sysDictDataMapper = sysDictDataMapper;
    }

    @Override
    public IPage<SysDictData> listPage(int pageNum, int pageSize, String dictType, String dictLabel, String status) {
        // 构建分页查询条件：dictType 精确匹配，dictLabel 模糊匹配
        LambdaQueryWrapper<SysDictData> wrapper = Wrappers.lambdaQuery(SysDictData.class);
        if (StringUtils.hasText(dictType)) {
            wrapper.eq(SysDictData::getDictType, dictType);
        }
        if (StringUtils.hasText(dictLabel)) {
            wrapper.like(SysDictData::getDictLabel, dictLabel);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysDictData::getStatus, status);
        }
        wrapper.orderByAsc(SysDictData::getDictSort);
        return sysDictDataMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public SysDictData getById(Long dictCode) {
        return sysDictDataMapper.selectById(dictCode);
    }

    @Override
    @Transactional
    public void create(SysDictData dictData) {
        sysDictDataMapper.insert(dictData);
    }

    @Override
    @Transactional
    public void update(SysDictData dictData) {
        sysDictDataMapper.updateById(dictData);
    }

    @Override
    @Transactional
    public void delete(Long dictCode) {
        sysDictDataMapper.deleteById(dictCode);
    }
}
