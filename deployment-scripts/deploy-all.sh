#!/bin/bash
# Polarion多实例部署脚本
# 用于自动化部署整个系统

set -e

# 配置变量
POLARION_USER="polarion"
POLARION_GROUP="polarion"
INSTALL_DIR="/opt"
DATA_DIR="/data"
LOG_DIR="/var/log/polarion"
BACKUP_DIR="/backup"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查root权限
check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "此脚本需要root权限运行"
        exit 1
    fi
}

# 创建用户
create_user() {
    log_info "创建Polarion用户..."
    if ! id "$POLARION_USER" &>/dev/null; then
        useradd -m -s /bin/bash $POLARION_USER
        log_info "用户 $POLARION_USER 创建成功"
    else
        log_warn "用户 $POLARION_USER 已存在"
    fi
}

# 创建目录结构
create_directories() {
    log_info "创建目录结构..."
    
    directories=(
        "$INSTALL_DIR/polarion1"
        "$INSTALL_DIR/polarion2"
        "$DATA_DIR/polarion1"
        "$DATA_DIR/polarion2"
        "$DATA_DIR/svn/polarion1"
        "$DATA_DIR/svn/polarion2"
        "$LOG_DIR/instance1"
        "$LOG_DIR/instance2"
        "$BACKUP_DIR/polarion1"
        "$BACKUP_DIR/polarion2"
        "/opt/sso"
        "/etc/polarion"
    )
    
    for dir in "${directories[@]}"; do
        mkdir -p "$dir"
        chown $POLARION_USER:$POLARION_GROUP "$dir"
        log_info "创建目录: $dir"
    done
}

# 安装依赖
install_dependencies() {
    log_info "安装系统依赖..."
    
    if [[ -f /etc/redhat-release ]]; then
        # CentOS/RHEL
        yum install -y epel-release
        yum install -y \
            java-11-openjdk \
            postgresql12-server \
            postgresql12-contrib \
            redis \
            httpd \
            mod_ssl \
            subversion \
            git \
            wget \
            curl
    elif [[ -f /etc/debian_version ]]; then
        # Debian/Ubuntu
        apt-get update
        apt-get install -y \
            openjdk-11-jdk \
            postgresql-12 \
            postgresql-contrib-12 \
            redis-server \
            apache2 \
            libapache2-mod-proxy-html \
            libapache2-mod-headers \
            subversion \
            git \
            wget \
            curl
    fi
}

# 配置PostgreSQL
configure_postgresql() {
    log_info "配置PostgreSQL..."
    
    # 初始化数据库（如果需要）
    if [[ -f /etc/redhat-release ]]; then
        postgresql-12-setup initdb
        systemctl start postgresql-12
        systemctl enable postgresql-12
    else
        systemctl start postgresql
        systemctl enable postgresql
    fi
    
    # 创建数据库和用户
    sudo -u postgres psql << EOF
CREATE USER polarion_user WITH PASSWORD 'polarion_password';
CREATE DATABASE polarion1_db OWNER polarion_user;
CREATE DATABASE polarion2_db OWNER polarion_user;
GRANT ALL PRIVILEGES ON DATABASE polarion1_db TO polarion_user;
GRANT ALL PRIVILEGES ON DATABASE polarion2_db TO polarion_user;
EOF
    
    log_info "PostgreSQL配置完成"
}

# 配置Redis
configure_redis() {
    log_info "配置Redis..."
    
    # 备份原配置
    cp /etc/redis/redis.conf /etc/redis/redis.conf.bak
    
    # 修改配置
    sed -i 's/^bind.*/bind 127.0.0.1/' /etc/redis/redis.conf
    sed -i 's/^# maxmemory.*/maxmemory 2gb/' /etc/redis/redis.conf
    sed -i 's/^# maxmemory-policy.*/maxmemory-policy allkeys-lru/' /etc/redis/redis.conf
    
    # 启动Redis
    systemctl start redis
    systemctl enable redis
    
    log_info "Redis配置完成"
}

# 创建SVN仓库
create_svn_repos() {
    log_info "创建SVN仓库..."
    
    svnadmin create $DATA_DIR/svn/polarion1
    svnadmin create $DATA_DIR/svn/polarion2
    
    chown -R apache:apache $DATA_DIR/svn
    
    log_info "SVN仓库创建完成"
}

# 配置Apache
configure_apache() {
    log_info "配置Apache..."
    
    # 启用必要的模块
    if [[ -f /etc/debian_version ]]; then
        a2enmod proxy proxy_http headers rewrite ssl session session_cookie
    fi
    
    # 复制配置文件
    cp /workspace/configs/apache-polarion-config.conf /etc/apache2/sites-available/polarion.conf
    cp /workspace/configs/project-instance-map.txt /etc/apache2/
    
    # 启用站点
    if [[ -f /etc/debian_version ]]; then
        a2ensite polarion.conf
        systemctl restart apache2
    else
        cp /workspace/configs/apache-polarion-config.conf /etc/httpd/conf.d/polarion.conf
        systemctl restart httpd
    fi
    
    log_info "Apache配置完成"
}

