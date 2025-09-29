package com.company.polarion.sso;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * 自定义SSO认证过滤器
 * 用于Polarion多实例部署的统一认证
 */
public class SSOAuthenticationFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(SSOAuthenticationFilter.class);
    private static final String SSO_TOKEN_COOKIE = "POLARION_SSO_TOKEN";
    private static final String SSO_SERVICE_URL = "http://localhost:8100/sso";
    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLES_HEADER = "X-Auth-Roles";
    private static final String INSTANCE_HEADER = "X-Polarion-Instance";
    
    private FilterConfig filterConfig;
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
        logger.info("SSO Authentication Filter initialized");
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // 获取当前请求路径
        String requestPath = httpRequest.getRequestURI();
        logger.debug("Processing request: {}", requestPath);
        
        // 排除不需要认证的路径
        if (isExcludedPath(requestPath)) {
            chain.doFilter(request, response);
            return;
        }
        
        // 检查SSO Token
        Optional<String> ssoToken = getSSOToken(httpRequest);
        
        if (ssoToken.isPresent()) {
            // 验证Token
            SSOUserInfo userInfo = validateToken(ssoToken.get());
            
            if (userInfo != null) {
                // Token有效，设置用户信息
                httpRequest.setAttribute("ssoUser", userInfo);
                
                // 设置认证头信息供后端使用
                RequestWrapper wrappedRequest = new RequestWrapper(httpRequest);
                wrappedRequest.addHeader(AUTH_USER_HEADER, userInfo.getUsername());
                wrappedRequest.addHeader(AUTH_ROLES_HEADER, String.join(",", userInfo.getRoles()));
                
                // 检查用户是否有权限访问当前实例
                String currentInstance = getCurrentInstance(httpRequest);
                if (userInfo.getAllowedInstances().contains(currentInstance)) {
                    // 继续处理请求
                    chain.doFilter(wrappedRequest, response);
                    return;
                } else {
                    // 用户无权访问此实例
                    logger.warn("User {} not authorized for instance {}", 
                        userInfo.getUsername(), currentInstance);
                    httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        "Not authorized for this Polarion instance");
                    return;
                }
            }
        }
        
        // 未认证或Token无效，重定向到登录页
        String currentUrl = httpRequest.getRequestURL().toString();
        if (httpRequest.getQueryString() != null) {
            currentUrl += "?" + httpRequest.getQueryString();
        }
        
        String loginUrl = SSO_SERVICE_URL + "/login?redirect=" + 
            java.net.URLEncoder.encode(currentUrl, "UTF-8");
        
        logger.info("Redirecting to login: {}", loginUrl);
        httpResponse.sendRedirect(loginUrl);
    }
    
    /**
     * 从请求中获取SSO Token
     */
    private Optional<String> getSSOToken(HttpServletRequest request) {
        // 首先从Cookie中查找
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            Optional<String> tokenFromCookie = Arrays.stream(cookies)
                .filter(cookie -> SSO_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
            
            if (tokenFromCookie.isPresent()) {
                return tokenFromCookie;
            }
        }
        
        // 然后从Header中查找（用于API调用）
        String tokenFromHeader = request.getHeader("Authorization");
        if (tokenFromHeader != null && tokenFromHeader.startsWith("Bearer ")) {
            return Optional.of(tokenFromHeader.substring(7));
        }
        
        // 最后从请求参数中查找（用于特殊场景）
        String tokenFromParam = request.getParameter("sso_token");
        return Optional.ofNullable(tokenFromParam);
    }
    
    /**
     * 验证SSO Token
     */
    private SSOUserInfo validateToken(String token) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet validateRequest = new HttpGet(SSO_SERVICE_URL + "/validate?token=" + token);
            
            try (CloseableHttpResponse response = httpClient.execute(validateRequest)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    return objectMapper.readValue(responseBody, SSOUserInfo.class);
                }
            }
        } catch (Exception e) {
            logger.error("Error validating SSO token", e);
        }
        
        return null;
    }
    
    /**
     * 获取当前Polarion实例标识
     */
    private String getCurrentInstance(HttpServletRequest request) {
        // 从请求头获取（由Apache设置）
        String instanceFromHeader = request.getHeader(INSTANCE_HEADER);
        if (instanceFromHeader != null) {
            return instanceFromHeader;
        }
        
        // 从URL路径推断
        String path = request.getRequestURI();
        if (path.startsWith("/polarion1")) {
            return "instance1";
        } else if (path.startsWith("/polarion2")) {
            return "instance2";
        }
        
        // 默认实例
        return "instance1";
    }
    
    /**
     * 检查是否是排除路径
     */
    private boolean isExcludedPath(String path) {
        // 静态资源
        if (path.matches(".*\\.(js|css|png|jpg|gif|ico)$")) {
            return true;
        }
        
        // API端点（如果有独立的API认证）
        if (path.startsWith("/polarion/api/public/")) {
            return true;
        }
        
        // 健康检查
        if (path.endsWith("/health") || path.endsWith("/status")) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public void destroy() {
        logger.info("SSO Authentication Filter destroyed");
    }
    
    /**
     * SSO用户信息类
     */
    public static class SSOUserInfo {
        private String username;
        private String email;
        private List<String> roles;
        private List<String> allowedInstances;
        private Map<String, Object> attributes;
        
        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
        
        public List<String> getAllowedInstances() { return allowedInstances; }
        public void setAllowedInstances(List<String> allowedInstances) { 
            this.allowedInstances = allowedInstances; 
        }
        
        public Map<String, Object> getAttributes() { return attributes; }
        public void setAttributes(Map<String, Object> attributes) { 
            this.attributes = attributes; 
        }
    }
    
    /**
     * HTTP请求包装器，用于添加自定义头
     */
    private static class RequestWrapper extends HttpServletRequestWrapper {
        private Map<String, String> customHeaders = new HashMap<>();
        
        public RequestWrapper(HttpServletRequest request) {
            super(request);
        }
        
        public void addHeader(String name, String value) {
            customHeaders.put(name, value);
        }
        
        @Override
        public String getHeader(String name) {
            String customHeader = customHeaders.get(name);
            if (customHeader != null) {
                return customHeader;
            }
            return super.getHeader(name);
        }
        
        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.addAll(customHeaders.keySet());
            return Collections.enumeration(names);
        }
        
        @Override
        public Enumeration<String> getHeaders(String name) {
            String customHeader = customHeaders.get(name);
            if (customHeader != null) {
                return Collections.enumeration(Collections.singletonList(customHeader));
            }
            return super.getHeaders(name);
        }
    }
}