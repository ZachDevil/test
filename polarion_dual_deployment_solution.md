# Polarion双系统部署解决方案

## 问题分析

在同一台服务器上部署两个Polarion系统时，主要面临以下挑战：
1. **Session冲突**：两个系统使用相同的Session ID机制
2. **Cookie冲突**：相同域名下Cookie命名冲突
3. **端口冲突**：多个服务需要不同端口
4. **统一登录**：用户需要无感切换系统

## 解决方案架构

```
[用户] → [Apache反向代理] → [认证服务] → [Polarion-1 / Polarion-2]
                ↓
         [统一Session存储]
```

## 方案一：基于Apache + CAS的单点登录

### 1. 系统架构设计

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Apache HTTPD  │    │   CAS Server     │    │  Redis/Memcached│
│   (端口80/443)  │    │   (端口8443)     │    │  (Session存储)  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                       │                       │
         ├───────────────────────┼───────────────────────┤
         │                       │                       │
┌─────────────────┐    ┌─────────────────┐
│  Polarion-1     │    │  Polarion-2     │
│  (端口8080)     │    │  (端口8081)     │
└─────────────────┘    └─────────────────┘
```

### 2. Apache配置

#### 主配置文件 (httpd.conf)
```apache
# 加载必要模块
LoadModule rewrite_module modules/mod_rewrite.so
LoadModule proxy_module modules/mod_proxy.so
LoadModule proxy_http_module modules/mod_proxy_http.so
LoadModule proxy_ajp_module modules/mod_proxy_ajp.so
LoadModule headers_module modules/mod_headers.so
LoadModule ssl_module modules/mod_ssl.so

# 虚拟主机配置
<VirtualHost *:80>
    ServerName polarion.company.com
    DocumentRoot /var/www/html
    
    # 重定向到HTTPS
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
    
    # CAS认证保护
    LoadModule auth_cas_module modules/mod_auth_cas.so
    
    CASLoginURL https://polarion.company.com:8443/cas/login
    CASValidateURL https://polarion.company.com:8443/cas/validate
    CASCookiePath /tmp/
    CASScope /
    
    # 反向代理配置
    ProxyPreserveHost On
    ProxyRequests Off
    
    # 系统1路由 - 基于URL路径
    <LocationMatch "^/polarion1/">
        AuthType CAS
        Require valid-user
        
        RewriteEngine On
        RewriteRule ^/polarion1/(.*)$ /$1 [PT]
        
        ProxyPass http://localhost:8080/
        ProxyPassReverse http://localhost:8080/
        
        # Session隔离
        Header edit Set-Cookie "^(JSESSIONID=.*)$" "$1; Path=/polarion1/"
        Header edit* Set-Cookie "^(.*)(domain=[^;]+)(.*)" "$1domain=.polarion1.company.com$3"
    </LocationMatch>
    
    # 系统2路由
    <LocationMatch "^/polarion2/">
        AuthType CAS
        Require valid-user
        
        RewriteEngine On
        RewriteRule ^/polarion2/(.*)$ /$1 [PT]
        
        ProxyPass http://localhost:8081/
        ProxyPassReverse http://localhost:8081/
        
        # Session隔离
        Header edit Set-Cookie "^(JSESSIONID=.*)$" "$1; Path=/polarion2/"
        Header edit* Set-Cookie "^(.*)(domain=[^;]+)(.*)" "$1domain=.polarion2.company.com$3"
    </LocationMatch>
    
    # 默认路由到系统选择页面
    DocumentRoot /var/www/html/portal
    DirectoryIndex index.html
</VirtualHost>
```

### 3. CAS Server配置

#### CAS服务器部署 (cas-server.xml)
```xml
<Context>
    <Resource name="jdbc/cas" 
              auth="Container" 
              type="javax.sql.DataSource"
              driverClassName="org.postgresql.Driver"
              url="jdbc:postgresql://localhost:5432/cas_db"
              username="cas_user" 
              password="cas_password"
              maxTotal="20" 
              maxIdle="10" 
              maxWaitMillis="-1"/>
