# Polarion多实例部署实施指南

## 前言

本指南详细说明了如何在同一操作系统上部署多个Polarion实例，解决Session/Cookie冲突问题，并实现统一登录。

## 1. 实施前准备

### 1.1 系统要求

- 操作系统：Linux (推荐 CentOS 7+ 或 Ubuntu 18.04+)
- 内存：至少16GB (每个实例建议8GB)
- 磁盘：至少100GB可用空间
- Java：JDK 11或更高版本
- Apache：2.4.x
- PostgreSQL：12.x或更高版本
- Redis：5.x或更高版本（用于Session共享）

### 1.2 备份现有系统

```bash
# 备份数据库
pg_dump -h localhost -U polarion_user -d polarion_db > polarion_backup_$(date +%Y%m%d).sql

# 备份SVN仓库
svnadmin dump /path/to/svn/repo > svn_backup_$(date +%Y%m%d).dump

# 备份Polarion配置
tar -czf polarion_config_backup_$(date +%Y%m%d).tar.gz /opt/polarion/etc
```

## 2. 环境搭建

### 2.1 创建目录结构

```bash
# 创建基础目录
sudo mkdir -p /opt/polarion1
sudo mkdir -p /opt/polarion2
sudo mkdir -p /data/polarion1
sudo mkdir -p /data/polarion2
sudo mkdir -p /data/svn/polarion1
sudo mkdir -p /data/svn/polarion2
sudo mkdir -p /var/log/polarion/instance1
sudo mkdir -p /var/log/polarion/instance2
sudo mkdir -p /backup/polarion1
sudo mkdir -p /backup/polarion2

# 设置权限
sudo chown -R polarion:polarion /opt/polarion*
sudo chown -R polarion:polarion /data/polarion*
sudo chown -R polarion:polarion /data/svn/polarion*
sudo chown -R polarion:polarion /var/log/polarion
sudo chown -R polarion:polarion /backup/polarion*
```

### 2.2 安装和配置PostgreSQL

```bash
# 创建数据库
sudo -u postgres psql << EOF
CREATE DATABASE polarion1_db;
CREATE DATABASE polarion2_db;
CREATE USER polarion_user WITH PASSWORD 'polarion_password';
GRANT ALL PRIVILEGES ON DATABASE polarion1_db TO polarion_user;
GRANT ALL PRIVILEGES ON DATABASE polarion2_db TO polarion_user;
EOF

# 配置PostgreSQL性能参数
sudo vi /etc/postgresql/12/main/postgresql.conf
# 添加以下配置：
# shared_buffers = 4GB
# effective_cache_size = 12GB
# maintenance_work_mem = 1GB
# work_mem = 50MB
# max_connections = 200
```

### 2.3 安装和配置Redis

```bash
# 安装Redis
sudo apt-get install redis-server

# 配置Redis
sudo vi /etc/redis/redis.conf
# 修改以下配置：
# bind 127.0.0.1
# port 6379
# maxmemory 2gb
# maxmemory-policy allkeys-lru

# 启动Redis
sudo systemctl start redis
sudo systemctl enable redis
```

### 2.4 创建SVN仓库

```bash
# 创建SVN仓库
svnadmin create /data/svn/polarion1
svnadmin create /data/svn/polarion2

# 设置权限
sudo chown -R apache:apache /data/svn/polarion*
```

## 3. Polarion实例安装

### 3.1 安装Polarion实例1

```bash
# 解压Polarion安装包
cd /opt/polarion1
tar -xzf polarion-xxx.tar.gz

# 配置环境变量
export POLARION_HOME=/opt/polarion1
export POLARION_DATA=/data/polarion1

# 初始化数据库
cd $POLARION_HOME/bin
./polarion.sh init-db

# 配置实例
cp /workspace/configs/polarion-instance1-config.xml $POLARION_HOME/etc/polarion.xml

# 修改启动脚本端口
vi $POLARION_HOME/bin/polarion.sh
# 修改: -Djetty.port=8080
```

### 3.2 安装Polarion实例2

```bash
# 解压Polarion安装包
cd /opt/polarion2
tar -xzf polarion-xxx.tar.gz

# 配置环境变量
export POLARION_HOME=/opt/polarion2
export POLARION_DATA=/data/polarion2

# 初始化数据库
cd $POLARION_HOME/bin
./polarion.sh init-db

# 配置实例
cp /workspace/configs/polarion-instance2-config.xml $POLARION_HOME/etc/polarion.xml

# 修改启动脚本端口
vi $POLARION_HOME/bin/polarion.sh
# 修改: -Djetty.port=8090
```

## 4. Apache配置

### 4.1 安装Apache模块

```bash
# 启用必要的模块
sudo a2enmod proxy
sudo a2enmod proxy_http
sudo a2enmod headers
sudo a2enmod rewrite
sudo a2enmod ssl
sudo a2enmod session
sudo a2enmod session_cookie
```

### 4.2 配置Apache

```bash
# 复制配置文件
sudo cp /workspace/configs/apache-polarion-config.conf /etc/apache2/sites-available/polarion.conf

# 创建项目映射文件
sudo cp /workspace/configs/project-instance-map.txt /etc/apache2/

# 启用站点
sudo a2ensite polarion.conf

# 重启Apache
sudo systemctl restart apache2
```

## 5. SSO服务部署

### 5.1 选择SSO方案

根据您的需求选择合适的SSO方案：

1. **CAS** - 适合已有CAS基础设施的企业
2. **Keycloak** - 功能丰富，支持多种协议
3. **自定义SSO** - 轻量级，易于定制

