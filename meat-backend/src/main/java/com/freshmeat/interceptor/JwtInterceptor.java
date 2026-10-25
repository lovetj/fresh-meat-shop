package com.freshmeat.interceptor;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.annotation.RequireLogin;
import com.freshmeat.config.JwtProperties;
import com.freshmeat.entity.User;
import com.freshmeat.exception.BusinessException;
import com.freshmeat.service.TokenSessionService;
import com.freshmeat.util.JwtUtil;
import com.freshmeat.util.SecurityContextUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final TokenSessionService tokenSessionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 进入请求先清理线程上下文：preHandle 抛异常时 afterCompletion 不会被回调，
        // 必须确保上一个请求的身份不会残留在被复用的 Tomcat 线程上
        SecurityContextUtil.clear();
        try {
            return authenticate(request, handler);
        } catch (RuntimeException e) {
            SecurityContextUtil.clear();
            throw e;
        }
    }

    /**
     * 解析 Token 并执行登录态 / 权限校验
     */
    private boolean authenticate(HttpServletRequest request, Object handler) {
        // 如果是跨域预检 OPTIONS 请求，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 提取 Token
        String authHeader = request.getHeader(jwtProperties.getHeader());
        String token = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(jwtProperties.getPrefix())) {
            token = authHeader.substring(jwtProperties.getPrefix().length()).trim();
        }

        if (StringUtils.hasText(token)) {
            Claims claims = jwtUtil.parseToken(token);
            if (claims != null) {
                Object userIdObj = claims.get("userId");
                String userId = userIdObj != null ? String.valueOf(userIdObj) : null;
                if (!StringUtils.hasText(userId) && claims.getSubject() != null) {
                    userId = claims.getSubject();
                }
                Object openidObj = claims.get("openid");
                String openid = openidObj != null ? String.valueOf(openidObj) : null;
                Object roleObj = claims.get("role");
                String role = roleObj != null ? String.valueOf(roleObj) : null;

                // 单设备登录 + 账号状态校验：
                // 1. Token 必须携带会话标识；2. 必须与账号当前有效会话一致；
                // 3. 账号必须为启用状态（禁用后旧 Token 立即失效，不等过期）
                if (!StringUtils.hasText(userId)) {
                    throw new BusinessException(401, "登录状态已失效，请重新登录");
                }
                String sessionKey = claims.get(JwtUtil.CLAIM_SESSION_KEY, String.class);
                if (!StringUtils.hasText(sessionKey)) {
                    // 升级前签发的旧 Token 没有会话标识，按登录失效处理，引导重新登录
                    throw new BusinessException(401, "登录状态已失效，请重新登录");
                }
                User sessionUser = tokenSessionService.getSessionUser(userId);
                if (sessionUser == null) {
                    throw new BusinessException(401, "登录状态已失效，请重新登录");
                }
                if (!sessionKey.equals(sessionUser.getSessionKey())) {
                    throw new BusinessException(4011, "账号已在其他设备登录，请重新登录");
                }
                if (sessionUser.getStatus() == null || sessionUser.getStatus() != 1) {
                    throw new BusinessException(403, "账号已被禁用，请联系客服");
                }

                SecurityContextUtil.setUser(SecurityContextUtil.LoginUser.builder()
                        .userId(userId)
                        .openid(openid)
                        .role(StringUtils.hasText(role) ? role : "CUSTOMER")
                        .build());
            }
        }

        // 如果不是 HandlerMethod，直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireAdmin requireAdmin = findAnnotation(handlerMethod, RequireAdmin.class);
        RequireLogin requireLogin = findAnnotation(handlerMethod, RequireLogin.class);

        // 仅要求登录的接口：统一在此拦截，避免 Service 层漏判
        if (requireLogin != null && SecurityContextUtil.getUserId() == null) {
            throw new BusinessException(401, "请先登录");
        }

        // 需要管理员权限
        if (requireAdmin != null) {
            if (SecurityContextUtil.getUserId() == null) {
                throw new BusinessException(401, "请先登录管理端");
            }
            if (!SecurityContextUtil.isAdmin()) {
                throw new BusinessException(403, "无权访问，仅限店铺管理员使用");
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理线程上下文
        SecurityContextUtil.clear();
    }

    /**
     * 优先取方法上的注解，其次取 Controller 类上的注解
     */
    private <A extends Annotation> A findAnnotation(HandlerMethod handlerMethod, Class<A> annotationType) {
        A annotation = handlerMethod.getMethodAnnotation(annotationType);
        return annotation != null ? annotation : handlerMethod.getBeanType().getAnnotation(annotationType);
    }
}
