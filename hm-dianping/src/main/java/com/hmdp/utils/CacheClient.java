package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 向Redis中存入普通数据
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(
                key, JSONUtil.toJsonStr(value), time, unit
        );
    }

    /**
     * 向Redis中存入热点数据
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID>R queryWithPassThrough(String preFix,
                                         ID id,
                                         Class<R> type,
                                         Function<ID, R> dbFallback,
                                         Long time,
                                         TimeUnit unit
    ){
        String key = preFix+id;
        //从缓存查
        String json = stringRedisTemplate.opsForValue()
                .get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(json)){
            //查到了，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中空值
        if(json != null){
            return null;
        }
        //去数据库查
        R r = dbFallback.apply(id);
        if(r == null){
            //将空值写入Redis 避免内存穿透
            stringRedisTemplate.opsForValue()
                    .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写入Redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 创建一个锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean absent = stringRedisTemplate
                .opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(absent);
    }

    /**
     * 删除锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 解决逻辑过期
     * @param id
     * @return
     */
    public <R, ID>R queryWithLogicalExpire(
            String preFix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ){
        String key = preFix + id;
        //从缓存查
        String json = stringRedisTemplate.opsForValue()
                .get(key);
        //判断是否存在
        if(StrUtil.isBlank(json)){
            return null;
        }
        //判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //没过期
            return r;
        }
        //过期，缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取锁成功，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查
                    R r1 = dbFallback.apply(id);
                    //写
                    this.setWithLogicalExpire(key, r1, time, unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
    }
}
