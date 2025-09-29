# Polarion双系统部署检查清单

## 前置准备

### 环境检查
- [ ] 操作系统版本确认 (推荐CentOS 7+/Ubuntu 18+)
- [ ] Java版本确认 (OpenJDK 8或11)
- [ ] Python 3.7+环境
- [ ] 可用内存 ≥ 16GB
- [ ] 可用磁盘空间 ≥ 500GB
- [ ] 网络端口可用性检查

### 软件依赖安装
```bash
# 安装Apache HTTP Server
yum install httpd mod_ssl mod_rewrite -y
# 或
apt-get install apache2 apache2-utils -y

# 安装Redis
yum install redis -y
systemctl enable redis

# 安装PostgreSQL
yum install postgresql postgresql-server -y
postgresql-setup initdb
systemctl enable postgresql

# 安装Python依赖
pip3 install flask flask-jwt-extended redis psycopg2-binary bcrypt

# 安装Java 8/11
yum install java-11-openjdk-devel -y
```

## 数据库配置

### PostgreSQL初始化
```sql
-- 创建认证数据库
CREATE DATABASE polarion_auth;
CREATE USER auth_user WITH PASSWORD 'secure_password_here';
GRANT ALL PRIVILEGES ON DATABASE polarion_auth TO auth_user;

-- 创建用户表
\c polarion_auth;
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    full_name VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

-- 创建用户权限表
CREATE TABLE user_permissions (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    system_name VARCHAR(20) NOT NULL, -- 'polarion1' or 'polarion2'
    permission_level VARCHAR(20) NOT NULL, -- 'read', 'write', 'admin'
    granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插入示例用户
INSERT INTO users (username, password_hash, email, full_name) VALUES 
('admin', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LeD.71jS4LM0.3hZO', 'admin@company.com', '系统管理员'),
('user1', '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LeD.71jS4LM0.3hZO', 'user1@company.com', '普通用户1');

-- 插入权限
INSERT INTO user_permissions (user_id, system_name, permission_level) VALUES 
(1, 'polarion1', 'admin'),
(1, 'polarion2', 'admin'),
(2, 'polarion1', 'write'),
(2, 'polarion2', 'read');
```

## 系统配置步骤

### 1. Polarion系统1配置
```bash
# 复制Polarion安装包
cp -r /opt/polarion-original /opt/polarion/polarion1

# 修改配置文件
cd /opt/polarion/polarion1/polarion/configuration
```

编辑 `polarion.conf`:
```properties
# 端口配置
com.polarion.platform.jetty.http.port=8080
com.polarion.platform.jetty.https.port=8443

# URL配置
com.polarion.platform.url=https://polarion.company.com/polarion1
com.polarion.portal.url=https://polarion.company.com/polarion1

# SVN配置
com.polarion.platform.repository.url=file:///opt/polarion/polarion1/data/svn

# 数据库配置
com.polarion.platform.persistence.database.url=jdbc:postgresql://localhost:5432/polarion1_db
com.polarion.platform.persistence.database.user=polarion1_user
com.polarion.platform.persistence.database.password=polarion1_password

# Session配置
session.cookie.name=POLARION1_SESSIONID
session.cookie.path=/polarion1/
session.cookie.domain=.company.com
```

### 2. Polarion系统2配置
```bash
# 复制Polarion安装包
cp -r /opt/polarion-original /opt/polarion/polarion2

# 修改配置文件
cd /opt/polarion/polarion2/polarion/configuration
```

编辑 `polarion.conf`:
```properties
# 端口配置
com.polarion.platform.jetty.http.port=8081
com.polarion.platform.jetty.https.port=8444

# URL配置
com.polarion.platform.url=https://polarion.company.com/polarion2
com.polarion.portal.url=https://polarion.company.com/polarion2

# SVN配置
com.polarion.platform.repository.url=file:///opt/polarion/polarion2/data/svn

# 数据库配置
com.polarion.platform.persistence.database.url=jdbc:postgresql://localhost:5432/polarion2_db
com.polarion.platform.persistence.database.user=polarion2_user
com.polarion.platform.persistence.database.password=polarion2_password

# Session配置
session.cookie.name=POLARION2_SESSIONID
session.cookie.path=/polarion2/
session.cookie.domain=.company.com
```

