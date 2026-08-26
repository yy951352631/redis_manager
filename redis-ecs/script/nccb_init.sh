useradd -u 2200 redis
mkdir /home/redis/{bin,conf,data,logs,nmon,redis,sh,soft,ssh,tmp}

## 上传nmon，redis-cli 到bin目录

sysctl vm.overcommit_memory=1
echo 2048 >/proc/sys/net/core/somaxconn
echo never >/sys/kernel/mm/transparent_hugepage/enabled
echo never >/sys/kernel/mm/transparent_hugepage/defrag

cat >> /etc/sysctl.conf <<EOF
vm.overcommit_memory = 1
net.ipv4.tcp_max_syn_backlog = 2048
net.core.somaxconn = 2048
vm.swappiness = 10
EOF
sysctl -p

echo "echo never >  /sys/kernel/mm/transparent_hugepage/enabled" >>/etc/rc.d/rc.local
echo "echo never >  /sys/kernel/mm/transparent_hugepage/defrag" >>/etc/rc.d/rc.local

systemctl stop firewalld
systemctl disable firewalld

yum -y install gcc*  glibc* libstdc* wget
