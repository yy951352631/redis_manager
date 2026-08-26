package com.shcj.cache.machine.impl;

import com.google.common.base.Strings;
import com.shcj.cache.dao.*;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.entity.MachineRelation;
import com.shcj.cache.entity.MachineRoom;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.machine.MachineCache;
import com.shcj.cache.machine.MachineDeployCenter;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.NMONFileFactory;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 机器部署相关
 *
 * @author leifu
 * changed @Date 2016-4-24
 * @Time 下午5:07:30
 */
@Service("machineDeployCenter")
public class MachineDeployCenterImpl implements MachineDeployCenter {
    private Logger logger = LoggerFactory.getLogger(MachineDeployCenterImpl.class);
    @Autowired
    private MachineDao machineDao;
    @Autowired
    private MachineRoomDao machineRoomDao;
    @Autowired
    private MachineStatsDao machineStatsDao;
    @Autowired
    private ServerStatusDao serverStatusDao;
    @Autowired
    private MachineRelationDao machineRelationDao;
    @Autowired
    SSHService sshService;
    @Autowired
    ResourceService resourceService;

    /**
     * 将机器加入资源池并统计、监控
     *
     * @param machineInfo
     * @return
     */
    @Override
    public boolean addMachine(MachineInfo machineInfo) {
        if (machineInfo == null || Strings.isNullOrEmpty(machineInfo.getIp())) {
            throw new BizException("机器 IP 不能为空");
        }
        try {
            if (machineInfo.getId() > 0) {
                updateExistingMachine(machineInfo);
            } else {
                insertMachine(machineInfo);
            }
            MachineCache.refresh();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            logger.error("save machineInfo: {} to db error.", machineInfo, e);
            throw new BizException(resolveMachineSaveError(e, machineInfo));
        }
        logger.info("save and deploy machine ok, machineInfo: {}", machineInfo);
        return true;
    }

    private void insertMachine(MachineInfo machineInfo) {
        if (Strings.isNullOrEmpty(machineInfo.getSshPasswd())) {
            throw new BizException("新增机器时必须填写 SSH 密码");
        }
        machineDao.saveMachineInfo(machineInfo);
    }

    private void updateExistingMachine(MachineInfo machineInfo) {
        MachineInfo existing = machineDao.getMachineFullInfoById(machineInfo.getId());
        if (existing == null) {
            throw new BizException("机器不存在或已删除，id={}", machineInfo.getId());
        }
        if (Strings.isNullOrEmpty(machineInfo.getSshPasswd())) {
            machineInfo.setSshPasswd(existing.getSshPasswd());
        }
        if (Strings.isNullOrEmpty(machineInfo.getSshUser())) {
            machineInfo.setSshUser(existing.getSshUser());
        }
        String oldIp = existing.getIp();
        int rows = machineDao.updateMachineInfoById(machineInfo);
        if (rows <= 0) {
            throw new BizException("更新机器失败，未找到 id={} 的记录", machineInfo.getId());
        }
        if (!oldIp.equals(machineInfo.getIp())) {
            machineDao.updateBrevityScheduleHost(oldIp, machineInfo.getIp());
            logger.info("machine ip changed: {} -> {}, brevity schedule host updated", oldIp, machineInfo.getIp());
        }
    }

    private String resolveMachineSaveError(Exception e, MachineInfo machineInfo) {
        String ip = machineInfo != null ? machineInfo.getIp() : "";
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String raw = root.getMessage();
        if (raw == null) {
            return "保存机器「" + ip + "」失败，请稍后重试";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("duplicate entry")) {
            return "机器 IP「" + ip + "」已存在，请检查是否重复录入";
        }
        if (lower.contains("ssh_passwd") && lower.contains("null")) {
            return "保存失败：SSH 密码不能为空。修改机器时请重新填写 SSH 密码";
        }
        if (lower.contains("data too long") && lower.contains("ip")) {
            return "机器 IP「" + ip + "」过长，请填写真实 IP 或主机名（最多 16 字符）";
        }
        if (lower.contains("data truncation") || lower.contains("data too long")) {
            return "保存失败：部分字段内容超出长度限制，请缩短后重试";
        }
        return "保存机器「" + ip + "」失败：" + raw;
    }