</Context>
```

#### CAS配置 (cas.properties)
```properties
# 服务器配置
server.name=https://polarion.company.com:8443
server.prefix=${server.name}/cas

# 认证配置
cas.authn.accept.users=
cas.authn.jdbc.query[0].sql=SELECT password FROM users WHERE username=?
cas.authn.jdbc.query[0].url=jdbc:postgresql://localhost:5432/polarion_auth
cas.authn.jdbc.query[0].dialect=org.hibernate.dialect.PostgreSQLDialect
cas.authn.jdbc.query[0].user=auth_user
cas.authn.jdbc.query[0].password=auth_password
cas.authn.jdbc.query[0].driverClass=org.postgresql.Driver

# Session配置
cas.ticket.tgt.maxTimeToLiveInSeconds=28800
cas.ticket.st.maxTimeToLiveInSeconds=300

# 服务注册
cas.serviceRegistry.json.location=file:/etc/cas/services
```

### 4. Polarion系统配置修改

#### Polarion-1配置 (polarion.conf)
```properties
# 服务器配置
com.polarion.platform.url=https://polarion.company.com/polarion1
com.polarion.portal.url=https://polarion.company.com/polarion1

# Session配置
session.cookie.name=POLARION1_SESSIONID
session.cookie.path=/polarion1/
session.cookie.domain=.polarion1.company.com

# 集群配置
com.polarion.platform.cluster.enabled=true
com.polarion.platform.cluster.name=polarion-cluster-1
```

#### Polarion-2配置 (polarion.conf)
```properties
# 服务器配置
com.polarion.platform.url=https://polarion.company.com/polarion2
com.polarion.portal.url=https://polarion.company.com/polarion2

# Session配置
session.cookie.name=POLARION2_SESSIONID
session.cookie.path=/polarion2/
session.cookie.domain=.polarion2.company.com

# 集群配置
com.polarion.platform.cluster.enabled=true
com.polarion.platform.cluster.name=polarion-cluster-2
```

## 方案二：基于SAML 2.0的统一认证

### 1. Identity Provider (IdP) 配置

#### 使用SimpleSAMLphp作为IdP
```php
// config/authsources.php
$config = array(
    'admin' => array(
        'core:AdminPassword',
    ),
    
    'polarion-auth' => array(
        'sqlauth:SQL',
        'dsn' => 'pgsql:host=localhost;port=5432;dbname=polarion_auth',
        'username' => 'auth_user',
        'password' => 'auth_password',
        'query' => 'SELECT username, password FROM users WHERE username = :username',
    ),
);
```

#### SAML元数据配置 (metadata/saml20-sp-remote.php)
```php
$metadata['https://polarion.company.com/polarion1'] = array(
    'AssertionConsumerService' => 'https://polarion.company.com/polarion1/saml/acs',
    'SingleLogoutService' => 'https://polarion.company.com/polarion1/saml/slo',
    'NameIDFormat' => 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent',
);

