package app.cardrail.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /internal/**}: calls between parties' own services must carry the internal key. The public edge
 * never forwards {@code /internal}, so the key is not the only thing in the way.
 */
public class InternalKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Key";

    private final byte[] key;

    public InternalKeyFilter(String key) {
        if (key == null || key.length() < 16) {
            throw new IllegalStateException("the internal key must be set and at least 16 characters");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"code\":\"unauthorised\",\"detail\":\"this is an internal endpoint\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