# 部署SSO服务
deploy_sso() {
    log_info "部署SSO服务..."
    
    # 这里假设已经编译好了SSO服务jar包
    # 实际使用时需要先编译项目
    
    # 创建systemd服务
    cat > /etc/systemd/system/polarion-sso.service << EOF
[Unit]
Description=Polarion SSO Service
After=network.target

[Service]
Type=simple
User=$POLARION_USER
ExecStart=/usr/bin/java -jar /opt/sso/polarion-sso.jar --server.port=8100
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
    
    systemctl daemon-reload
    # systemctl start polarion-sso
    # systemctl enable polarion-sso
    
    log_info "SSO服务部署完成"
}

# 创建启动脚本
create_startup_scripts() {
    log_info "创建启动脚本..."
    
    # Polarion实例1启动脚本
    cat > /usr/local/bin/polarion1-start.sh << 'EOF'
#!/bin/bash
export POLARION_HOME=/opt/polarion1
export POLARION_DATA=/data/polarion1
cd $POLARION_HOME/bin
./polarion.sh start
EOF
    
    # Polarion实例2启动脚本
    cat > /usr/local/bin/polarion2-start.sh << 'EOF'
#!/bin/bash
export POLARION_HOME=/opt/polarion2
export POLARION_DATA=/data/polarion2
cd $POLARION_HOME/bin
./polarion.sh start
EOF
    
    chmod +x /usr/local/bin/polarion*-start.sh
    
    log_info "启动脚本创建完成"
}

# 创建监控脚本
create_monitoring_scripts() {
    log_info "创建监控脚本..."
    
    cat > /usr/local/bin/polarion-health-check.sh << 'EOF'
#!/bin/bash

# 健康检查脚本
check_service() {
    local service=$1
    local url=$2
    local name=$3
    
    if curl -f -s "$url" > /dev/null; then
        echo "✓ $name is running"
        return 0
    else
        echo "✗ $name is down"
        return 1
    fi
}

echo "=== Polarion Health Check ==="
echo "Time: $(date)"
echo

check_service "polarion1" "http://localhost:8080/polarion/health" "Polarion Instance 1"
check_service "polarion2" "http://localhost:8090/polarion/health" "Polarion Instance 2"
check_service "sso" "http://localhost:8100/sso/health" "SSO Service"
check_service "apache" "http://localhost/server-status" "Apache Server"

echo
echo "=== Database Status ==="
sudo -u postgres psql -c "SELECT datname, pg_database_size(datname) as size FROM pg_database WHERE datname LIKE 'polarion%';"
EOF
    
    chmod +x /usr/local/bin/polarion-health-check.sh
    
    log_info "监控脚本创建完成"
}

# 创建备份脚本
create_backup_scripts() {
    log_info "创建备份脚本..."
    
    cp /workspace/deployment-scripts/backup_polarion.sh /usr/local/bin/
    chmod +x /usr/local/bin/backup_polarion.sh
    
    # 创建cron任务
    echo "0 2 * * * /usr/local/bin/backup_polarion.sh" | crontab -u $POLARION_USER -
    
    log_info "备份脚本创建完成"
}

# 设置防火墙规则
configure_firewall() {
    log_info "配置防火墙..."
    
    if command -v firewall-cmd &> /dev/null; then
        firewall-cmd --permanent --add-service=http
        firewall-cmd --permanent --add-service=https
        firewall-cmd --permanent --add-port=8080/tcp
        firewall-cmd --permanent --add-port=8090/tcp
        firewall-cmd --permanent --add-port=8100/tcp
        firewall-cmd --reload
    elif command -v ufw &> /dev/null; then
        ufw allow 80/tcp
        ufw allow 443/tcp
        ufw allow 8080/tcp
        ufw allow 8090/tcp
        ufw allow 8100/tcp
    fi
    
    log_info "防火墙配置完成"
}

# 主函数
main() {
    log_info "开始Polarion多实例部署..."
    
    check_root
    create_user
    create_directories
    install_dependencies
    configure_postgresql
    configure_redis
    create_svn_repos
    configure_apache
    deploy_sso
    create_startup_scripts
    create_monitoring_scripts
    create_backup_scripts
    configure_firewall
    
    log_info "==================================="
    log_info "Polarion多实例部署完成！"
    log_info "==================================="
    log_info "下一步操作："
    log_info "1. 解压Polarion安装包到 /opt/polarion1 和 /opt/polarion2"
    log_info "2. 复制配置文件到相应目录"
    log_info "3. 运行 polarion1-start.sh 和 polarion2-start.sh 启动实例"
    log_info "4. 访问 http://your-server/polarion 测试"
    log_info "==================================="
}

# 执行主函数
main "$@"