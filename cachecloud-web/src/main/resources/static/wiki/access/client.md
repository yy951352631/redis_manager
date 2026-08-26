# 客户端接入

<a name="access"/>

## 一、<span id="cc1">Java接入方法</span>

**Maven坐标:**
<div>这里以jedis为例说明。除jedis外，还可以使用Lettuce，Redisson，RedisTemplate等不同类型客户端。</div>

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>4.3.2</version>
</dependency>
```

**获取应用连接参数：**

<div style="color: red">IP地址和端口从【实例列表】中获取，哨兵模式只需要连接哨兵地址和端口</div>
<div style="color: red">密码从【应用统计信息】【查看应用密码】按钮获取</div>
<div style="color: red">如果是哨兵集群，masterName从【应用详情】中获取</div>
<div style="color: red">如果是哨兵集群，从【应用详情】【哨兵密码】可以知道是否有配置哨兵密码，密码同Redis应用密码</div>
<br>

**standalone示例代码:**

```
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisPoolDemo {
    
    public static void main(String[] args) {
        // 创建连接池配置对象
        JedisPoolConfig config = new JedisPoolConfig();
        // 设置最大连接数
        config.setMaxTotal(500);
        // 设置最大空闲连接数
        config.setMaxIdle(10);
        // 设置最小空闲连接数
        config.setMinIdle(5);
        // 设置连接池没有连接后客户端的最大等待时间
        config.setMaxWaitMillis(3000);
        
        // 创建JedisPool对象 这里的IP和端口，请查看【实例列表】
        JedisPool jedisPool = new JedisPool(config, "localhost", 6379);
        
        // 获取Jedis对象
        try (Jedis jedis = jedisPool.getResource()) {
            // 执行操作
            jedis.set("hello", "world");
            String value = jedis.get("hello");
            System.out.println(value);
        }
        
        // 关闭连接池
        jedisPool.close();
    }
    
}
```

**sentinel示例代码:**<br>
提示：建议配置哨兵密码，需要使用Jedis 2.9.0+以上版本<br>
JedisSentinelPool sentinelPool = new JedisSentinelPool(masterName, sentinelSet, redisPassword, poolConfig, <span style="color: red">sentinelPassword</span>);

```
import redis.clients.jedis.*;

import java.util.HashSet;
import java.util.Set;

public class RedisSentinelDemo {
    
    public static void main(String[] args) {
        // 配置Sentinel节点
        Set<String> sentinelSet = new HashSet<>();
        sentinelSet.add("localhost:26379");
        sentinelSet.add("localhost:26380");
        sentinelSet.add("localhost:26381");

        // 配置JedisPool
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(500);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);

        String redisPassword = "99db396c7eca9e7ccc5869962679c3c6@_Z"
        String masterName = "mymaster";
        String sentinelPassword = "99db396c7eca9e7ccc5869962679c3c6@_Z" //redis管理平台上哨兵密码同redis密码
        
        // 创建JedisSentinelPool对象，没有哨兵密码
        // JedisSentinelPool sentinelPool = new JedisSentinelPool(masterName, sentinelSet, redisPassword, poolConfig);
        // 创建JedisSentinelPool对象，有哨兵密码
        JedisSentinelPool sentinelPool = new JedisSentinelPool(masterName, sentinelSet, redisPassword, poolConfig, sentinelPassword);

        // 获取Jedis对象
        try (Jedis jedis = sentinelPool.getResource()) {
            // 执行操作
            jedis.set("hello", "world");
            String value = jedis.get("hello");
            System.out.println(value);
        }

        // 关闭连接池
        sentinelPool.close();
    }
    
}
```

**cluster示例代码:**

```
import redis.clients.jedis.*;

import java.util.HashSet;
import java.util.Set;

public class RedisClusterDemo {

    public static void main(String[] args) {
        // 配置集群节点 
        Set<HostAndPort> clusterNodes = new HashSet<>();
        clusterNodes.add(new HostAndPort("localhost", 7000));
        clusterNodes.add(new HostAndPort("localhost", 7001));
        clusterNodes.add(new HostAndPort("localhost", 7002));
        clusterNodes.add(new HostAndPort("localhost", 7003));
        clusterNodes.add(new HostAndPort("localhost", 7004));
        clusterNodes.add(new HostAndPort("localhost", 7005));

        // 配置JedisPool
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(500);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);

        // 创建JedisCluster对象
        JedisCluster cluster = new JedisCluster(clusterNodes, poolConfig);

        // 执行操作
        cluster.set("hello", "world");
        String value = cluster.get("hello");
        System.out.println(value);

        // 关闭JedisCluster对象
        cluster.close();
    }
}
```

## 二、<span id="cc1">Jedis客户端常见问题</span>

**<div>连接池资源耗尽或连接超时</div>**<br>
如果连接池资源耗尽或连接超时，可能会导致Jedis无法获取连接，从而抛出JedisConnectionException异常。为了避免这种情况，我们可以通过以下方式来解决： <br>
<ul>
<li>增加连接池大小：我们可以通过修改JedisPoolConfig对象中的maxTotal和maxIdle属性来增加连接池大小，从而提高并发连接数。</li>  
<li>减少连接池空闲时间：我们可以通过修改JedisPoolConfig对象中的minIdle和minEvictableIdleTimeMillis属性来减少连接池空闲时间，从而让空闲连接被回收更快。</li>
<li>增加连接超时时间：我们可以通过修改JedisPoolConfig对象中的timeout属性来增加连接超时时间，从而避免连接超时。 </li>
</ul>

**<div>Redis命令执行失败</div>**<br>
如果Redis命令执行失败，可能会导致Jedis抛出JedisDataException异常。为了避免这种情况，我们可以通过以下方式来解决： <br>
<ul>
<li>检查Redis命令语法：我们可以通过在Redis客户端中手动执行相同的命令来检查Redis命令语法是否正确。  </li>
<li>检查Redis集群状态：如果使用Redis集群，我们可以通过在Redis Sentinel或Redis Cluster中查看集群状态来检查是否有节点已经下线或不可用。 </li>
<li>检查Jedis版本：如果使用旧版本的Jedis，可能会存在某些Redis命令无法支持的情况。我们可以尝试升级Jedis版本，或者使用命令的低级别API来手动执行命令。</li> 
</ul>

**<div>Jedis连接被关闭</div>** <br>
如果Jedis连接被意外关闭，可能会导致Jedis抛出JedisConnectionException异常。为了避免这种情况，我们可以通过以下方式来解决：<br>
<ul>
<li>检查连接池设置：我们可以通过修改JedisPoolConfig对象中的<span style="color: red">testOnBorrow和testOnReturn</span>属性来检查连接池设置是否正确。这些属性用于在获取连接和返回连接时自动检测连接是否可用。</li>
<li>检查Redis服务器设置：我们可以通过在Redis服务器中查看日志文件来检查是否有网络故障、内存不足或其他问题导致Redis服务崩溃或重新启动。</li>
<li>使用try-with-resources语句：我们可以使用try-with-resources语句来自动关闭Jedis连接，这样可以确保连接在使用完毕后正确关闭并返回到连接池中。</li>
</ul>