### 3. Apache配置
编辑 `/etc/httpd/conf/httpd.conf`:
```apache
# 加载必要模块
LoadModule rewrite_module modules/mod_rewrite.so
LoadModule proxy_module modules/mod_proxy.so
LoadModule proxy_http_module modules/mod_proxy_http.so
LoadModule headers_module modules/mod_headers.so
LoadModule ssl_module modules/mod_ssl.so

# 虚拟主机配置
Include conf.d/*.conf
```

创建 `/etc/httpd/conf.d/polarion.conf`:
```apache
<VirtualHost *:80>
    ServerName polarion.company.com
    RewriteEngine On
    RewriteCond %{HTTPS} off
    RewriteRule ^(.*)$ https://%{HTTP_HOST}%{REQUEST_URI} [R=301,L]
</VirtualHost>

<VirtualHost *:443>
    ServerName polarion.company.com
    
    # SSL配置
    SSLEngine on
    SSLCertificateFile /etc/ssl/certs/polarion.crt
    SSLCertificateKeyFile /etc/ssl/private/polarion.key
    
    # 静态文件服务
    DocumentRoot /var/www/html/polarion-portal
    DirectoryIndex index.html
    
    # 反向代理配置
    ProxyPreserveHost On
    ProxyRequests Off
    
    # 认证服务代理
    ProxyPass /auth/ http://localhost:8082/auth/
    ProxyPassReverse /auth/ http://localhost:8082/auth/
    
    # Session管理服务代理
    ProxyPass /session/ http://localhost:8083/session/
    ProxyPassReverse /session/ http://localhost:8083/session/
    
    # Polarion系统1代理
    <LocationMatch "^/polarion1/">
        # 认证检查
        RewriteEngine On
        RewriteCond %{HTTP_COOKIE} !auth_token=
        RewriteRule ^/polarion1/(.*)$ /login.html [R=302,L]
        
        # 移除路径前缀
        RewriteRule ^/polarion1/(.*)$ /$1 [PT]
        
        ProxyPass http://localhost:8080/
        ProxyPassReverse http://localhost:8080/
        
        # Cookie路径修改
        Header edit Set-Cookie "^(POLARION1_SESSIONID=.*)$" "$1; Path=/polarion1/; HttpOnly; Secure"
    </LocationMatch>
    
    # Polarion系统2代理
    <LocationMatch "^/polarion2/">
        # 认证检查
        RewriteEngine On
        RewriteCond %{HTTP_COOKIE} !auth_token=
        RewriteRule ^/polarion2/(.*)$ /login.html [R=302,L]
        
        # 移除路径前缀
        RewriteRule ^/polarion2/(.*)$ /$1 [PT]
        
        ProxyPass http://localhost:8081/
        ProxyPassReverse http://localhost:8081/
        
        # Cookie路径修改
        Header edit Set-Cookie "^(POLARION2_SESSIONID=.*)$" "$1; Path=/polarion2/; HttpOnly; Secure"
    </LocationMatch>
    
    # 错误页面
    ErrorDocument 401 /login.html
    ErrorDocument 403 /unauthorized.html
</VirtualHost>
```

### 4. 创建SSL证书
```bash
# 创建自签名证书（测试用）
mkdir -p /etc/ssl/certs /etc/ssl/private

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/ssl/private/polarion.key \
    -out /etc/ssl/certs/polarion.crt \
    -subj "/C=CN/ST=Beijing/L=Beijing/O=Company/CN=polarion.company.com"

chmod 600 /etc/ssl/private/polarion.key
chmod 644 /etc/ssl/certs/polarion.crt
```

