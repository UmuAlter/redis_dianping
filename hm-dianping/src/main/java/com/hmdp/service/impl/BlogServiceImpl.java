package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户,以及是否被点赞
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //给博客添加作者信息
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 根据博客id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //根据id查
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        //添加作者信息
        queryBlogUser(blog);
        //查询bLog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //查询bLog是否被点赞了
    private void isBlogLiked(Blog blog) {
        if(UserHolder.getUser() == null){
            //用户未登录，无需查看是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double isSuccess = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(isSuccess != null);
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result like(Long id) {
        //判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double isLiked = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(isLiked == null) {
            //没点赞为点赞操作 数据库点赞+1 Redis添加当前用户
            boolean isSuccess = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //点赞过为取消点赞
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看点赞列表
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //查前五条
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> tops = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        String strIds = StrUtil.join(",", tops);
        //解决了排序的问题
        List<UserDTO> userDTOS = userService.query().in("id", tops)
                .last("ORDER BY FIELD(id,"+ strIds + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 提交点评
     * 并且将刚发布的消息推送给粉丝们
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if(!save) {
            return Result.ok("新增笔记失败");
        }
        //查询粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            //粉丝id
            Long userId = follow.getUserId();
            //推送
            //key : 收件者
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //  解析数据 获取消息的id和分数
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //存放id的集合
        List<Long> ids = new ArrayList<>(typedTuples.size());
        //最后的分数相同的元素的个数
        int count = 1;
        //记录最后一个元素的分数
        long minTime = 0;
        for(ZSetOperations.TypedTuple<String> tuple: typedTuples){
            //获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数
            long time = tuple.getScore().longValue();
            if(time == minTime){
                count++;
            }else{
                count = 1;
                minTime = time;
            }
        }
        //查询要展示的blog
        String strIds = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + strIds + ")").list();
        //对blogs中的元素的补充
        for (Blog blog : blogs) {
            //添加作者信息
            queryBlogUser(blog);
            //查询bLog是否被点赞了
            isBlogLiked(blog);
        }

        //封装结果
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(count);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }
}
