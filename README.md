# redis_manager

Redis 运维管理平台（基于 CacheCloud 二次开发，前后端分离）。

---

##1. 服务器配置
###仓库：
需要安装sshpass，nginx
###redis和sentinel资源池服务器：
+ 创建redis用户：useradd redis
+ 在/home/redis目录下新建目录
```shell
mkdir bin  conf  data  logs  nmon  redis  sh  soft  ssh  tmp
```
+ 从仓库将nmon上传至bin中
```shell
for i in {1..6} ; do sshpass -p redis0${i} scp /tmp/aaa/tool/nmon redis@redis0${i}:/home/redis/bin/; done
```
+ 从仓库将编译好的redis-cli上传至bin中
```shell
for i in {1..6} ; do sshpass -p redis0${i} scp /tmp/aaa/tool/redis-cli redis@redis0${i}:/home/redis/bin/; done
```
+ 配置内核参数：
```shell
cat >> /etc/sysctl.conf <<EOF
vm.overcommit_memory = 1
net.ipv4.tcp_max_syn_backlog = 2048
net.core.somaxconn = 2048
vm.swappiness = 10
EOF
sysctl -p

echo never > /sys/kernel/mm/transparent_hugepage/enabled
cat >>  /etc/rc.local << EOF
echo never > /sys/kernel/mm/transparent_hugepage/enabled
EOF
```
+ 安装依赖包
```shell
  yum -y install glibc* gcc* wget
```

##2、仓库配置
+ 需要安装nginx：http://x.x.x.x/resource  
+ BASE目录：/data/nginx/html/resource

##3、Redis资源管理
+ 按照源地址，上传redis-6.2.7.tar.gz源码包
+ 编译&推送前，需要在机器管理中配置一台机器，用来做make&makeinstall的操作

##4、机器管理:
+ 是否虚机：如果选否，需要配置宿主机IP地址
+ 机器的用户名和密码直接在数据库中维护 machine_info

##5、增加Redis新版本步骤
+ 在资源管理-Redis资源管理-新增资源包，注意资源名称格式，源地址
+ 上传源文件到对应的目录
+ 新增配置模板
```sql
INSERT INTO instance_config
(`config_key`,
`config_value`,
`info`,
`update_time`,
`type`,
`status`,
`version_id`,
`refresh`)
select
`config_key`,
`config_value`,
`info`,
`update_time`,
`type`,
`status`,
"版本号（参考资源手工指定）",
`refresh`
from instance_config where version_id=29;
```

##6 数据迁移
+ 上传的redis-shake无需编译，文件目录结构为：
```text
[root@base tool]# tar zxvf redis-shake-2.1.2-make.tar.gz 
redis-shake-2.1.2/
redis-shake-2.1.2/redis-shake.conf
redis-shake-2.1.2/redis-shake.darwin
redis-shake-2.1.2/redis-shake.linux
redis-shake-2.1.2/redis-shake.windows
```