### 5. 创建认证服务
创建 `/opt/polarion/auth-service/app.py`:
```python
from flask import Flask, request, jsonify, make_response
from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity
import bcrypt
import psycopg2
from datetime import timedelta
import logging

app = Flask(__name__)
app.config['JWT_SECRET_KEY'] = 'your-super-secret-jwt-key-change-this'
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=8)

jwt = JWTManager(app)
logging.basicConfig(level=logging.INFO)

# 数据库连接配置
DB_CONFIG = {
    'host': 'localhost',
    'database': 'polarion_auth',
    'user': 'auth_user',
    'password': 'secure_password_here'
}

def get_db_connection():
    return psycopg2.connect(**DB_CONFIG)

def authenticate_user(username, password):
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute("""
            SELECT id, password_hash, full_name, email 
            FROM users 
            WHERE username = %s AND is_active = TRUE
        """, (username,))
        result = cursor.fetchone()
        
        if result and bcrypt.checkpw(password.encode('utf-8'), result[1].encode('utf-8')):
            # 更新最后登录时间
            cursor.execute("""
                UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = %s
            """, (result[0],))
            conn.commit()
            
            # 获取用户权限
            cursor.execute("""
                SELECT system_name, permission_level 
                FROM user_permissions 
                WHERE user_id = %s
            """, (result[0],))
            permissions = dict(cursor.fetchall())
            
            return {
                'id': result[0],
                'username': username,
                'full_name': result[2],
                'email': result[3],
                'permissions': permissions
            }
    except Exception as e:
        app.logger.error(f"Authentication error: {e}")
        return None
    finally:
        if conn:
            conn.close()
    
    return None

@app.route('/auth/login', methods=['POST'])
def login():
    data = request.get_json()
    username = data.get('username')
    password = data.get('password')
    
    if not username or not password:
        return jsonify({'error': 'Username and password required'}), 400
    
    user_data = authenticate_user(username, password)
    
    if user_data:
        access_token = create_access_token(
            identity=username,
            additional_claims={
                'user_id': user_data['id'],
                'full_name': user_data['full_name'],
                'email': user_data['email'],
                'permissions': user_data['permissions']
            }
        )
        
        response = make_response(jsonify({
            'status': 'success',
            'user': {
                'username': username,
                'full_name': user_data['full_name'],
                'email': user_data['email'],
                'permissions': user_data['permissions']
            }
        }))
        
        # 设置HttpOnly Cookie
        response.set_cookie(
            'auth_token',
            access_token,
            httponly=True,
            secure=True,
            samesite='Strict',
            max_age=28800,  # 8小时
            path='/'
        )
        
        app.logger.info(f"User {username} logged in successfully")
        return response
    else:
        app.logger.warning(f"Failed login attempt for user {username}")
        return jsonify({'error': 'Invalid credentials'}), 401

@app.route('/auth/verify', methods=['POST'])
@jwt_required()
def verify():
    current_user = get_jwt_identity()
    return jsonify({'user': current_user, 'valid': True})

@app.route('/auth/logout', methods=['POST'])
def logout():
    response = make_response(jsonify({'status': 'logged out'}))
    response.set_cookie('auth_token', '', expires=0, httponly=True, secure=True)
    return response

@app.route('/auth/health', methods=['GET'])
def health():
    return jsonify({'status': 'healthy', 'service': 'auth'})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8082, debug=False)
```

### 6. 创建前端页面