$metadata['https://polarion.company.com/polarion2'] = array(
    'AssertionConsumerService' => 'https://polarion.company.com/polarion2/saml/acs',
    'SingleLogoutService' => 'https://polarion.company.com/polarion2/saml/slo',
    'NameIDFormat' => 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent',
);
```

### 2. Apache + mod_shib配置

#### Shibboleth配置 (shibboleth2.xml)
```xml
<SPConfig xmlns="urn:mace:shibboleth:2.0:native:sp:config">
    <ApplicationDefaults entityID="https://polarion.company.com"
                        REMOTE_USER="eppn persistent-id targeted-id">
        
        <!-- Polarion-1 应用 -->
        <ApplicationOverride id="polarion1" entityID="https://polarion.company.com/polarion1">
            <Sessions lifetime="28800" timeout="3600" 
                     cookieName="SHIB_POLARION1_SESSION"
                     cookieProps="https"
                     relayState="ss:mem"
                     checkAddress="false"
                     handlerSSL="true">
                <SSO entityID="https://polarion.company.com:8443/simplesaml">
                    SAML2 SAML1
                </SSO>
                <Logout>SAML2 Local</Logout>
            </Sessions>
        </ApplicationOverride>
        
        <!-- Polarion-2 应用 -->
        <ApplicationOverride id="polarion2" entityID="https://polarion.company.com/polarion2">
            <Sessions lifetime="28800" timeout="3600" 
                     cookieName="SHIB_POLARION2_SESSION"
                     cookieProps="https"
                     relayState="ss:mem"
                     checkAddress="false"
                     handlerSSL="true">
                <SSO entityID="https://polarion.company.com:8443/simplesaml">
                    SAML2 SAML1
                </SSO>
                <Logout>SAML2 Local</Logout>
            </Sessions>
        </ApplicationOverride>
    </ApplicationDefaults>
</SPConfig>
```

## 方案三：基于JWT的无状态认证

### 1. JWT认证服务

#### JWT认证API (auth-service.py)
```python
from flask import Flask, request, jsonify
from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity
import bcrypt
import psycopg2
from datetime import timedelta

app = Flask(__name__)
app.config['JWT_SECRET_KEY'] = 'your-secret-key'
app.config['JWT_ACCESS_TOKEN_EXPIRES'] = timedelta(hours=8)

jwt = JWTManager(app)

def authenticate_user(username, password):
    conn = psycopg2.connect(
        host="localhost",
        database="polarion_auth",
        user="auth_user",
        password="auth_password"
    )
    cursor = conn.cursor()
    cursor.execute("SELECT password_hash FROM users WHERE username = %s", (username,))
    result = cursor.fetchone()
    conn.close()
    
    if result and bcrypt.checkpw(password.encode('utf-8'), result[0].encode('utf-8')):
        return True
    return False

@app.route('/auth/login', methods=['POST'])
def login():
    username = request.json.get('username')
    password = request.json.get('password')
    
    if authenticate_user(username, password):
        access_token = create_access_token(
            identity=username,
            additional_claims={'systems': ['polarion1', 'polarion2']}
        )
        
        response = jsonify({
            'access_token': access_token,
            'user': username
        })
        
        # 设置HttpOnly Cookie
        response.set_cookie(
            'auth_token',
            access_token,
            httponly=True,
            secure=True,
            samesite='Strict',
            max_age=28800
        )
        
        return response
    else:
        return jsonify({'error': 'Invalid credentials'}), 401

@app.route('/auth/verify', methods=['POST'])
@jwt_required()
def verify():
    current_user = get_jwt_identity()
    return jsonify({'user': current_user, 'valid': True})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8082, ssl_context='adhoc')
```

### 2. Apache Lua脚本认证

#### JWT验证模块 (auth_jwt.lua)
```lua
local jwt = require "resty.jwt"
local cjson = require "cjson"

function authenticate_request(r)
    local token = r.headers_in["Authorization"]
    if not token then
        token = r:getcookie("auth_token")
    end
    
    if not token then
        return 401
    end
    
    -- 验证JWT
    local jwt_obj = jwt:verify("your-secret-key", token)
    if not jwt_obj.valid then
        return 401
    end
    
    -- 设置用户信息到请求头
    r.headers_in["X-Authenticated-User"] = jwt_obj.payload.sub
    r.headers_in["X-User-Systems"] = cjson.encode(jwt_obj.payload.systems)
    
    return 200
end

