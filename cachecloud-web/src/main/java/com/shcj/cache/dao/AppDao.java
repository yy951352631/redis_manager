package com.shcj.cache.dao;

import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppSearch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 基于app的dao操作
 *
 * @author leifu
 * @Date 2014年5月15日
 * @Time 下午1:58:22
 */
public interface AppDao {
    /**
     * 通过appId获取对应的app
     *
     * @param appId
     * @return
     */
    public AppDesc getAppDescById(@Param("appId") long appId);

    AppDesc getByClusterNo(@Param("clusterNo") int clusterNo);

    /**
     * 分配下一个 cluster_no（调用方需在事务内使用，避免并发冲突）
     */
    @Select("SELECT COALESCE(MAX(cluster_no), 100000) + 1 FROM app_desc")
    int allocateNextClusterNo();

    AppDesc getOnlineAppDescById(@Param("appId") long appId);

    /**
     * 通过所有在线的应用
     *
     * @return
     */
    public List<AppDesc> getOnlineApps();

    List<AppDesc> getOnlineAppsNonTest();

    /**
     * 通过所有在线的应用
     *
     * @return
     */
    public List<AppDesc> getAllApps();

    /**
     * 通过应用名获取对应app
     *
     * @param appName
     * @return
     */
    public AppDesc getByAppName(@Param("appName") String appName);

    /**
     * 保存app
     *
     * @param appDesc
     * @return
     */
    public int save(AppDesc appDesc);

    /**
     * 更新app
     *
     * @param appDesc
     * @return
     */
    public int update(AppDesc appDesc);


    /**
     * 更新app,包含自定义密码
     */
    public int updateWithCustomPwd(AppDesc appDesc);

    /**
     * 更新哨兵名称
     */
    public int updateMasterName(AppDesc appDesc);


    /**
     * 删除app
     */
    public int delete(@Param("id") Long id);

    /**
     * 获取用户拥有的应用
     *
     * @param userId
     * @return
     */
    public List<AppDesc> getAppDescList(@Param("userId") long userId);

    /**
     * 已发布集群的完整字段（含 is_access_monitor）。
     *
     * <p>不复用 getOnlineApps：它走的 app_desc_select_column 里没有 is_access_monitor，
     * 主面板要靠这个字段区分「真的没问题」和「压根没监控」。</p>
     */
    List<AppDesc> listPublishedApps();

    /**
     * 获取应用拥有的应用个数
     *
     * @param userId
     * @return
     */
    public int getUserAppCount(@Param("userId") long userId);

    /**
     * 获取所有应用
     *
     * @param appSearch
     * @return
     */
    public List<AppDesc> getAllAppDescList(AppSearch appSearch);

    /**
     * 获取应用个数(有效状态)
     *
     * @param appSearch
     * @return
     */
    public int getAllAppCount(AppSearch appSearch);

    /**
     * <p>
     * Description:获取应用个数
     * </p>
     *
     * @param
     * @return
     */
    public int getTotalAppCount();

    /**
     * 更新appKey
     *
     * @param appId
     * @param appKey
     */
    public void updateAppKey(@Param("appId") long appId, @Param("appKey") String appKey);

    /**
     * 仅更新应用 Redis 版本绑定。
     */
    int updateVersionId(@Param("appId") long appId, @Param("versionId") int versionId);

    int updateVersionName(@Param("appId") long appId, @Param("versionName") String versionName);

    /**
     * 获取app安装不同版本的数量
     */
    @Select("SELECT version_id,count(version_id) as num from app_desc where status=2 GROUP BY version_id")
    public List<Map<String, Integer>> getVersionStat();

    public List<AppDesc> getAppDescByIds(@Param("appIds") List<String> appIds);


}
