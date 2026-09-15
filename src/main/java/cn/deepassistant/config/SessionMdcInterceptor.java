package cn.deepassistant.config;

import cn.deepassistant.util.ConversationMdc;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 非 SSE 接口：从路径里的会话 id 写入 MDC。
 *
 * <p>聊天 / 续跑的 sessionId 在 JSON body 里，拦截器拿不到，由 {@code AssistantController}
 * 在工作线程打开。本拦截器覆盖 {@code GET/DELETE /api/sessions/{id}} 这类路径带 id 的请求。
 */
@Component
public class SessionMdcInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** 把自己注册到 {@code /api/**}。同一实例既是拦截器又是配置器，避免再拆一个 Config 类。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    /**
     * 请求进入 Controller 之前：能解析出 sessionId 就写入当前 HTTP 线程的 MDC。
     * 解析不到（列表、新建、聊天）保持空，后面的日志 SESSION_ID 段为空。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String sessionId = pathSessionId(request.getRequestURI());
        if (sessionId != null) {
            ConversationMdc.open(sessionId);
        }
        return true;
    }

    /**
     * 无论 Controller 成功还是抛异常都清 MDC。Tomcat 线程会复用，不清会把上一个会话的 id 漏到下一次请求。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ConversationMdc.clear();
    }

    /**
     * 从 URI 抽出 {@code /api/sessions/{id}} 的 id。
     *
     * <ul>
     *   <li>{@code /api/sessions/abc-123} → {@code abc-123}</li>
     *   <li>{@code /api/sessions}、{@code /api/sessions/}、带多余路径段的都不算单条会话</li>
     *   <li>query string（{@code ?userId=}）先剥掉再解析</li>
     * </ul>
     */
    static String pathSessionId(String uri) {
        if (uri == null) {
            return null;
        }
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        String prefix = "/api/sessions/";
        int at = path.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        String rest = path.substring(at + prefix.length());
        // 空、或还有下一级路径（例如未来 /sessions/{id}/messages）都不当作 sessionId
        if (rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        return rest;
    }
}
