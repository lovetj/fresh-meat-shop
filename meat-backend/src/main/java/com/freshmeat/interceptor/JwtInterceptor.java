package com.freshmeat.interceptor;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.config.JwtProperties;
import com.freshmeat.exception.BusinessException;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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
        RequireAdmin requireAdmin = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (requireAdmin == null) {
            requireAdmin = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
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
}