### 5.2 部署自定义SSO服务

```bash
# 编译SSO服务
cd /workspace/sso-solutions/custom-sso
mvn clean package

# 部署SSO服务
java -jar target/polarion-sso-1.0.jar --server.port=8100 &

# 或使用systemd服务
sudo vi /etc/systemd/system/polarion-sso.service
```

```ini
[Unit]
Description=Polarion SSO Service
After=network.target

[Service]
Type=simple
User=polarion
ExecStart=/usr/bin/java -jar /opt/sso/polarion-sso.jar --server.port=8100
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl start polarion-sso
sudo systemctl enable polarion-sso
```

## 6. 启动和验证

### 6.1 启动Polarion实例

```bash
# 启动实例1
cd /opt/polarion1/bin
./polarion.sh start

# 启动实例2
cd /opt/polarion2/bin
./polarion.sh start

# 检查日志
tail -f /var/log/polarion/instance1/polarion.log
tail -f /var/log/polarion/instance2/polarion.log
```

### 6.2 验证部署

1. **访问统一入口**
   - 打开浏览器访问 http://polarion.company.com
   - 应该重定向到SSO登录页面

2. **测试登录**
   - 使用测试账号登录
   - 验证可以访问两个实例

3. **验证Cookie隔离**
   ```javascript
   // 在浏览器控制台执行
   document.cookie.split(';').forEach(c => console.log(c.trim()));
   ```

4. **测试项目路由**
   - 访问不同项目，验证路由到正确的实例

## 7. 数据迁移

### 7.1 项目迁移

```bash
# 导出项目数据
cd /opt/polarion1/bin
./polarion.sh export-project --project=PROJECT_ID --output=/tmp/project_export.zip

# 导入到新实例
cd /opt/polarion2/bin
./polarion.sh import-project --file=/tmp/project_export.zip
```

### 7.2 用户和权限迁移

```sql
-- 导出用户数据
COPY (SELECT * FROM users WHERE project_id IN ('projectX', 'projectY')) 
TO '/tmp/users_export.csv' CSV HEADER;

-- 导入到新实例
COPY users FROM '/tmp/users_export.csv' CSV HEADER;
```

## 8. 监控和维护

### 8.1 健康检查脚本

```bash
#!/bin/bash
# health_check.sh

# 检查Polarion实例1
curl -f http://localhost:8080/polarion/health || echo "Instance 1 is down"

# 检查Polarion实例2
curl -f http://localhost:8090/polarion/health || echo "Instance 2 is down"

# 检查SSO服务
curl -f http://localhost:8100/sso/health || echo "SSO service is down"

# 检查数据库连接
psql -h localhost -U polarion_user -d polarion1_db -c "SELECT 1" || echo "DB1 connection failed"
psql -h localhost -U polarion_user -d polarion2_db -c "SELECT 1" || echo "DB2 connection failed"
```

### 8.2 日志轮转配置

```bash
# /etc/logrotate.d/polarion
/var/log/polarion/*/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 0644 polarion polarion
    sharedscripts
    postrotate
        /bin/kill -USR1 `cat /var/run/polarion1.pid 2>/dev/null` 2>/dev/null || true
        /bin/kill -USR1 `cat /var/run/polarion2.pid 2>/dev/null` 2>/dev/null || true
    endscript
}
```

### 8.3 备份脚本

```bash
#!/bin/bash
# backup_polarion.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/backup"

# 备份数据库
pg_dump -h localhost -U polarion_user polarion1_db | gzip > $BACKUP_DIR/polarion1_db_$DATE.sql.gz
pg_dump -h localhost -U polarion_user polarion2_db | gzip > $BACKUP_DIR/polarion2_db_$DATE.sql.gz

# 备份SVN
svnadmin dump /data/svn/polarion1 | gzip > $BACKUP_DIR/svn_polarion1_$DATE.dump.gz
svnadmin dump /data/svn/polarion2 | gzip > $BACKUP_DIR/svn_polarion2_$DATE.dump.gz

# 清理旧备份
find $BACKUP_DIR -name "*.gz" -mtime +30 -delete
```

## 9. 故障排除

### 9.1 Session冲突问题

如果仍然遇到Session冲突：
1. 检查Cookie名称是否正确配置
2. 验证Apache的Cookie路径重写规则
3. 清除浏览器Cookie后重试

### 9.2 SSO登录失败

1. 检查SSO服务日志
2. 验证网络连接
3. 检查用户权限配置

### 9.3 性能问题

1. 检查数据库索引
2. 调整JVM内存参数
3. 启用查询缓存

## 10. 性能优化建议

### 10.1 JVM调优

```bash
# 在polarion.sh中添加
JAVA_OPTS="-Xms4g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### 10.2 数据库优化

```sql
-- 创建必要的索引
CREATE INDEX idx_project_id ON work_items(project_id);
CREATE INDEX idx_created_date ON work_items(created_date);
CREATE INDEX idx_modified_date ON work_items(modified_date);
```

### 10.3 Apache优化

```apache
# 启用压缩
<IfModule mod_deflate.c>
    SetOutputFilter DEFLATE
    SetEnvIfNoCase Request_URI \.(?:gif|jpe?g|png)$ no-gzip dont-vary
</IfModule>

# 启用缓存
<IfModule mod_cache.c>
    CacheEnable disk /
    CacheRoot /var/cache/apache2/mod_cache_disk
</IfModule>
```

## 结语

通过本指南，您应该能够成功部署Polarion多实例系统，实现Session/Cookie隔离和统一登录。如有问题，请参考故障排除章节或联系技术支持。