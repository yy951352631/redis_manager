package com.shcj.cache.task.tasks.resource;

import com.shcj.cache.exception.BizException;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.exception.SSHException;
import com.shcj.cache.machine.MachineCache;
import com.shcj.cache.ssh.SSHTemplate;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.PushEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.StringUtil;
import com.shcj.cache.web.enums.SshAuthTypeEnum;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.springframework.beans.factory.config.BeanDefinition.SCOPE_PROTOTYPE;

/**
 * Created by chenshi on 2020/7/13.
 */
@Component("PackCompileTask")
@Scope(SCOPE_PROTOTYPE)
public class PackCompileTask extends BaseTask {

    /**
     * 编译容器ip
     */
    private String containerIp;
    /**
     * 资源id
     */
    private Integer resourceId;
    /**
     * 仓库id
     */
    private Integer repositoryId;
    // 操作人
    private String username;
    // 资源包
    private SystemResource resource;
    // 仓库资源
    private SystemResource repository;

    // 编译
    private static String COMPILE_SH = " mkdir -p %s && cd %s && " +
            " wget -O resource.tar.gz %s && tar zxvf resource.tar.gz --strip-component=1 &&" +
            " rm -f resource.tar.gz && make";
    // 备份
    private static String BACKUP_SH = "mkdir -p %s && cp -r %s %s ";
    // 上传
    private static String PRIVATEKEY_UPLOAD_SH = "cd %s && tar -cvf %s %s && scp -i %s -oStrictHostKeyChecking=no -r %s %s@%s:%s";
    private static String COMPRESS_REDIS_BIN = "cd %s && tar -cvf %s %s";

    private static String PACKAGE_SUFFIX = "-make.tar.gz";

    private static String PRIVATE_KEY = ConstUtils.REDIS_BASE_DIR + "/ssh/id_rsa";

    private static int COMPILE_TIME = 10 * 60 * 1000;

    @Override
    public List<String> getTaskSteps() {

        List<String> taskStepList = new ArrayList<String>();
        //1. 参数初始化
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        //2. 检查容器ssh环境&私钥文件&gcc
        taskStepList.add("checkSshEnv");
        //3. 远程仓库下载资源包&解压编译
        taskStepList.add("compile");
        //4. 备份资源
        taskStepList.add("bakResource");
        //5. 二进制包上传到仓库
        taskStepList.add("uploadResource");
        //6. 编译完成
        taskStepList.add("compileOver");
        return taskStepList;
    }

