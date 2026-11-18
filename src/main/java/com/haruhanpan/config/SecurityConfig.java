package com.haruhanpan.config;

import com.haruhanpan.auth.HaruRememberMeServices;
import com.haruhanpan.auth.JsonResponses;
import com.haruhanpan.auth.LoginAttemptService;
import com.haruhanpan.auth.LoginRateLimitFilter;
import com.haruhanpan.auth.RememberKey;
import com.haruhanpan.google.GoogleLoginService;
import com.haruhanpan.google.GoogleLoginSuccessHandler;
import com.haruhanpan.google.GoogleSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices.RememberMeTokenAlgorithm;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인·접근 제어·보안 헤더.
 * <ul>
 *   <li>사용자는 users 테이블 (AccountUserDetailsService). 가입은 초대 링크로만</li>
 *   <li>로그인 없이 허용: 로그인·초대 화면과 그 화면이 쓰는 파일, POST /api/login, GET /api/session, GET /api/health, /api/invites/**,
 *       설치형 앱 파일(manifest·서비스 워커·오프라인 화면·아이콘)</li>
 *   <li>그 외 /api/** 는 401, 화면 요청은 /login.html 로 이동</li>
 *   <li>CSRF: XSRF-TOKEN 쿠키 → 화면 JS 가 X-XSRF-TOKEN 헤더로 돌려보냄</li>
 *   <li>로그인 유지: 30일 쿠키. 서명에 비밀번호 해시가 들어가므로 비밀번호를 바꾸면 자동 무효</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
			+ "frame-ancestors 'none'; form-action 'self'; base-uri 'none'";

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	TokenBasedRememberMeServices rememberMeServices(RememberKey key, UserDetailsService users,
			HaruProperties props) {
		var services = new HaruRememberMeServices(key.value(), users, RememberMeTokenAlgorithm.SHA256);
		services.setAlwaysRemember(true);
		services.setTokenValiditySeconds((int) Duration.ofDays(props.rememberMeDays()).toSeconds());
		services.setUseSecureCookie(props.secureCookie());
		services.setCookieName("haru_remember");
		return services;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptService attempts,
			TokenBasedRememberMeServices rememberMe, GoogleSupport google, GoogleLoginService googleLogin,
			UserDetailsService users) throws Exception {
		var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

		// Google 로그인: 클라이언트 ID/비밀 환경변수가 있을 때만 켠다
		if (google.enabled()) {
			http.oauth2Login(o -> o
				.loginPage("/login.html")
				.successHandler(new GoogleLoginSuccessHandler(googleLogin, users, rememberMe))
				.failureHandler((req, res, ex) -> {
					log.warn("Google 로그인 실패: {}", ex.getMessage());
					res.sendRedirect("/login.html?error=google");
				}));
		}

		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/login.html", "/login.js", "/invite.html", "/invite.js", "/style.css", "/theme.js",
						"/favicon.svg", "/api/session", "/api/health", "/api/invites/**", "/auth/google", "/error",
						// 설치형 앱(PWA): 휴대폰은 manifest·아이콘을 로그인 정보 없이 받는다
						"/manifest.json", "/sw.js", "/offline.html", "/icons/**").permitAll()
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage("/login.html")
				.loginProcessingUrl("/api/login")
				.usernameParameter("username")
				.passwordParameter("password")
				.successHandler((req, res, authentication) -> {
					attempts.recordSuccess(req.getRemoteAddr());
					log.info("로그인 성공 user={} ip={}", authentication.getName(), req.getRemoteAddr());
					JsonResponses.write(res, 200, null);
				})
				.failureHandler((req, res, ex) -> {
					attempts.recordFailure(req.getRemoteAddr());
					log.warn("로그인 실패 ip={}", req.getRemoteAddr());
					slowDown();
					JsonResponses.write(res, 401, "bad credentials");
				})
				.permitAll())
			.rememberMe(r -> r.rememberMeServices(rememberMe).key(rememberMe.getKey()))
			.logout(out -> out
				.logoutUrl("/api/logout")
				.deleteCookies("JSESSIONID")
				.logoutSuccessHandler((req, res, authentication) -> JsonResponses.write(res, 200, null)))
			.csrf(csrf -> csrf.spa())
			.exceptionHandling(ex -> ex
				.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api)
				.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login.html"),
						AnyRequestMatcher.INSTANCE))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
				.referrerPolicy(ref -> ref.policy(ReferrerPolicy.SAME_ORIGIN)))
			.addFilterBefore(new LoginRateLimitFilter(attempts), UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	/** 실패한 로그인 응답을 0.5~1초 늦춰 무차별 대입 속도를 낮춘다. */
	private static void slowDown() {
		try {
			Thread.sleep(500 + ThreadLocalRandom.current().nextInt(500));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 모든 응답에 XSRF-TOKEN 쿠키가 실리도록 토큰을 미리 꺼내 둔다 (첫 화면 로딩 직후 PUT 대비). */
	static final class CsrfCookieFilter extends OncePerRequestFilter {
		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
			if (token != null) {
				token.getToken();
			}
			chain.doFilter(request, response);
		}
	}
}