    @Override
    public boolean addMachineRoom(MachineRoom room) {
        boolean success = true;
        try {
            machineRoomDao.saveRoom(room);
        } catch (Exception e) {
            logger.error("save machineRoom: {} to db error.", room.toString(), e);
            return false;
        }

        if (success) {
            logger.info("save machineRoom ok, machineRoom: {}", room.toString());
        }
        return success;
    }

    @Override
    public boolean updateMachineRoom(MachineRoom room) {
        try {
            return machineRoomDao.updateRoom(room) > 0;
        } catch (Exception e) {
            logger.error("update machineRoom: {} error.", room, e);
            return false;
        }
    }

    @Override
    public boolean removeMachineRoom(int roomId) {
        try {
            machineRoomDao.removeRoom(roomId);
        } catch (Exception e) {
            logger.error("remove machineRoom from db error, machineRoom: {}", roomId, e);
            return false;
        }
        logger.info("remove machineRoom ok: {}", roomId);
        return true;
    }

    /**
     * 删除机器，并删除相关的定时任务
     *
     * @param machineInfo
     * @return
     */
    @Override
    public boolean removeMachine(MachineInfo machineInfo) {
        if (machineInfo == null || Strings.isNullOrEmpty(machineInfo.getIp())) {
            logger.warn("machineInfo is null or ip is empty.");
            return false;
        }
        String machineIp = machineInfo.getIp();

        // 从db中删除machine和相关统计信息
        try {
            machineDao.removeMachineInfoByIp(machineIp);
            machineStatsDao.deleteMachineStatsByIp(machineIp);
            serverStatusDao.deleteServerInfo(machineIp);
        } catch (Exception e) {
            logger.error("remove machineInfo from db error, machineInfo: {}", machineInfo.toString(), e);
            return false;
        }
        logger.info("remove and undeploy machine ok: {}", machineInfo.toString());
        return true;
    }

    @Override
    public void updateMachineRelation(int id, Long taskid, int is_sync) {
        try {
            machineRelationDao.updateMachineRelation(id, taskid, is_sync);
        } catch (Exception e) {
            logger.error("update machineRelation id:{} taskid :{} error, machineRelation: {}", id, taskid, e.getMessage());
        }
    }

    @Override
    public List<MachineRelation> getMachineRelationList(String ip) {
        List<MachineRelation> relationList = new ArrayList<MachineRelation>();
        try {
            relationList = machineRelationDao.getRelationList(ip);
        } catch (Exception e) {
            logger.error("get machine relation : containerIp:{} , error message:{}", ip, e.getMessage(), e);
        }
        return relationList;
    }

    @Override
    public SuccessEnum checkMachineSyncStatus(String containerIp, String sourceIp, int is_sync) {
        List<MachineRelation> machineRelationList = null;
        try {
            machineRelationList = machineRelationDao.getMachineSyncStatus(containerIp, sourceIp, is_sync);
        } catch (Exception e) {
            logger.error("check machine relation error : containerIp: {} sourceIp:{} is_sync:{} ,error message: {}", containerIp, sourceIp, is_sync, e.getMessage(), e);
            return SuccessEnum.ERROR;
        }
        if (machineRelationList != null && machineRelationList.size() > 0) {
            logger.info("check machine relation containerIp: {} sourceIp:{} is_sync:{} size :{} ", containerIp, sourceIp, is_sync, machineRelationList.size());
            return SuccessEnum.REPEAT;
        } else {
            return SuccessEnum.NO_REPEAT;
        }
    }

    @Override
    public void synToolToMachine(String ip) {
        SystemResource repository = resourceService.getRepository();
        String binDir = ConstUtils.REDIS_DEFAULT_BIN;
        String nmonPath = binDir + "/nmon";
        String nmonUrl = repository != null && !Strings.isNullOrEmpty(repository.getUrl())
                ? repository.getUrl() + "/tool/nmon" : null;
        String redisCliUrl = repository != null && !Strings.isNullOrEmpty(repository.getUrl())
                ? repository.getUrl() + "/tool/redis-cli" : null;
        try {
            prepareMachineDirs(ip, binDir);
            deployNmon(ip, binDir, nmonPath, nmonUrl);
            if (!Strings.isNullOrEmpty(redisCliUrl)) {
                deployRedisCli(ip, binDir, redisCliUrl);
            }
            String verifyCmd = String.format("stat -c%%s %s 2>/dev/null && %s -V 2>&1 | head -1", nmonPath, nmonPath);
            com.shcj.cache.ssh.SSHTemplate.Result verify = sshService.executeWithResult(ip, verifyCmd);
            if (verify == null || !verify.isSuccess() || Strings.isNullOrEmpty(verify.getResult())) {
                throw new BizException("同步 Tool 失败：nmon 未正确部署到 " + nmonPath
                        + "。请检查资源仓库是否可达，或在目标机执行 yum install nmon 后重试");
            }
            logger.info("synToolToMachine ok ip={} nmon={}", ip, verify.getResult().trim());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("同步 Tool 失败：" + ex.getMessage(), ex);
        }
    }

