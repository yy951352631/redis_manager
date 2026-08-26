package com.shcj.cache.alert.service;

import com.shcj.cache.alert.impl.BaseAlertService;
import com.shcj.cache.alert.vo.KafkaAlertVO;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.InstanceFaultDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceFault;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.web.enums.AlertTypeEnum;
import com.shcj.cache.web.service.UserService;
import com.shcj.cache.web.util.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 节点存活状态维护。
 *
 * <p>原先由 {@code InstanceRunInspector}（10 秒一次的 socket PING）负责，现随存活探测一起并入
 * 分钟报警作业：探活结论改为「上一分钟是否成功采集到 INFO」，由本服务负责把结论落到
 * instance_info.status、instance_fault 以及状态变更通知。</p>
 *
 * <p>状态维护与「是否配置了 instance_alive 报警项」解耦——节点列表、故障记录、异常状态巡检
 * 都依赖 status，删掉报警配置不应让这些一起失效。</p>
 */
@Service
public class InstanceAliveStateService extends BaseAlertService {

    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private InstanceFaultDao instanceFaultDao;

    @Autowired
    private AppDao appDao;

    @Autowired
    private UserService userService;

    /**
     * 根据本轮探活结论更新单个节点状态。
     *
     * @param info  节点信息（status 以库中最新值为准，此处会被刷新）
     * @param alive 本轮是否判定存活
     * @return 状态是否发生了变更
     */
    public boolean refreshAliveState(InstanceInfo info, boolean alive) {
        if (info == null) {
            return false;
        }
        try {
            InstanceInfo latest = instanceDao.getInstanceInfoById(info.getId());
            if (latest == null) {
                return false;
            }
            info.setStatus(latest.getStatus());
            // 平台侧已下线的节点不参与状态翻转：进程还在也不该恢复成「运行中」
            if (info.getStatus() == InstanceStatusEnum.OFFLINE_STATUS.getStatus()) {
                return false;
            }
            if (alive) {
                AppDesc appDesc = appDao.getAppDescById(info.getAppId());
                if (appDesc != null && appDesc.isOffline()) {
                    return false;
                }
                if (info.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                    return transfer(info, InstanceStatusEnum.GOOD_STATUS.getStatus(), "实例状态恢复", KafkaAlertVO.INFO);
                }
            } else if (info.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                return transfer(info, InstanceStatusEnum.ERROR_STATUS.getStatus(), "实例状态异常", KafkaAlertVO.WARN);
            }
        } catch (Exception e) {
            logger.error("refresh alive state failed for {}: {}", info.getHostPort(), e.getMessage(), e);
        }
        return false;
    }

    private boolean transfer(InstanceInfo info, int newStatus, String reason, String severity) {
        info.setStatus(newStatus);
        instanceDao.update(info);
        logger.warn("instance {} status -> {} ({})", info.getHostPort(), newStatus, reason);
        // 状态与故障记录属于运行状态，任何情况下都要落库：节点列表、故障诊断都依赖它们，
        // 关掉集群报警不该让这些页面显示过期状态。
        saveFault(info, reason);
        // 对外通知（kafka + 报警记录）才受「集群报警通知」开关控制
        if (isAlertEnabled(info.getAppId())) {
            sendKafkaStateAlert(info, reason, severity);
            sendStateChangeRecord(info);
        } else {
            logger.info("cluster alert disabled for appId={}, skip state change notification of {}",
                    info.getAppId(), info.getHostPort());
        }
        return true;
    }

    /**
     * 集群是否启用了监控告警（app_desc.is_access_monitor）。
     * 查不到集群时按启用处理——宁可多报也不要静默漏报。
     */
    private boolean isAlertEnabled(long appId) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        return appDesc == null || appDesc.getIsAccessMonitor() == 1;
    }

    private void saveFault(InstanceInfo info, String reason) {
        InstanceFault instanceFault = new InstanceFault();
        instanceFault.setAppId((int) info.getAppId());
        instanceFault.setInstId(info.getId());
        instanceFault.setIp(info.getIp());
        instanceFault.setPort(info.getPort());
        instanceFault.setType(info.getType());
        instanceFault.setCreateTime(new Date());
        instanceFault.setReason(reason);
        instanceFaultDao.insert(instanceFault);
    }

    private void sendStateChangeRecord(InstanceInfo info) {
        String title = "实例(" + info.getIp() + ":" + info.getPort() + ")状态发生变化";
        String message = describeStateChange(info);
        appAlertRecordService.saveAlertInfoByType(AlertTypeEnum.INSTANCE_RUNNING_STATE_CHANGE, title, message, info);
        emailComponent.sendMailToAdmin(title, message);
    }

    private String describeStateChange(InstanceInfo info) {
        AppDesc appDesc = appDao.getAppDescById(info.getAppId());
        String appName = appDesc == null ? String.valueOf(info.getAppId())
                : info.getAppId() + "-" + appDesc.getName();
        String transition;
        if (info.getStatus() == InstanceStatusEnum.ERROR_STATUS.getStatus()) {
            transition = "由运行中变为异常";
        } else if (info.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
            transition = "由异常恢复为运行中";
        } else {
            transition = "状态变更";
        }
        return String.format("Redis管理平台-实例(%s:%s)-%s, 集群:%s",
                info.getIp(), info.getPort(), transition, appName);
    }

    private void sendKafkaStateAlert(InstanceInfo info, String reason, String severity) {
        AppDesc app = appDao.getAppDescById(info.getAppId());
        if (app == null) {
            return;
        }
        KafkaAlertVO vo = new KafkaAlertVO();
        vo.setContent(String.format(
                "Redis管理平台(应用系统->[%s] 负责人->[%s]) IP->[%s] PORT->[%s] APP_ID->[%s] INST_ID->[%s] INST_TYPE->[%s] %s",
                app.getName(), userService.getOfficerName(app.getOfficer()), info.getIp(), info.getPort(),
                app.getAppId(), info.getId(),
                InstanceInfoEnum.InstanceTypeEnum.getByType(info.getType()), reason));
        vo.setSeverity(severity);
        vo.setEventTime(DateUtil.formatYYYYMMddHHMMSS(new Date()));
        vo.setObject(app.getName() + "->" + info.getHostPort());
        vo.setLocation("Redis管理平台");
        vo.setTitle("Redis管理平台-实例告警");
        vo.setHost(info.getIp());
        vo.setMergeKey(info.getHostPort() + reason);
        vo.setBusiness(app.getIntro());
        sendKafkaAlert(vo);
    }
}
