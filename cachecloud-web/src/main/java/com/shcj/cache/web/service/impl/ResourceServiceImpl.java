package com.shcj.cache.web.service.impl;

import com.shcj.cache.dao.ResourceDao;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.exception.SSHException;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.protocol.MachineProtocol;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.ssh.SSHTemplate;
import com.shcj.cache.ssh.SSHUtil;
import com.shcj.cache.task.constant.PushEnum;
import com.shcj.cache.task.constant.ResourceEnum;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.service.ResourceService;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


/**
 * Created by chenshi on 2020/7/6.
 */
@Service("resourceService")
public class ResourceServiceImpl implements ResourceService {

    private Logger logger = LoggerFactory.getLogger(ResourceService.class);

    @Autowired
    ResourceDao resourceDao;
    @Autowired
    SSHService sshService;
    @Autowired
    MachineCenter machineCenter;

    @Override
    public SuccessEnum saveResource(SystemResource systemResouce) {
        try {
            resourceDao.save(systemResouce);
        } catch (Exception e) {
            logger.error("key {} value {} update faily" + e.getMessage(), e);
            return SuccessEnum.FAIL;
        }
        return SuccessEnum.SUCCESS;
    }

    @Override
    public SuccessEnum updateResource(SystemResource systemResouce) {
        try {
            resourceDao.update(systemResouce);
        } catch (Exception e) {
            logger.error("key {} value {} update faily" + e.getMessage(), e);
            return SuccessEnum.FAIL;
        }
        return SuccessEnum.REPEAT;
    }

