package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *  服务实现类
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 查询店铺类型
     * @return
     */
    @Override
    public List<ShopType> queryShopType() throws JsonProcessingException {
        //先从缓存查
        String key = "shop:type";
        String shopType = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopType)){
            //将查到的数据放到list中
            return MAPPER.readValue(shopType, new TypeReference<List<ShopType>>() {});
        }
        //未命中查数据库
        List<ShopType> shopTypeList = new ArrayList<>();
        shopTypeList = query().orderByAsc("sort").list();
        //写入Redis
        //转JSON
        String shopTypeJson = MAPPER.writeValueAsString(shopTypeList);
        stringRedisTemplate.opsForValue().set(key,shopTypeJson , 10L, TimeUnit.MINUTES);
        return shopTypeList;
    }
}
