#!/bin/bash
# Polarion自动备份脚本
# 用于定期备份数据库和SVN仓库

# 配置
BACKUP_DIR="/backup"
DB_USER="polarion_user"
DB_PASS="polarion_password"
DB_HOST="localhost"
RETENTION_DAYS=30
DATE=$(date +%Y%m%d_%H%M%S)
LOG_FILE="/var/log/polarion/backup.log"

# 创建日志函数
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a $LOG_FILE
}

# 检查备份目录
check_backup_dir() {
    if [ ! -d "$BACKUP_DIR" ]; then
        mkdir -p "$BACKUP_DIR"
        log "创建备份目录: $BACKUP_DIR"
    fi
}

# 备份数据库
backup_database() {
    log "开始备份数据库..."
    
    # 备份Polarion实例1数据库
    PGPASSWORD=$DB_PASS pg_dump -h $DB_HOST -U $DB_USER -d polarion1_db | gzip > "$BACKUP_DIR/polarion1_db_$DATE.sql.gz"
    if [ $? -eq 0 ]; then
        log "✓ Polarion1数据库备份成功"
    else
        log "✗ Polarion1数据库备份失败"
    fi
    
    # 备份Polarion实例2数据库
    PGPASSWORD=$DB_PASS pg_dump -h $DB_HOST -U $DB_USER -d polarion2_db | gzip > "$BACKUP_DIR/polarion2_db_$DATE.sql.gz"
    if [ $? -eq 0 ]; then
        log "✓ Polarion2数据库备份成功"
    else
        log "✗ Polarion2数据库备份失败"
    fi
}

# 备份SVN仓库
backup_svn() {
    log "开始备份SVN仓库..."
    
    # 备份SVN仓库1
    svnadmin dump /data/svn/polarion1 | gzip > "$BACKUP_DIR/svn_polarion1_$DATE.dump.gz"
    if [ $? -eq 0 ]; then
        log "✓ SVN仓库1备份成功"
    else
        log "✗ SVN仓库1备份失败"
    fi
    
    # 备份SVN仓库2
    svnadmin dump /data/svn/polarion2 | gzip > "$BACKUP_DIR/svn_polarion2_$DATE.dump.gz"
    if [ $? -eq 0 ]; then
        log "✓ SVN仓库2备份成功"
    else
        log "✗ SVN仓库2备份失败"
    fi
}

# 备份配置文件
backup_configs() {
    log "开始备份配置文件..."
    
    # 创建配置备份目录
    CONFIG_BACKUP_DIR="$BACKUP_DIR/configs_$DATE"
    mkdir -p "$CONFIG_BACKUP_DIR"
    
    # 备份Polarion配置
    cp -r /opt/polarion1/etc "$CONFIG_BACKUP_DIR/polarion1_etc"
    cp -r /opt/polarion2/etc "$CONFIG_BACKUP_DIR/polarion2_etc"
    
    # 备份Apache配置
    cp -r /etc/apache2/sites-available/polarion.conf "$CONFIG_BACKUP_DIR/"
    cp /etc/apache2/project-instance-map.txt "$CONFIG_BACKUP_DIR/"
    
    # 打包配置文件
    tar -czf "$BACKUP_DIR/configs_$DATE.tar.gz" -C "$CONFIG_BACKUP_DIR" .
    rm -rf "$CONFIG_BACKUP_DIR"
    
    log "✓ 配置文件备份成功"
}

# 清理旧备份
cleanup_old_backups() {
    log "清理超过 $RETENTION_DAYS 天的旧备份..."
    
    # 统计清理前的文件数
    before_count=$(find "$BACKUP_DIR" -name "*.gz" -mtime +$RETENTION_DAYS | wc -l)
    
    # 删除旧备份
    find "$BACKUP_DIR" -name "*.gz" -mtime +$RETENTION_DAYS -delete
    
    log "清理了 $before_count 个旧备份文件"
}

# 备份统计
backup_statistics() {
    log "=== 备份统计 ==="
    
    # 计算备份大小
    total_size=$(du -sh "$BACKUP_DIR" | awk '{print $1}')
    log "备份目录总大小: $total_size"
    
    # 列出最新的备份文件
    log "最新备份文件:"
    ls -lh "$BACKUP_DIR"/*_$DATE* | awk '{print "  " $9 " (" $5 ")"}' | tee -a $LOG_FILE
    
    # 计算可用磁盘空间
    available_space=$(df -h "$BACKUP_DIR" | awk 'NR==2 {print $4}')
    log "可用磁盘空间: $available_space"
}

# 发送通知（可选）
send_notification() {
    # 如果配置了邮件通知
    if [ ! -z "$NOTIFY_EMAIL" ]; then
        mail -s "Polarion备份完成 - $(date)" $NOTIFY_EMAIL < $LOG_FILE
    fi
}

# 主函数
main() {
    log "========================================="
    log "开始Polarion自动备份"
    log "========================================="
    
    check_backup_dir
    backup_database
    backup_svn
    backup_configs
    cleanup_old_backups
    backup_statistics
    send_notification
    
    log "========================================="
    log "备份完成"
    log "========================================="
}

# 执行主函数
main