package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 *  服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //自定义工具类
    @Autowired
    private CacheClient client;
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查数据
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop1 = queryWithPassThrough(id);
        //通过工具类解决缓存穿透
        Shop shop1 = client.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
        );

        //互斥锁解决缓存击穿
//        Shop s = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = client.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY,
                id, Shop.class, this::getById,
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    /**
     * 根据id查数据,解决内存穿透问题
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //从缓存查
        String shopJson = stringRedisTemplate.opsForValue()
                .get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //查到了，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中空值
        if(shopJson != null){
            return null;
        }
        //去数据库查
        Shop shop = getById(id);
        if(shop == null){
            //将空值写入Redis 避免内存穿透
            stringRedisTemplate.opsForValue()
                    .set(RedisConstants.CACHE_SHOP_KEY, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写入Redis
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.CACHE_SHOP_KEY, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
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
     * 存储热点店铺信息到Redis
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds){
        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue()
                .set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 用互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        //从缓存查
        String shopJson = stringRedisTemplate.opsForValue()
                .get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //查到了，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中空值
        if(shopJson != null){
            return null;
        }
        //实现缓存重建
        Shop shop = null;
        try {
            boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            if(!lock){
                //获取失败 休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取成功
            //去数据库查
            shop = getById(id);
            if(shop == null){
                //将空值写入Redis 避免内存穿透
                stringRedisTemplate.opsForValue()
                        .set(RedisConstants.CACHE_SHOP_KEY, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //写入Redis
            stringRedisTemplate.opsForValue()
                    .set(RedisConstants.CACHE_SHOP_KEY, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        //从缓存查
        String shopJson = stringRedisTemplate.opsForValue()
                .get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //为空直接返回
            return null;
        }
        //判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //没过期
            return shop;
        }
        //过期，缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //获取锁成功，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }

    /**
     * 更新店铺数据
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    /**
     * 根据店铺类型分页查询店铺信息
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopById(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null) {
            // 根据类型分页查询
            //自定义SystemConstants.DEFAULT_PAGE_SIZE = 5
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //根据位置查询
        //计算分页参数
        int from = (current -1 ) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //从Redis中查询店铺id
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),  //查询半径5公里距离内
                // 附带距离，并指定从第一个数据开始查询到第end 0 ~ end
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        //解析id并截取位置
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        //跳过from以前的
        list.stream().skip(from).forEach(result->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //根据id获取shop
        String strIds = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + strIds + ")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
