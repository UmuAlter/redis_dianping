package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 优惠卷下单
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    Result createVoucherOrder(Long voucherId);
}