return {
    authenticate_request = authenticate_request
}
```

### 3. 系统选择门户页面

#### 主页面 (portal/index.html)
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Polarion统一门户</title>
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
        
        .portal-container {
            background: rgba(255, 255, 255, 0.95);
            border-radius: 20px;
            padding: 3rem;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
            max-width: 800px;
            width: 90%;
        }
        
        .portal-header {
            text-align: center;
            margin-bottom: 3rem;
        }
        
        .portal-title {
            color: #333;
            font-size: 2.5rem;
            margin-bottom: 0.5rem;
        }
        
        .portal-subtitle {
            color: #666;
            font-size: 1.1rem;
        }
        
        .systems-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 2rem;
            margin-bottom: 2rem;
        }
        
        .system-card {
            background: white;
            border-radius: 15px;
            padding: 2rem;
            text-align: center;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
            transition: transform 0.3s ease, box-shadow 0.3s ease;
            cursor: pointer;
        }
        
        .system-card:hover {
            transform: translateY(-5px);
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
        }
        
        .system-icon {
            width: 80px;
            height: 80px;
            margin: 0 auto 1rem;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 2rem;
            color: white;
        }
        
        .system1-icon {
            background: linear-gradient(135deg, #667eea, #764ba2);
        }
        
        .system2-icon {
            background: linear-gradient(135deg, #f093fb, #f5576c);
        }
        
        .system-name {
            font-size: 1.5rem;
            color: #333;
            margin-bottom: 0.5rem;
        }
        
        .system-description {
            color: #666;
            line-height: 1.6;
        }
        
        .user-info {
            background: #f8f9fa;
            border-radius: 10px;
            padding: 1rem;
            text-align: center;
            margin-top: 2rem;
        }
        
        .logout-btn {
            background: #dc3545;
            color: white;
            border: none;
            padding: 0.5rem 1rem;
            border-radius: 5px;
            cursor: pointer;
            margin-left: 1rem;
        }
    </style>
</head>
<body>
    <div class="portal-container">
        <div class="portal-header">
            <h1 class="portal-title">Polarion统一门户</h1>
            <p class="portal-subtitle">选择您要访问的系统</p>
        </div>
        
        <div class="systems-grid">
            <div class="system-card" onclick="accessSystem('polarion1')">
                <div class="system-icon system1-icon">
                    📊
                </div>
                <h3 class="system-name">Polarion系统1</h3>
                <p class="system-description">
                    主要业务系统，包含核心项目管理和需求管理功能
                </p>
            </div>
            
            <div class="system-card" onclick="accessSystem('polarion2')">
                <div class="system-icon system2-icon">
                    🚀
                </div>
                <h3 class="system-name">Polarion系统2</h3>
                <p class="system-description">
                    扩展业务系统，包含高级分析和报告功能
                </p>
            </div>
        </div>
        
        <div class="user-info">
            <span>当前用户: <strong id="username">加载中...</strong></span>
            <button class="logout-btn" onclick="logout()">退出登录</button>
        </div>
    </div>
    
    <script>
        // 检查认证状态
        fetch('/auth/verify', {
            method: 'POST',
            credentials: 'include'
        })
        .then(response => {
            if (!response.ok) {
                window.location.href = '/login.html';
                return;
            }
            return response.json();
        })
        .then(data => {
            if (data && data.user) {
                document.getElementById('username').textContent = data.user;
            }
        })
        .catch(() => {
            window.location.href = '/login.html';
        });
        
        function accessSystem(system) {
            // 在新标签页中打开系统
            window.open(`/${system}/`, '_blank');
        }
        
        function logout() {
            fetch('/auth/logout', {
                method: 'POST',
                credentials: 'include'
            })
            .then(() => {
                window.location.href = '/login.html';
            });
        }
    </script>
</body>
</html>
```

## 方案四：基于Redis的共享Session

### 1. Redis配置

#### Redis集群配置 (redis.conf)
```conf
# 基本配置
port 6379
bind 127.0.0.1
protected-mode yes
timeout 300
keepalive 300

# 持久化配置
save 900 1
save 300 10
save 60 10000
rdbcompression yes
rdbchecksum yes
dbfilename dump.rdb

# 内存配置
maxmemory 2gb
maxmemory-policy allkeys-lru

# 集群配置（如果需要高可用）
cluster-enabled yes
cluster-config-file nodes-6379.conf
cluster-node-timeout 15000
```

