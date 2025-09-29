# Polarion多实例部署解决方案

## 1. 系统架构分析

### 当前架构
```
用户 -> Apache -> Polarion(Java/OSGI) -> SVN(数据存储) + PostgreSQL(索引)
```

### 目标架构
```
用户 -> Apache(反向代理) -> 负载均衡/路由 -> Polarion实例1(端口8080) -> SVN1 + PostgreSQL1
                                           └-> Polarion实例2(端口8090) -> SVN2 + PostgreSQL2
```

## 2. Session/Cookie冲突解决方案

### 2.1 Cookie隔离方案

#### 方案A: 基于路径的Cookie隔离
```apache
# Apache配置
<VirtualHost *:80>
    # Polarion实例1
    ProxyPass /polarion1 http://localhost:8080/polarion
    ProxyPassReverse /polarion1 http://localhost:8080/polarion
    
    # 设置Cookie路径
    ProxyPassReverseCookiePath / /polarion1/
    
    # Polarion实例2
    ProxyPass /polarion2 http://localhost:8090/polarion
    ProxyPassReverse /polarion2 http://localhost:8090/polarion
    
    # 设置Cookie路径
    ProxyPassReverseCookiePath / /polarion2/
</VirtualHost>
```

#### 方案B: 基于域名的Cookie隔离
```apache
# 使用子域名
<VirtualHost *:80>
    ServerName polarion1.company.com
    ProxyPass / http://localhost:8080/polarion
    ProxyPassReverse / http://localhost:8080/polarion
</VirtualHost>

<VirtualHost *:80>
    ServerName polarion2.company.com
    ProxyPass / http://localhost:8090/polarion
    ProxyPassReverse / http://localhost:8090/polarion
</VirtualHost>
```

### 2.2 Session管理方案

#### 方案A: 修改Session Cookie名称
在每个Polarion实例的配置中设置不同的session cookie名称：

```xml
<!-- server.xml for instance 1 -->
<Context sessionCookieName="POLARIONSESSIONID1">
    <!-- 其他配置 -->
</Context>

<!-- server.xml for instance 2 -->
<Context sessionCookieName="POLARIONSESSIONID2">
    <!-- 其他配置 -->
</Context>
```

#### 方案B: 使用Redis共享Session
```java
// 配置Spring Session Redis
@Configuration
@EnableRedisHttpSession
public class SessionConfig {
    @Bean
    public LettuceConnectionFactory connectionFactory() {
        return new LettuceConnectionFactory();
    }
}
```

## 3. 统一登录解决方案

### 3.1 基于CAS的单点登录

#### CAS服务器部署
```bash
# 下载CAS服务器
wget https://github.com/apereo/cas/releases/download/v6.6.0/cas-server-webapp-6.6.0.war

# 部署到独立的Tomcat
cp cas-server-webapp-6.6.0.war /opt/tomcat-cas/webapps/cas.war
```

#### CAS配置
```yaml
# cas.properties
cas.server.name=https://sso.company.com
cas.server.prefix=${cas.server.name}/cas

# 服务注册
cas.serviceRegistry.json.location=file:/etc/cas/services
```

#### Polarion集成CAS
```xml
<!-- web.xml 添加CAS过滤器 -->
<filter>
    <filter-name>CAS Authentication Filter</filter-name>
    <filter-class>org.jasig.cas.client.authentication.AuthenticationFilter</filter-class>
    <init-param>
        <param-name>casServerLoginUrl</param-name>
        <param-value>https://sso.company.com/cas/login</param-value>
    </init-param>
    <init-param>
        <param-name>serverName</param-name>
        <param-value>http://polarion.company.com</param-value>
    </init-param>
</filter>
```

### 3.2 基于OAuth2/OIDC的解决方案

#### 使用Keycloak作为认证服务器
```bash
# 下载并启动Keycloak
wget https://github.com/keycloak/keycloak/releases/download/22.0.0/keycloak-22.0.0.tar.gz
tar -xzf keycloak-22.0.0.tar.gz
cd keycloak-22.0.0
bin/kc.sh start-dev
```

#### Keycloak配置
```json
{
  "realm": "polarion-realm",
  "auth-server-url": "http://localhost:8180/auth",
  "ssl-required": "external",
  "resource": "polarion-client",
  "credentials": {
    "secret": "secret-key"
  }
}
```

### 3.3 基于SAML2的解决方案

#### SimpleSAMLphp作为IdP
```php
// 配置SimpleSAMLphp
$config = [
    'baseurlpath' => 'simplesaml/',
    'certdir' => 'cert/',
    'loggingdir' => 'log/',
    'datadir' => 'data/',
    'metadata.sources' => [
        ['type' => 'flatfile', 'directory' => 'metadata/'],
    ],
];
```

#### Polarion SAML集成
```xml
<!-- saml-config.xml -->
<saml-configuration>
    <identity-provider>
        <entity-id>http://idp.company.com/simplesaml</entity-id>
        <sso-service-url>http://idp.company.com/simplesaml/saml2/idp/SSOService.php</sso-service-url>
        <x509-certificate>MIIDXTCCAkWgAwIBAgIJALm...</x509-certificate>
    </identity-provider>
    <service-provider>
        <entity-id>http://polarion.company.com</entity-id>
        <assertion-consumer-service-url>http://polarion.company.com/saml/SSO</assertion-consumer-service-url>
    </service-provider>
</saml-configuration>
```

