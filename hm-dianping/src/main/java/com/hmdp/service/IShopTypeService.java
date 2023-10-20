package com.hmdp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 *  服务类
 */
public interface IShopTypeService extends IService<ShopType> {
    /**
     * 查询店铺类型
     * @return
     */
    List<ShopType> queryShopType() throws JsonProcessingException;
}