### 2. Session同步机制

#### Session管理服务 (session-manager.py)
```python
import redis
import json
import uuid
from datetime import datetime, timedelta
from flask import Flask, request, jsonify

app = Flask(__name__)
redis_client = redis.Redis(host='localhost', port=6379, db=0)

class SessionManager:
    def __init__(self):
        self.redis = redis_client
        self.session_timeout = 28800  # 8小时
    
    def create_session(self, user_id, user_data):
        session_id = str(uuid.uuid4())
        session_data = {
            'user_id': user_id,
            'user_data': user_data,
            'created_at': datetime.now().isoformat(),
            'last_accessed': datetime.now().isoformat(),
            'systems': ['polarion1', 'polarion2']
        }
        
        # 存储到Redis
        self.redis.setex(
            f"session:{session_id}",
            self.session_timeout,
            json.dumps(session_data)
        )
        
        # 用户会话映射
        self.redis.setex(
            f"user_session:{user_id}",
            self.session_timeout,
            session_id
        )
        
        return session_id
    
    def get_session(self, session_id):
        session_data = self.redis.get(f"session:{session_id}")
        if session_data:
            data = json.loads(session_data)
            # 更新最后访问时间
            data['last_accessed'] = datetime.now().isoformat()
            self.redis.setex(
                f"session:{session_id}",
                self.session_timeout,
                json.dumps(data)
            )
            return data
        return None
    
    def invalidate_session(self, session_id):
        session_data = self.get_session(session_id)
        if session_data:
            user_id = session_data['user_id']
            self.redis.delete(f"session:{session_id}")
            self.redis.delete(f"user_session:{user_id}")
    
    def sync_session_between_systems(self, session_id, system_id, session_data):
        """在系统间同步Session数据"""
        key = f"session:{session_id}:system:{system_id}"
        self.redis.setex(key, self.session_timeout, json.dumps(session_data))

session_manager = SessionManager()

@app.route('/session/create', methods=['POST'])
def create_session():
    user_id = request.json.get('user_id')
    user_data = request.json.get('user_data', {})
    
    session_id = session_manager.create_session(user_id, user_data)
    return jsonify({'session_id': session_id})

@app.route('/session/<session_id>', methods=['GET'])
def get_session(session_id):
    session_data = session_manager.get_session(session_id)
    if session_data:
        return jsonify(session_data)
    else:
        return jsonify({'error': 'Session not found'}), 404

@app.route('/session/<session_id>/invalidate', methods=['POST'])
def invalidate_session(session_id):
    session_manager.invalidate_session(session_id)
    return jsonify({'status': 'success'})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8083)
```

### 3. Polarion Session Filter

#### Java Session过滤器 (SessionSyncFilter.java)
```java
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import redis.clients.jedis.Jedis;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

public class SessionSyncFilter implements Filter {
    private Jedis jedis;
    private Gson gson;
    private String systemId;
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        jedis = new Jedis("localhost", 6379);
        gson = new Gson();
        systemId = filterConfig.getInitParameter("systemId"); // polarion1 or polarion2
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                        FilterChain chain) throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 获取统一Session ID
        String unifiedSessionId = getUnifiedSessionId(httpRequest);
        
        if (unifiedSessionId != null) {
            // 从Redis获取Session数据
            String sessionData = jedis.get("session:" + unifiedSessionId);
            
            if (sessionData != null) {
                // 解析Session数据
                Map<String, Object> sessionMap = gson.fromJson(sessionData, Map.class);
                
                // 设置到当前请求的Session中
                HttpSession localSession = httpRequest.getSession(true);
                for (Map.Entry<String, Object> entry : sessionMap.entrySet()) {
                    localSession.setAttribute(entry.getKey(), entry.getValue());
                }
                
                // 同步当前系统的Session数据到Redis
                syncSessionToRedis(unifiedSessionId, localSession);
            }
        }
        
        chain.doFilter(request, response);
    }
    
    private String getUnifiedSessionId(HttpServletRequest request) {
        // 从Cookie中获取统一Session ID
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("UNIFIED_SESSION_ID".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
    
    private void syncSessionToRedis(String unifiedSessionId, HttpSession localSession) {
        Map<String, Object> sessionData = new HashMap<>();
        
        // 收集Session属性
        java.util.Enumeration<String> attributeNames = localSession.getAttributeNames();
        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();
            Object attributeValue = localSession.getAttribute(attributeName);
            
            // 只同步可序列化的数据
            if (attributeValue instanceof java.io.Serializable) {
                sessionData.put(attributeName, attributeValue);
            }
        }
        
        // 存储到Redis
        String key = "session:" + unifiedSessionId + ":system:" + systemId;
        jedis.setex(key, 28800, gson.toJson(sessionData));
    }
    
    @Override
    public void destroy() {
        if (jedis != null) {
            jedis.close();
        }
    }
}
```

