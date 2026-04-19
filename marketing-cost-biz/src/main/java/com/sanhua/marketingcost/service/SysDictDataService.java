package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysDictData;

import java.util.List;

/**
 * 字典数据管理 Service 接口
 */
public interface SysDictDataService {

    /**
     * 分页查询指定字典类型的数据
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param dictType  字典类型（精确匹配）
     * @param dictLabel 字典标签（模糊）
     * @param status    状态
     * @return 分页结果
     */
    IPage<SysDictData> listPage(int pageNum, int pageSize, String dictType, String dictLabel, String status);

    /**
     * 根据ID查询字典数据
     *
     * @param dictCode 字典编码（主键）
     * @return 字典数据
     */
    SysDictData getById(Long dictCode);

    /**
     * 新增字典数据
     *
     * @param dictData 字典数据
     */
    void create(SysDictData dictData);

    /**
     * 修改字典数据
     *
     * @param dictData 字典数据
     */
    void update(SysDictData dictData);

    /**
     * 删除字典数据
     *
     * @param dictCode 字典编码
     */
    void delete(Long dictCode);
}