### 3.4 自定义轻量级SSO解决方案

#### SSO服务器实现
```java
@RestController
@RequestMapping("/sso")
public class SSOController {
    
    @Autowired
    private TokenService tokenService;
    
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // 验证用户凭证
        if (authenticate(request.getUsername(), request.getPassword())) {
            String token = tokenService.generateToken(request.getUsername());
            return ResponseEntity.ok(new TokenResponse(token));
        }
        return ResponseEntity.status(401).build();
    }
    
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestParam String token) {
        if (tokenService.validateToken(token)) {
            return ResponseEntity.ok(tokenService.getUserInfo(token));
        }
        return ResponseEntity.status(401).build();
    }
}
```

#### Token服务实现
```java
@Service
public class TokenService {
    private final RedisTemplate<String, Object> redisTemplate;
    
    public String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
            "sso:token:" + token, 
            username, 
            30, 
            TimeUnit.MINUTES
        );
        return token;
    }
    
    public boolean validateToken(String token) {
        return redisTemplate.hasKey("sso:token:" + token);
    }
}
```

## 4. Apache统一入口配置

### 4.1 基于路径的路由配置
```apache
<VirtualHost *:80>
    ServerName polarion.company.com
    
    # 加载必要的模块
    LoadModule proxy_module modules/mod_proxy.so
    LoadModule proxy_http_module modules/mod_proxy_http.so
    LoadModule headers_module modules/mod_headers.so
    LoadModule rewrite_module modules/mod_rewrite.so
    
    # SSO认证检查
    RewriteEngine On
    RewriteCond %{HTTP_COOKIE} !sso_token=([^;]+)
    RewriteRule ^/polarion[12]?/(.*)$ /sso/login?redirect=%{REQUEST_URI} [R,L]
    
    # Polarion实例1路由
    <Location /polarion1>
        ProxyPass http://localhost:8080/polarion
        ProxyPassReverse http://localhost:8080/polarion
        
        # 修改响应头中的Cookie路径
        Header edit Set-Cookie "^(.*)Path=/polarion(.*)$" "$1Path=/polarion1$2"
    </Location>
    
    # Polarion实例2路由
    <Location /polarion2>
        ProxyPass http://localhost:8090/polarion
        ProxyPassReverse http://localhost:8090/polarion
        
        # 修改响应头中的Cookie路径
        Header edit Set-Cookie "^(.*)Path=/polarion(.*)$" "$1Path=/polarion2$2"
    </Location>
    
    # SSO服务路由
    <Location /sso>
        ProxyPass http://localhost:8100/sso
        ProxyPassReverse http://localhost:8100/sso
    </Location>
</VirtualHost>
```

### 4.2 基于Header的智能路由
```apache
# 根据项目或用户属性路由到不同实例
RewriteEngine On

# 从SSO服务获取用户信息并路由
RewriteMap project2instance "txt:/etc/apache2/project-mapping.txt"

# 检查X-Project-ID头
RewriteCond %{HTTP:X-Project-ID} ^(.+)$
RewriteRule ^/polarion/(.*)$ http://localhost:${project2instance:%1|8080}/polarion/$1 [P,L]
```

## 5. 实施步骤

### 5.1 环境准备
1. 备份现有Polarion系统
2. 准备第二个Polarion实例的安装目录
3. 配置独立的PostgreSQL数据库实例
4. 配置独立的SVN仓库

### 5.2 Polarion实例配置
```bash
# 实例1配置
export POLARION_HOME=/opt/polarion1
export POLARION_DATA=/data/polarion1
export POLARION_PORT=8080

# 实例2配置
export POLARION_HOME=/opt/polarion2
export POLARION_DATA=/data/polarion2
export POLARION_PORT=8090
```

### 5.3 数据库配置
```sql
-- PostgreSQL实例1
CREATE DATABASE polarion1;
GRANT ALL PRIVILEGES ON DATABASE polarion1 TO polarion_user;

-- PostgreSQL实例2
CREATE DATABASE polarion2;
GRANT ALL PRIVILEGES ON DATABASE polarion2 TO polarion_user;
```

## 6. 监控和维护

### 6.1 健康检查
```apache
# Apache健康检查配置
<Location /health>
    SetHandler server-status
    Require ip 127.0.0.1
</Location>
```

### 6.2 日志配置
```apache
# 分离不同实例的日志
CustomLog /var/log/apache2/polarion1_access.log combined env=polarion1
CustomLog /var/log/apache2/polarion2_access.log combined env=polarion2
```

## 7. 故障排除

### 7.1 Cookie冲突检查
```javascript
// 浏览器控制台检查Cookie
document.cookie.split(';').forEach(function(c) {
    console.log(c.trim());
});
```

### 7.2 Session验证
```bash
# 检查Redis中的Session
redis-cli keys "spring:session:*"
```

## 8. 性能优化建议

1. 使用Apache mod_cache缓存静态资源
2. 配置连接池优化数据库访问
3. 使用CDN分发静态资源
4. 实施负载均衡策略

## 9. 安全考虑

1. 使用HTTPS加密所有通信
2. 实施CSRF防护
3. 配置适当的CORS策略
4. 定期更新安全补丁