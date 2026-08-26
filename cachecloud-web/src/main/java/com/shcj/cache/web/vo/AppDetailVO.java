package com.shcj.cache.web.vo;

import java.util.ArrayList;
import java.util.List;

import com.shcj.cache.entity.InstanceInfo;
import lombok.Data;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;

import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;

/**
 * 集群详情
 *
 * @author leifu
 * @Time 2014年8月29日
 */
@Data
public class AppDetailVO {

    private AppDesc appDesc;

    private AppUser officer;

    /**
     * 内存空间
     */
    private long mem;

    /**
     * 当前内存
     */
    private long currentMem;

    /**
     * 机器数
     */
    private int machineNum;

    /**
     * 主节点数
     */
    private int masterNum;

    /**
     * 从节点数
     */
    private int slaveNum;

    /**
     * 哨兵节点数
     */
    private int sentinelNum;

    /**
     * 当前对象数
     */
    private long currentObjNum;

    /**
     * 当前连接数
     */
    private int conn;

    /**
     * 内存使用报警
     */
    private double memUseThreshold;

    /**
     * 命中率使用报警
     */
    private double hitPercentThreshold;

    /**
     * 内存使用率
     */
    private double memUsePercent;

    /**
     * 最大碎片率
     */
    private double highestMemFragRatio;

    /**
     * 最大碎片率的实例id
     */
    private long instIdWithHighestMemFragRatio;

    /**
     * 命中率
     */
    private double hitPercent;

    /** 主节点 key 总数，包含 Redis INFO 返回的全部 DB。 */
    private long currentKeyCount;

    /** 最近两次采集计算出的 CPU 使用率（百分比）。 */
    private double cpuUsePercent;

    /** 实例运行时长，单位秒。 */
    private long uptimeSeconds;

    /**
     * 应用授权的用户
     */
    private List<AppUser> appUsers;

    /**
     * 应用报警的用户
     */
    private List<AppUser> alertUsers;

    /**
     * 非在线状态的节点列表
     */
    private List<InstanceInfo> offLineInstances;

    public void addOffLineInstance(InstanceInfo instanceInfo) {
        if (offLineInstances == null) {
            offLineInstances = new ArrayList<>();
        }
        offLineInstances.add(instanceInfo);
    }

    public List<String> getPhoneList() {
        List<String> phoneList = new ArrayList<String>();
        if (CollectionUtils.isNotEmpty(appUsers)) {
            for (AppUser appUser : appUsers) {
                String mobile = appUser.getMobile();
                if (StringUtils.isNotBlank(mobile)) {
                    phoneList.add(appUser.getMobile());
                }
            }
        }
        return phoneList;
    }

    public List<String> getWeChatList() {
        List<String> weChatList = new ArrayList<String>();
        if (CollectionUtils.isNotEmpty(alertUsers)) {
            for (AppUser appUser : alertUsers) {
                String weChat = appUser.getWeChat();
                if (StringUtils.isNotBlank(weChat)) {
                    weChatList.add(appUser.getWeChat());
                }
            }
        }
        return weChatList;
    }

    public List<String> getEmailList() {
        List<String> emailList = new ArrayList<String>();
        if (CollectionUtils.isNotEmpty(alertUsers)) {
            for (AppUser appUser : alertUsers) {
                String email = appUser.getEmail();
                if (StringUtils.isNotBlank(email)) {
                    emailList.add(appUser.getEmail());
                }
            }
        }
        return emailList;
    }
}
