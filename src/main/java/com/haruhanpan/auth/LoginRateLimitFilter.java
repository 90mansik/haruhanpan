package com.haruhanpan.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 로그인 요청(POST /api/login)이 시도 제한에 걸렸으면 인증 전에 429 로 끊는다. */
public class LoginRateLimitFilter extends OncePerRequestFilter {

	private final LoginAttemptService attempts;

	public LoginRateLimitFilter(LoginAttemptService attempts) {
		this.attempts = attempts;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !("POST".equals(request.getMethod()) && "/api/login".equals(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (attempts.isBlocked(request.getRemoteAddr())) {
			JsonResponses.write(response, 429, "too many attempts");
			return;
		}
		chain.doFilter(request, response);
	}
}