    private void prepareMachineDirs(String ip, String binDir) throws Exception {
        String cmd = String.format("mkdir -p %s/{bin,conf,data,logs,nmon,redis,sh,soft,ssh,tmp} && mkdir -p %s",
                ConstUtils.REDIS_BASE_DIR, binDir);
        sshService.execute(ip, cmd);
    }

    private void deployNmon(String ip, String binDir, String nmonPath, String nmonUrl) throws Exception {
        if (!Strings.isNullOrEmpty(nmonUrl)) {
            // 先下到临时文件并限时，避免 wget 长时间占住最终 nmon 路径导致 Text file busy
            String wgetCmd = String.format(
                    "cd %s && rm -f .nmon.tmp nmon && "
                            + "(wget --timeout=10 --tries=1 -q -O .nmon.tmp '%s' 2>/dev/null || true) && "
                            + "if [ -s .nmon.tmp ] && [ $(stat -c%%s .nmon.tmp) -ge 1024 ]; then mv -f .nmon.tmp nmon; else rm -f .nmon.tmp; fi",
                    binDir, nmonUrl);
            sshService.execute(ip, wgetCmd);
        }
        if (!isRemoteNmonValid(ip, nmonPath)) {
            String systemCopyCmd = String.format(
                    "cd %s && if command -v nmon >/dev/null 2>&1; then cp -f $(command -v nmon) nmon; "
                            + "elif [ -x /usr/bin/nmon ]; then cp -f /usr/bin/nmon nmon; fi && chmod +x nmon 2>/dev/null || true",
                    binDir);
            sshService.execute(ip, systemCopyCmd);
        }
        if (!isRemoteNmonValid(ip, nmonPath)) {
            File bundledNmon = NMONFileFactory.getBundledNmonFile();
            if (bundledNmon == null) {
                throw new BizException("同步 Tool 失败：资源仓库不可达且后端未内置 nmon，"
                        + "请将介质包中的 nmon 放到 cachecloud-web/nmon/ 或在目标机 yum install nmon");
            }
            com.shcj.cache.ssh.SSHTemplate.Result scpResult = sshService.scpFileToRemote(ip, bundledNmon.getAbsolutePath(), binDir);
            if (scpResult == null || !scpResult.isSuccess()) {
                throw new BizException("同步 Tool 失败：内置 nmon SCP 到 " + ip + " 失败");
            }
            sshService.execute(ip, String.format("cd %s && mv -f %s nmon && chmod +x nmon", binDir, bundledNmon.getName()));
        }
        if (!isRemoteNmonValid(ip, nmonPath)) {
            throw new BizException("同步 Tool 失败：nmon 未正确部署到 " + nmonPath);
        }
    }

    private void deployRedisCli(String ip, String binDir, String redisCliUrl) throws Exception {
        String cmd = String.format(
                "cd %s && (wget --timeout=10 --tries=1 -q -O redis-cli '%s' 2>/dev/null || true) "
                        + "&& chmod +x redis-cli 2>/dev/null || true",
                binDir, redisCliUrl);
        sshService.execute(ip, cmd);
    }

    private boolean isRemoteNmonValid(String ip, String nmonPath) throws Exception {
        String checkCmd = String.format(
                "[ -s \"%s\" ] && [ $(stat -c%%s \"%s\") -ge 1024 ] && \"%s\" -V >/dev/null 2>&1",
                nmonPath, nmonPath, nmonPath);
        com.shcj.cache.ssh.SSHTemplate.Result result = sshService.executeWithResult(ip, checkCmd);
        return result != null && result.isSuccess();
    }
}
