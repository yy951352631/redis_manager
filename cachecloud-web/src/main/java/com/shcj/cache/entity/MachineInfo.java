package com.shcj.cache.entity;


import com.shcj.cache.constant.MachineInfoEnum;
import lombok.Data;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 机器的属性信息
 * <p>
 */
@Data
public class MachineInfo {
    /**
     * 机器id
     */
    private long id;

    /**
     * ssh用户名
     */
    private String sshUser;

    /**
     * ssh密码
     */
    private String sshPasswd;

    /**
     * ip地址
     */
    private String ip;

    /**
     * 机房
     */
    private String room;

    /**
     * 内存，单位G
     */
    private int mem;

    /**
     * cpu数量
     */
    private int cpu;

    /**
     * 磁盘空间, 单位G
     */
    private int disk;

    /**
     * 是否虚机，0否，1是
     */
    private int virtual;

    /**
     * 宿主机ip
     */
    private String realIp;

    /**
     * 上线时间
     */
    private Date serviceTime;

    /**
     * 故障次数
     */
    private int faultCount;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 是否启用报警，0否，1是
     */
    private int warn;

    /**
     * 是否可用，MachineInfoEnum.AvailableEnum
     */
    private int available;

    /**
     * 机器类型：详见MachineInfoEnum.TypeEnum
     */
    private int type;


    /**
     * 机器类型：详见MachineInfoEnum.DisTypeEnum
     */
    private int disType;


    /**
     * groupId
     */
    private int groupId;

    /**
     * 额外说明:(例如本机器有其他web或者其他服务)
     */
    private String extraDesc;
    private String projectName;

    /**
     * 是否收集服务器信息，0否，1是
     */
    private int collect;

    /**
     * redis版本安装情况 版本号#安装标识  -1:获取安装信息异常 0:未安装 1:安装成功
     */
    private String versionInstall;

    /**
     * 使用类型：Redis专用机器（0），Redis测试机器（1），混合部署机器（2）
     */
    private int useType;
    private int isAllocating;

    /**
     * 是否k8s容器：0:不是 1:是
     */
    private int k8sType;
    private long podUpdateTime;
    private String rack;

    /**
     * 判断机器是否已经下线
     *
     * @return
     */
    public boolean isOffline() {
        return MachineInfoEnum.AvailableEnum.NO.getValue() == this.available;
    }

    /**
     * 是否是云主机
     *
     * @return
     */
    public boolean isYunMachine() {
        //return isYun == 1;
        return false;
    }

    public boolean isK8sMachine(int k8sType) {
        if (k8sType == 1) {
            return true;
        }
        return false;
    }

    /**
     * 时间格式化
     */
    public String getUpdateTimeFormat() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(modifyTime);
    }

    public Date getModifyTime() {
        if(modifyTime == null){
            return null;
        }
        return (Date) modifyTime.clone();
    }

    public void setModifyTime(Date modifyTime) {
        if(modifyTime != null){
            this.modifyTime = (Date) modifyTime.clone();
        }
    }

    public Date getServiceTime() {
        if(serviceTime == null){
            return null;
        }
        return (Date) serviceTime.clone();
    }

    public void setServiceTime(Date serviceTime) {
        if(serviceTime != null){
            this.serviceTime = (Date) serviceTime.clone();
        }
    }
}