创建登录页面 `/var/www/html/polarion-portal/login.html`:
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Polarion统一登录</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
        }
        
        .login-container {
            background: rgba(255, 255, 255, 0.95);
            border-radius: 20px;
            padding: 3rem;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
            max-width: 400px;
            width: 90%;
        }
        
        .login-header {
            text-align: center;
            margin-bottom: 2rem;
        }
        
        .login-title {
            color: #333;
            font-size: 2rem;
            margin-bottom: 0.5rem;
        }
        
        .login-subtitle {
            color: #666;
            font-size: 1rem;
        }
        
        .form-group {
            margin-bottom: 1.5rem;
        }
        
        .form-label {
            display: block;
            color: #555;
            margin-bottom: 0.5rem;
            font-weight: 500;
        }
        
        .form-input {
            width: 100%;
            padding: 0.75rem;
            border: 2px solid #e1e1e1;
            border-radius: 10px;
            font-size: 1rem;
            transition: border-color 0.3s ease;
        }
        
        .form-input:focus {
            outline: none;
            border-color: #667eea;
            box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
        }
        
        .login-button {
            width: 100%;
            padding: 0.75rem;
            background: linear-gradient(135deg, #667eea, #764ba2);
            color: white;
            border: none;
            border-radius: 10px;
            font-size: 1rem;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.3s ease, box-shadow 0.3s ease;
        }
        
        .login-button:hover {
            transform: translateY(-2px);
            box-shadow: 0 10px 20px rgba(102, 126, 234, 0.3);
        }
        
        .login-button:disabled {
            opacity: 0.6;
            cursor: not-allowed;
            transform: none;
        }
        
        .error-message {
            background: #ffe6e6;
            color: #d63384;
            padding: 0.75rem;
            border-radius: 8px;
            margin-bottom: 1rem;
            border-left: 4px solid #d63384;
            display: none;
        }
        
        .loading {
            display: none;
            text-align: center;
            margin-top: 1rem;
        }
        
        .spinner {
            border: 2px solid #f3f3f3;
            border-top: 2px solid #667eea;
            border-radius: 50%;
            width: 20px;
            height: 20px;
            animation: spin 1s linear infinite;
            display: inline-block;
            margin-right: 0.5rem;
        }
        
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
    </style>
</head>
<body>
    <div class="login-container">
        <div class="login-header">
            <h1 class="login-title">Polarion</h1>
            <p class="login-subtitle">统一登录系统</p>
        </div>
        
        <div class="error-message" id="errorMessage"></div>
        
        <form id="loginForm">
            <div class="form-group">
                <label class="form-label" for="username">用户名</label>
                <input type="text" id="username" name="username" class="form-input" required>
            </div>
            
            <div class="form-group">
                <label class="form-label" for="password">密码</label>
                <input type="password" id="password" name="password" class="form-input" required>
            </div>
            
            <button type="submit" class="login-button" id="loginButton">
                登录
            </button>
        </form>
        
        <div class="loading" id="loading">
            <div class="spinner"></div>
            正在登录...
        </div>
    </div>
    
    <script>
        document.getElementById('loginForm').addEventListener('submit', async function(e) {
            e.preventDefault();
            
            const username = document.getElementById('username').value;
            const password = document.getElementById('password').value;
            const loginButton = document.getElementById('loginButton');
            const loading = document.getElementById('loading');
            const errorMessage = document.getElementById('errorMessage');
            
            // 显示加载状态
            loginButton.disabled = true;
            loading.style.display = 'block';
            errorMessage.style.display = 'none';
            
            try {
                const response = await fetch('/auth/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ username, password }),
                    credentials: 'include'
                });
                
                const data = await response.json();
                
                if (response.ok) {
                    // 登录成功，跳转到主页
                    window.location.href = '/';
                } else {
                    // 显示错误信息
                    errorMessage.textContent = data.error || '登录失败';
                    errorMessage.style.display = 'block';
                }
            } catch (error) {
                errorMessage.textContent = '网络错误，请稍后重试';
                errorMessage.style.display = 'block';
            } finally {
                // 恢复按钮状态
                loginButton.disabled = false;
                loading.style.display = 'none';
            }
        });
        
        // 检查是否已经登录
        window.addEventListener('load', async function() {
            try {
                const response = await fetch('/auth/verify', {
                    method: 'POST',
                    credentials: 'include'
                });
                
                if (response.ok) {
                    // 已经登录，跳转到主页
                    window.location.href = '/';
                }
            } catch (error) {
                // 忽略错误，继续显示登录页面
            }
        });
    </script>
</body>
</html>
```

## 服务启动顺序

### 启动脚本 `/opt/polarion/start_services.sh`
```bash
#!/bin/bash

echo "启动Polarion双系统部署..."

# 1. 启动基础服务
echo "启动Redis..."
systemctl start redis
systemctl enable redis

echo "启动PostgreSQL..."
systemctl start postgresql
systemctl enable postgresql

# 等待数据库启动
sleep 5

# 2. 启动认证服务
echo "启动认证服务..."
cd /opt/polarion/auth-service
nohup python3 app.py > auth.log 2>&1 &
echo $! > auth.pid

# 等待认证服务启动
sleep 3

# 3. 启动Polarion系统
echo "启动Polarion系统1..."
cd /opt/polarion/polarion1
nohup ./bin/polarion.sh start > polarion1.log 2>&1 &

