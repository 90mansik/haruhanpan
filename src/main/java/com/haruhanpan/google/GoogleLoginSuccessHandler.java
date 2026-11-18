package com.haruhanpan.google;

import com.haruhanpan.google.GoogleLoginService.GoogleLoginException;
import com.haruhanpan.google.GoogleLoginService.GoogleProfile;
import com.haruhanpan.google.GoogleLoginService.Intent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Google 인증이 끝나면 호출된다. Google 계정을 하루한판 사용자로 바꿔서
 * 아이디·비밀번호 로그인과 똑같은 상태(세션 + 30일 로그인 유지 쿠키)로 만든다.
 */
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

	/** 세션에 남겨 두는 Google 로그인 의도 (연결 / 초대 가입). 10분 지나면 무시 */
	public static final String INTENT = "haru.googleIntent";
	public static final String INTENT_AT = "haru.googleIntentAt";
	private static final Duration INTENT_TTL = Duration.ofMinutes(10);

	private final GoogleLoginService google;
	private final UserDetailsService users;
	private final RememberMeServices rememberMe;
	private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

	public GoogleLoginSuccessHandler(GoogleLoginService google, UserDetailsService users, RememberMeServices rememberMe) {
		this.google = google;
		this.users = users;
		this.rememberMe = rememberMe;
	}

	public static void setIntent(HttpSession session, Intent intent) {
		session.setAttribute(INTENT, intent);
		session.setAttribute(INTENT_AT, Instant.now());
	}

	public static void clearIntent(HttpSession session) {
		session.removeAttribute(INTENT);
		session.removeAttribute(INTENT_AT);
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		OidcUser oidc = (OidcUser) authentication.getPrincipal();
		GoogleProfile profile = new GoogleProfile(oidc.getSubject(), oidc.getEmail(),
				Boolean.TRUE.equals(oidc.getEmailVerified()), oidc.getClaimAsString("hd"), oidc.getFullName());

		HttpSession session = request.getSession();
		Intent intent = takeIntent(session);
		String login;
		try {
			login = google.resolve(profile, intent);
		} catch (GoogleLoginException e) {
			// Google 인증 상태로 남지 않게 로그아웃 상태로 되돌린다
			SecurityContext empty = SecurityContextHolder.createEmptyContext();
			SecurityContextHolder.setContext(empty);
			contexts.saveContext(empty, request, response);
			response.sendRedirect("/login.html?error=" + e.code());
			return;
		}

		UserDetails user = users.loadUserByUsername(login);
		Authentication auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		rememberMe.loginSuccess(request, response, auth);
		response.sendRedirect(intent instanceof GoogleLoginService.Link ? "/board?google=linked" : "/"); // 연결은 하루한판 내 계정에서 시작
	}

	private static Intent takeIntent(HttpSession session) {
		Object intent = session.getAttribute(INTENT);
		Object at = session.getAttribute(INTENT_AT);
		clearIntent(session);
		if (intent instanceof Intent i && at instanceof Instant t && t.plus(INTENT_TTL).isAfter(Instant.now())) {
			return i;
		}
		return null;
	}
}