## 部署配置清单

### 1. 端口分配
```
- Apache HTTP Server: 80, 443
- CAS Server: 8443
- JWT Auth Service: 8082
- Session Manager: 8083
- Polarion-1: 8080
- Polarion-2: 8081
- Redis: 6379
- PostgreSQL: 5432
```

### 2. 目录结构
```
/opt/polarion/
├── apache/
│   ├── conf/
│   │   ├── httpd.conf
│   │   └── ssl.conf
│   └── htdocs/
│       └── portal/
├── cas/
│   ├── webapps/
│   └── conf/
├── polarion1/
│   ├── polarion/
│   └── data/
├── polarion2/
│   ├── polarion/
│   └── data/
├── auth-service/
│   └── app.py
└── session-manager/
    └── app.py
```

### 3. 启动脚本 (start_all.sh)
```bash
#!/bin/bash

# 启动Redis
systemctl start redis

# 启动PostgreSQL
systemctl start postgresql

# 启动认证服务
cd /opt/polarion/auth-service
nohup python3 app.py &

# 启动Session管理服务
cd /opt/polarion/session-manager
nohup python3 app.py &

# 启动CAS服务器
cd /opt/polarion/cas
nohup ./bin/startup.sh &

# 启动Polarion系统1
cd /opt/polarion/polarion1
nohup ./bin/polarion.sh start &

# 启动Polarion系统2
cd /opt/polarion/polarion2
nohup ./bin/polarion.sh start &

# 启动Apache
systemctl start httpd

echo "所有服务已启动"
```

## 监控和维护

### 1. 健康检查脚本 (health_check.sh)
```bash
#!/bin/bash

SERVICES=("redis:6379" "postgresql:5432" "cas:8443" "auth:8082" "session:8083" "polarion1:8080" "polarion2:8081")

for service in "${SERVICES[@]}"; do
    name=${service%%:*}
    port=${service##*:}
    
    if nc -z localhost $port; then
        echo "✓ $name is running on port $port"
    else
        echo "✗ $name is NOT running on port $port"
    fi
done
```

### 2. Session清理脚本 (cleanup_sessions.py)
```python
import redis
import json
from datetime import datetime, timedelta

def cleanup_expired_sessions():
    r = redis.Redis(host='localhost', port=6379, db=0)
    
    # 获取所有session keys
    session_keys = r.keys('session:*')
    
    for key in session_keys:
        session_data = r.get(key)
        if session_data:
            data = json.loads(session_data)
            last_accessed = datetime.fromisoformat(data['last_accessed'])
            
            # 如果超过24小时未访问，删除session
            if datetime.now() - last_accessed > timedelta(hours=24):
                r.delete(key)
                print(f"Cleaned up expired session: {key}")

if __name__ == '__main__':
    cleanup_expired_sessions()
```

这个解决方案提供了多种选择，您可以根据实际需求和技术栈选择最适合的方案。每种方案都解决了Session冲突、Cookie冲突和统一登录的问题，同时考虑了性能和可维护性。