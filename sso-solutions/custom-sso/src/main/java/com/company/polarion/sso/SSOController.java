package com.company.polarion.sso;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSO服务端控制器
 * 提供登录、验证、登出等功能
 */
@RestController
@RequestMapping("/sso")
@CrossOrigin(origins = {"http://polarion.company.com", "http://localhost:8080", "http://localhost:8090"})
public class SSOController {
    
    private static final Logger logger = LoggerFactory.getLogger(SSOController.class);
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private AuthenticationService authService;
    
    /**
     * 登录页面
     */
    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect) {
        // 返回登录页面HTML（实际应该使用模板引擎）
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Polarion SSO Login</title>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; }
                    .login-container { 
                        max-width: 400px; 
                        margin: 100px auto; 
                        padding: 20px; 
                        background: white; 
                        border-radius: 5px; 
                        box-shadow: 0 0 10px rgba(0,0,0,0.1);
                    }
                    input { 
                        width: 100%; 
                        padding: 10px; 
                        margin: 10px 0; 
                        border: 1px solid #ddd; 
                        border-radius: 3px;
                    }
                    button { 
                        width: 100%; 
                        padding: 10px; 
                        background: #007bff; 
                        color: white; 
                        border: none; 
                        border-radius: 3px; 
                        cursor: pointer;
                    }
                    button:hover { background: #0056b3; }
                    .error { color: red; margin: 10px 0; }
                </style>
            </head>
            <body>
                <div class="login-container">
                    <h2>Polarion Single Sign-On</h2>
                    <form id="loginForm" onsubmit="doLogin(event)">
                        <input type="text" id="username" placeholder="Username" required>
                        <input type="password" id="password" placeholder="Password" required>
                        <div id="error" class="error"></div>
                        <button type="submit">Login</button>
                    </form>
                </div>
                <script>
                    function doLogin(e) {
                        e.preventDefault();
                        const username = document.getElementById('username').value;
                        const password = document.getElementById('password').value;
                        const redirect = new URLSearchParams(window.location.search).get('redirect') || '/';
                        
                        fetch('/sso/authenticate', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json'},
                            body: JSON.stringify({username, password, redirect})
                        })
                        .then(res => res.json())
                        .then(data => {
                            if (data.success) {
                                window.location.href = data.redirect;
                            } else {
                                document.getElementById('error').textContent = data.message;
                            }
                        })
                        .catch(err => {
                            document.getElementById('error').textContent = 'Login failed';
                        });
                    }
                </script>
            </body>
            </html>
        """;
    }
    
    /**
     * 认证接口
     */
    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticate(@RequestBody LoginRequest request, 
                                        HttpServletResponse response) {
        try {
            // 验证用户凭证
            User user = authService.authenticate(request.getUsername(), request.getPassword());
            
            if (user != null) {
                // 生成Token
                String token = tokenService.generateToken(user);
                
                // 设置Cookie
                Cookie ssoCookie = new Cookie("POLARION_SSO_TOKEN", token);
                ssoCookie.setPath("/");
                ssoCookie.setMaxAge(8 * 60 * 60); // 8小时
                ssoCookie.setHttpOnly(true);
                ssoCookie.setSecure(request.isSecure()); // HTTPS时设置Secure
                response.addCookie(ssoCookie);
                
                // 记录登录日志
                logger.info("User {} logged in successfully", user.getUsername());
                
                // 返回成功响应
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("token", token);
                result.put("redirect", request.getRedirect() != null ? request.getRedirect() : "/");
                result.put("user", createUserInfo(user));
                
                return ResponseEntity.ok(result);
            } else {
                // 认证失败
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "Invalid username or password");
                
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
            }
        } catch (Exception e) {
            logger.error("Authentication error", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "Authentication service error");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
    
    /**
     * Token验证接口
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateToken(@RequestParam String token) {
        try {
            TokenInfo tokenInfo = tokenService.validateToken(token);
            
            if (tokenInfo != null && !tokenInfo.isExpired()) {
                User user = userService.getUserById(tokenInfo.getUserId());
                if (user != null) {
                    return ResponseEntity.ok(createUserInfo(user));
                }
            }
            
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            logger.error("Token validation error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 登出接口
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@CookieValue(name = "POLARION_SSO_TOKEN", required = false) String token,
                                  HttpServletResponse response) {
        try {
            if (token != null) {
                // 使Token失效
                tokenService.invalidateToken(token);
                
                // 清除Cookie
                Cookie ssoCookie = new Cookie("POLARION_SSO_TOKEN", "");
                ssoCookie.setPath("/");
                ssoCookie.setMaxAge(0);
                response.addCookie(ssoCookie);
                
                logger.info("User logged out successfully");
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("redirect", "/sso/login");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Logout error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * 获取用户信息接口
     */
    @GetMapping("/userinfo")
    public ResponseEntity<?> getUserInfo(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @CookieValue(name = "POLARION_SSO_TOKEN", required = false) String cookieToken) {
        String token = null;
        
        // 优先从Header获取Token
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (cookieToken != null) {
            token = cookieToken;
        }
        
        if (token != null) {
            TokenInfo tokenInfo = tokenService.validateToken(token);
            if (tokenInfo != null && !tokenInfo.isExpired()) {
                User user = userService.getUserById(tokenInfo.getUserId());
                if (user != null) {
                    return ResponseEntity.ok(createUserInfo(user));
                }
            }
        }
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    
    /**
     * 刷新Token接口
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody Map<String, String> request) {
        String oldToken = request.get("token");
        
        if (oldToken != null) {
            String newToken = tokenService.refreshToken(oldToken);
            if (newToken != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("token", newToken);
                result.put("expiresAt", Instant.now().plus(8, ChronoUnit.HOURS).toEpochMilli());
                
                return ResponseEntity.ok(result);
            }
        }
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    
    /**
     * 创建返回的用户信息
     */
    private Map<String, Object> createUserInfo(User user) {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("username", user.getUsername());
        userInfo.put("email", user.getEmail());
        userInfo.put("roles", user.getRoles());
        userInfo.put("allowedInstances", user.getAllowedInstances());
        userInfo.put("attributes", user.getAttributes());
        return userInfo;
    }
    
    /**
     * 登录请求类
     */
    public static class LoginRequest {
        private String username;
        private String password;
        private String redirect;
        private boolean rememberMe;
        
        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public String getRedirect() { return redirect; }
        public void setRedirect(String redirect) { this.redirect = redirect; }
        
        public boolean isRememberMe() { return rememberMe; }
        public void setRememberMe(boolean rememberMe) { this.rememberMe = rememberMe; }
    }
}