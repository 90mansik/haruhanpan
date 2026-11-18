package com.haruhanpan.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * 30일 로그인 유지 쿠키.
 *
 * <p>Spring Security 는 Google(OAuth2) 인증이 끝나면 우리 성공 처리기보다 먼저 로그인 유지 쿠키를 만들려고 한다.
 * 그 시점의 로그인 정보는 Google 계정이라 하루한판 사용자 목록에 없어서 실패("no user")한다.
 * 그래서 하루한판 사용자(UserDetails)일 때만 쿠키를 만들고, Google 인증은 건너뛴다.
 * Google 로그인은 GoogleLoginSuccessHandler 가 하루한판 사용자로 바꾼 뒤 이 서비스를 다시 불러 쿠키를 만든다.
 */
public class HaruRememberMeServices extends TokenBasedRememberMeServices {

	public HaruRememberMeServices(String key, UserDetailsService users, RememberMeTokenAlgorithm algorithm) {
		super(key, users, algorithm);
	}

	@Override
	public void onLoginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
		if (auth.getPrincipal() instanceof UserDetails) {
			super.onLoginSuccess(request, response, auth);
		}
	}
}
