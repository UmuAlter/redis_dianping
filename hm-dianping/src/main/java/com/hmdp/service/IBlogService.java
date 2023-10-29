package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result like(Long id);

    /**
     * 查看点赞列表
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
}
