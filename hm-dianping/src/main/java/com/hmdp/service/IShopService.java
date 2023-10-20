package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.Serializable;

/**
 *  服务类
 */
public interface IShopService extends IService<Shop> {
    /**
     * 根据id查数据
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新店铺数据
     * @param shop
     * @return
     */
    Result updateShop(Shop shop);

}
