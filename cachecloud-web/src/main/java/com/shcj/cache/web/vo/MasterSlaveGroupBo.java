package com.shcj.cache.web.vo;

import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.web.enums.MasterSlaveExistEnum;
import lombok.Data;

import java.util.List;

/**
 * @Author: zengyizhao
 * @DateTime: 2021/10/20 18:55
 * @Description: 主从分组辅助类
 */
@Data
public class MasterSlaveGroupBo {

    private InstanceInfo master;

    private List<InstanceInfo> slaveList;

    private MasterSlaveExistEnum masterSlaveExistEnum;

}