    @Override
    public TaskFlowStatusEnum init() {

        super.init();
        // 1.资源id
        resourceId = MapUtils.getInteger(paramMap, TaskConstants.RESOURCE_ID);
        if (resourceId == null) {
            throw new BizException("task {} source resourceId {} is empty", taskId, resourceId);
        }
        // 2.仓库服务器
        repositoryId = MapUtils.getInteger(paramMap, TaskConstants.REPOSITORY_ID);
        if (repositoryId == null) {
            throw new BizException("task {} 仓库服务器 repositoryId {} is empty", taskId, repositoryId);
        }
        // 3.容器ip
        containerIp = MapUtils.getString(paramMap, TaskConstants.CONTAINER_IP);
        if (StringUtils.isEmpty(containerIp)) {
            throw new BizException("task {} container ip {} is empty", taskId, containerIp);
        }

        // 4.操作人/资源/仓库信息
        username = MapUtils.getString(paramMap, TaskConstants.USER_INFO_KEY);
        resource = resourceDao.getResourceById(resourceId);
        repository = resourceDao.getResourceById(repositoryId);

        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum checkSshEnv() {

        //1. 资源状态更新:编译中
        if (resource != null) {
            resource.setIspush(PushEnum.COMPILEING.getValue());
            resourceDao.update(resource);
        } else {
            throw new BizException("resource id:{} is empty ", resourceId);
        }

        if (ConstUtils.SSH_AUTH_TYPE == SshAuthTypeEnum.PASSWORD.getValue()) {
            // 2.1 用户名密码
            logger.info(marker, "check ssh use username/passwd");
            return TaskFlowStatusEnum.SUCCESS;
        } else if (ConstUtils.SSH_AUTH_TYPE == SshAuthTypeEnum.PUBLIC_KEY.getValue()) {
            // 2.2 检查私钥文件
            String checkCmd = String.format("ls -l %s", PRIVATE_KEY);

            SSHTemplate.Result result;
            try {
                result = sshService.executeWithResult(containerIp, checkCmd, COMPILE_TIME);
            } catch (SSHException ex) {
                throw new BizException("SSHException, {}, {}", containerIp, checkCmd, ex);
            }

            if (result.isSuccess() && !StringUtil.isBlank(result.getResult())) {
                logger.info(marker, "check private key exists ,cmd:{} result : {}", checkCmd, result);
                return TaskFlowStatusEnum.SUCCESS;
            } else {
                // 下载私钥文件到机器
                throw new BizException("check private key ,cmd:{} result : {}", checkCmd, result);
            }
        }


        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum compile() {

        //1. 资源编译
        String compileDir = ConstUtils.REDIS_COMPILE_BASE_DIR + resource.getName();
        String compileCmd = String.format(COMPILE_SH, compileDir, compileDir, resource.getUrl());

        logger.info(marker, "download from url:{}", resource.getUrl());
        logger.info(marker, "compile cmd : {}", compileCmd);
        try {
            SSHTemplate.Result result = sshService.executeWithResult(containerIp, compileCmd, COMPILE_TIME);
            if (result.isSuccess()) {
                logger.info(marker, "compile cmd:{} result : {}", compileCmd, result);
            } else {
                throw new BizException("compile cmd:{} result return:{} result error: {}", compileCmd, result.getResult(), result.getExcetion());
            }
        } catch (SSHException e) {
            throw new BizException("compile error containerIp={}, compileCmd={}", containerIp, compileCmd, e);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum bakResource() {

        if (repository != null) {
            try {
                String resouceFile = repository.getDir() + resource.getDir() + "/" + resource.getName() + PACKAGE_SUFFIX;
                String bakPath = repository.getDir() + resource.getDir() + "/bak/";
                String backupFile = bakPath + resource.getName() + PACKAGE_SUFFIX + "-" + DateUtil.formatYYYYMMddHHMMss(new Date()) + "-" + username;
                String bakcmd = String.format(BACKUP_SH, bakPath, resouceFile, backupFile);

                //远程仓库ip
                String repositoryIp = repository.getName();
                // 源文件如果存在，需要先备份
                SSHTemplate.Result existResult = sshService.executeWithResult(repositoryIp, String.format("ls -l %s", resouceFile));
                logger.info(marker, "resouceFile result:{} {}", existResult.isSuccess(), existResult);
                if (existResult.isSuccess() && !StringUtil.isBlank(existResult.getResult())) {
                    SSHTemplate.Result result = sshService.executeWithResult(repositoryIp, bakcmd);
                    if (result.isSuccess()) {
                        logger.info(marker, "bakResource success cmd:{} ,result :{}", bakcmd, result.getResult());
                    } else {
                        throw new BizException("bakResource error cmd:{} ,result: {}", bakcmd, result);
                    }
                }

            } catch (SSHException e) {
                throw new BizException("bakResource error :{}", e.getMessage());
            }
        } else {
            throw new BizException("repository id:{} is empty,repository {}", repositoryId);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum uploadResource() {

        // 1.源资源文件和打包资源文件
        String compileResourceName = ConstUtils.REDIS_COMPILE_BASE_DIR + resource.getName() + PACKAGE_SUFFIX;
        String uploadPath = repository.getDir() + resource.getDir();
        // 2.上传文件
        String compressCmd = "";
        if (ConstUtils.SSH_AUTH_TYPE == SshAuthTypeEnum.PASSWORD.getValue()) {
            // 2.1 用户名密码
            compressCmd = String.format(COMPRESS_REDIS_BIN, ConstUtils.REDIS_COMPILE_BASE_DIR, compileResourceName, resource.getName());
        } else if (ConstUtils.SSH_AUTH_TYPE == SshAuthTypeEnum.PUBLIC_KEY.getValue()) {
            compressCmd = String.format(PRIVATEKEY_UPLOAD_SH, ConstUtils.REDIS_COMPILE_BASE_DIR, compileResourceName, resource.getName(), PRIVATE_KEY, compileResourceName, MachineCache.getUser(repository.getName()), repository.getName(), uploadPath);
        }

        try {
            SSHTemplate.Result result1 = sshService.executeWithResult(containerIp, compressCmd, COMPILE_TIME);
            if (result1.isSuccess()) {
                logger.info(marker, "upload cmd {} success : result1:{}  ", compressCmd, result1);
            } else {
                throw new BizException("upload cmd {} fail : result1:{} ", compressCmd, result1);
            }
            //要求仓库和管理系统部署在一起
            String uploadCmd = String.format("sshpass -p %s scp -oStrictHostKeyChecking=no -r %s@%s:%s %s", MachineCache.getPwd(containerIp), MachineCache.getUser(containerIp), containerIp, compileResourceName, uploadPath);
            SSHTemplate.Result result2 = sshService.executeWithResult(repository.getName(), uploadCmd, COMPILE_TIME);
            if (result2.isSuccess()) {
                logger.info(marker, "upload cmd {} success : result2:{}  ", uploadCmd, result2);
            } else {
                logger.error(marker, "upload cmd {} fail : result2:{}  ", uploadCmd, result2);
            }

        } catch (SSHException e) {
            throw new BizException("uploadResource error :{}", e.getMessage());
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum compileOver() {

        if (resource != null) {
            //1.清理远程目录
            String clearCmd = String.format("rm -rf %s", ConstUtils.REDIS_COMPILE_BASE_DIR + resource.getName() + "*");
            try {
                logger.info(marker, "clear resource :" + sshService.executeWithResult(containerIp, clearCmd));
            } catch (SSHException ex) {
                throw new BizException("compileOver failed, {} {}", clearCmd, ex.getMessage());
            }
            //2.更新资源状态
            resource.setIspush(PushEnum.YES.getValue());
            resource.setLastmodify(new Date());
            resource.setUsername(username);
            resourceDao.update(resource);
        } else {
            throw new BizException("resource id:{} is empty,resource {}", resourceId);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

}