    @Override
    public List<SystemResource> getResourceList(int resourceType) {
        try {
            return resourceDao.getResourceList(resourceType);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<SystemResource> getResourceList(int resourceType, String searchName) {
        try {
            return resourceDao.getResourceListByName(resourceType, searchName);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public SuccessEnum pushScript(Integer repositoryId, Integer resourceId, String content, AppUser userInfo) {

        try {
            SystemResource repository = resourceDao.getResourceById(repositoryId);
            SystemResource resource = resourceDao.getResourceById(resourceId);
            // a).推送脚本
            if (repository != null && resource != null && resource.getType() == ResourceEnum.SCRIPT.getValue()) {
                // 远程仓库地址ip/path
                String repos_ip = repository.getName();
                String fileDir = String.format("%s%s", repository.getDir(), resource.getDir());

                // 1. 先内容保存到本地
                String localAbsolutePath = MachineProtocol.TMP_DIR + resource.getName();
                File tmpDir = new File(MachineProtocol.TMP_DIR);
                if (!tmpDir.exists()) {
                    if (!tmpDir.mkdirs()) {
                        logger.error("cannot create /tmp/cachecloud directory.");
                    }
                }
                Path path = Paths.get(MachineProtocol.TMP_DIR + resource.getName());
                BufferedWriter bufferedWriter = null;
                try {
                    bufferedWriter = Files.newBufferedWriter(path, Charset.forName(MachineProtocol.ENCODING_UTF8));
                    bufferedWriter.write(content);
                } catch (IOException e) {
                    logger.error("write rmt file error, ip: {}, path: {}, content: {}", repos_ip, localAbsolutePath, content);
                    return SuccessEnum.FAIL;
                } finally {
                    if (bufferedWriter != null) {
                        try {
                            bufferedWriter.close();
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                // 2. scp file to remote

                try {
                    //2.1 备份老文件
                    String bakDir = fileDir + "/bak";
                    String filename = fileDir + "/" + resource.getName();
                    String bakfilename = bakDir + "/" + resource.getName() + "-" + DateUtil.formatYYYYMMddHHMMss(new Date()) + "-" + userInfo.getName();
                    String bakcmd = String.format("mkdir -p %s && cp -r %s %s", bakDir, filename, bakfilename);
                    String bak_result = SSHUtil.execute(repos_ip, bakcmd);
                    logger.info("bak_result: {}", bak_result);

                    //2.2 上传新文件
                    SSHUtil.scpFileToRemote(repos_ip, localAbsolutePath, fileDir);
                } catch (SSHException e) {
                    logger.error("message {}", e.getMessage(), e);
                    return SuccessEnum.FAIL;
                }
                // 3. delete temp file
                File file = new File(localAbsolutePath);
                if (file.exists()) {
                    boolean del = file.delete();
                    if (!del) {
                        logger.warn("file.delete:{}", del);
                    }
                }
                // 4.update push status
                resource.setIspush(PushEnum.YES.getValue());
                resource.setUsername(userInfo.getName());
                resource.setLastmodify(new Date());
                resourceDao.update(resource);
            }
        } catch (Exception e) {
            logger.error("pushScript resource repositoryId:{} resourceId:{} error:{}", repositoryId, resourceId, e.getMessage(), e);
            return SuccessEnum.FAIL;
        }
        return SuccessEnum.SUCCESS;
    }

    @Override
    public SuccessEnum pushDir(Integer repositoryId, Integer resourceId, AppUser userInfo) {

        try {
            SystemResource repository = resourceDao.getResourceById(repositoryId);
            if (machineCenter.getMachineInfoByIp(repository.getName()) == null) {
                throw new BizException("需要先在机器管理中维护仓库服务器{}（用户名和密码需要手工处理）", repository.getName());
            }

            SystemResource resource = resourceDao.getResourceById(resourceId);
            if (repository != null && resource != null) {
                // 1.推送目录
                String cmd = String.format("mkdir -p %s", repository.getDir() + resource.getName());
                SSHTemplate.Result result = sshService.executeWithResult(repository.getName(), cmd);
                if (!result.isSuccess()) {
                    throw new BizException("pushDir resource repositoryId:{} resourceId:{} result:{} cmd:{}", repositoryId, resourceId, result, cmd);
                }
                // 2.update push status
                resource.setIspush(PushEnum.YES.getValue());
                resource.setUsername(userInfo.getName());
                resource.setLastmodify(new Date());
                resourceDao.update(resource);
            }

        } catch (Exception e) {
            throw new BizException("pushDir resource repositoryId:{} resourceId:{} error:{}", repositoryId, resourceId, e.getMessage(), e);
        }
        return SuccessEnum.SUCCESS;
    }

    @Override
    public SystemResource getResourceById(int repositoryId) {
        try {
            return resourceDao.getResourceById(repositoryId);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public SystemResource getResourceByName(String resourceName) {
        try {
            return resourceDao.getResourceByName(resourceName);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String getRemoteFileContent(int resourceId, int respoitoryId) {

        try {
            SystemResource resource = resourceDao.getResourceById(resourceId);
            SystemResource repository = resourceDao.getResourceById(respoitoryId);

            String ip = repository.getName();
            if (!StringUtils.isEmpty(ip)) {
                String command = String.format("cat %s%s/%s", repository.getDir(), resource.getDir(), resource.getName());
                SSHTemplate.Result result = sshService.executeWithResult(ip, command);
                logger.info(result.getResult());
                return result.getResult();
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public SystemResource getRepository() {
        try {
            List<SystemResource> repositorylist = resourceDao.getResourceList(ResourceEnum.Repository.getValue());
            if (!CollectionUtils.isEmpty(repositorylist)) {
                return repositorylist.get(0);
            } else {
                return null;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return null;
    }

    @Override
    public Map<Integer, Integer> getAppUseRedis() {
        Map<Integer, Integer> resultMap = new HashMap<>();
        try {
            List<Map<Integer, Integer>> mapList = resourceDao.getAppUseRedis();
            if (!CollectionUtils.isEmpty(mapList)) {
                for (Map<Integer, Integer> stat : mapList) {
                    resultMap.put(MapUtils.getInteger(stat, "version_id"), MapUtils.getInteger(stat, "num"));
                }
            }
        } catch (Exception e) {
            logger.error("getAppUseRedis error :{}", e.getMessage(), e);
        }
        return resultMap;
    }
}
