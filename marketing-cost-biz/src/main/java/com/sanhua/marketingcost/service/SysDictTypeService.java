package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysDictType;

import java.util.List;

/**
 * 字典类型管理 Service 接口
 */
public interface SysDictTypeService {

    /**
     * 分页查询字典类型
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param dictName 字典名称（模糊）
     * @param dictType 字典类型（模糊）
     * @param status   状态
     * @return 分页结果
     */
    IPage<SysDictType> listPage(int pageNum, int pageSize, String dictName, String dictType, String status);

    /**
     * 查询所有字典类型
     *
     * @return 字典类型列表
     */
    List<SysDictType> listAll();

    /**
     * 根据ID查询字典类型
     *
     * @param dictId 字典ID
     * @return 字典类型
     */
    SysDictType getById(Long dictId);

    /**
     * 新增字典类型
     *
     * @param dictType 字典类型
     */
    void create(SysDictType dictType);

    /**
     * 修改字典类型
     *
     * @param dictType 字典类型
     */
    void update(SysDictType dictType);

    /**
     * 删除字典类型（同时删除该类型下所有字典数据）
     *
     * @param dictId 字典ID
     */
    void delete(Long dictId);

    /**
     * 校验字典类型编码是否唯一
     *
     * @param dictType  字典类型编码
     * @param excludeId 排除的字典ID
     * @return true=唯一
     */
    boolean isDictTypeUnique(String dictType, Long excludeId);
}
