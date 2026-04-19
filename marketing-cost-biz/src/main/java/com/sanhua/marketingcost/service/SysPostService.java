package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysPost;

import java.util.List;

/**
 * 岗位管理 Service 接口
 */
public interface SysPostService {

    /**
     * 分页查询岗位列表
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param postCode 岗位编码（可选，模糊查询）
     * @param postName 岗位名称（可选，模糊查询）
     * @param status   状态（可选，精确匹配）
     * @return 分页结果
     */
    IPage<SysPost> listPosts(int pageNum, int pageSize, String postCode, String postName, String status);

    /**
     * 查询所有岗位（用于下拉选择）
     *
     * @return 岗位列表
     */
    List<SysPost> listAll();

    /**
     * 根据ID查询岗位
     *
     * @param postId 岗位ID
     * @return 岗位信息
     */
    SysPost getById(Long postId);

    /**
     * 新增岗位
     *
     * @param post 岗位信息
     */
    void createPost(SysPost post);

    /**
     * 修改岗位
     *
     * @param post 岗位信息
     */
    void updatePost(SysPost post);

    /**
     * 删除岗位
     *
     * @param postId 岗位ID
     */
    void deletePost(Long postId);

    /**
     * 校验岗位编码是否唯一
     *
     * @param postCode  岗位编码
     * @param excludeId 排除的岗位ID（编辑时排除自身）
     * @return true=唯一，false=已存在
     */
    boolean isPostCodeUnique(String postCode, Long excludeId);
}