echo "启动Polarion系统2..."
cd /opt/polarion/polarion2
nohup ./bin/polarion.sh start > polarion2.log 2>&1 &

# 等待Polarion系统启动
sleep 30

# 4. 启动Apache
echo "启动Apache HTTP Server..."
systemctl start httpd
systemctl enable httpd

echo "所有服务启动完成！"
echo "访问地址: https://polarion.company.com"

# 检查服务状态
echo "服务状态检查:"
systemctl status redis --no-pager -l
systemctl status postgresql --no-pager -l
systemctl status httpd --no-pager -l

# 检查端口
echo "端口监听状态:"
netstat -tlnp | grep -E ':(80|443|6379|5432|8080|8081|8082)' || ss -tlnp | grep -E ':(80|443|6379|5432|8080|8081|8082)'
```

### 停止脚本 `/opt/polarion/stop_services.sh`
```bash
#!/bin/bash

echo "停止Polarion双系统部署..."

# 停止Apache
echo "停止Apache..."
systemctl stop httpd

# 停止Polarion系统
echo "停止Polarion系统..."
cd /opt/polarion/polarion1
./bin/polarion.sh stop

cd /opt/polarion/polarion2
./bin/polarion.sh stop

# 停止认证服务
echo "停止认证服务..."
if [ -f /opt/polarion/auth-service/auth.pid ]; then
    kill $(cat /opt/polarion/auth-service/auth.pid)
    rm /opt/polarion/auth-service/auth.pid
fi

# 停止基础服务（可选）
# systemctl stop redis
# systemctl stop postgresql

echo "服务停止完成！"
```

## 验证和测试

### 功能测试清单
- [ ] 用户可以正常登录
- [ ] 登录后可以访问系统选择页面
- [ ] 可以正常访问Polarion系统1
- [ ] 可以正常访问Polarion系统2
- [ ] Session在两个系统间不冲突
- [ ] Cookie路径隔离正常工作
- [ ] 退出登录功能正常
- [ ] 权限控制功能正常

### 性能测试
- [ ] 并发用户登录测试
- [ ] 系统间切换性能测试
- [ ] Session同步性能测试
- [ ] 内存使用监控

### 安全测试
- [ ] Session劫持防护
- [ ] CSRF防护
- [ ] SQL注入防护
- [ ] XSS防护
- [ ] HTTPS强制重定向

## 故障排查

### 常见问题及解决方案

1. **服务启动失败**
   ```bash
   # 检查日志
   tail -f /var/log/httpd/error_log
   tail -f /opt/polarion/auth-service/auth.log
   
   # 检查端口占用
   netstat -tlnp | grep :8080
   ```

2. **认证失败**
   ```bash
   # 检查数据库连接
   psql -h localhost -U auth_user -d polarion_auth
   
   # 检查用户数据
   SELECT * FROM users WHERE username='testuser';
   ```

3. **Session问题**
   ```bash
   # 检查Redis连接
   redis-cli ping
   
   # 查看Session数据
   redis-cli keys "session:*"
   ```

4. **代理问题**
   ```bash
   # 检查Apache配置
   httpd -t
   
   # 检查代理状态
   curl -I http://localhost:8080
   ```

### 日志文件位置
- Apache: `/var/log/httpd/`
- Polarion1: `/opt/polarion/polarion1/polarion/logs/`
- Polarion2: `/opt/polarion/polarion2/polarion/logs/`
- 认证服务: `/opt/polarion/auth-service/auth.log`
- Redis: `/var/log/redis/redis.log`
- PostgreSQL: `/var/lib/pgsql/data/log/`

## 维护和监控

### 定期维护任务
- [ ] 清理过期Session (每天)
- [ ] 备份数据库 (每天)
- [ ] 清理日志文件 (每周)
- [ ] 更新SSL证书 (按需)
- [ ] 安全补丁更新 (每月)

### 监控指标
- 系统资源使用率 (CPU, 内存, 磁盘)
- 服务可用性
- 响应时间
- 并发用户数
- 错误率
- Session数量

这个检查清单确保您能够系统性地完成Polarion双系统部署，并提供了详细的验证和故障排查指南。