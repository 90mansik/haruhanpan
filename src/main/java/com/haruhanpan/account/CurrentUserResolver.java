package com.haruhanpan.account;

import com.haruhanpan.common.ApiErrors;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 메서드의 {@code AppUser me} 파라미터에 로그인한 사용자를 넣어 준다. */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

	private final UserRepository users;

	public CurrentUserResolver(UserRepository users) {
		this.users = users;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == AppUser.class;
	}

	@Override
	public AppUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
			WebDataBinderFactory binderFactory) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || auth instanceof AnonymousAuthenticationToken) {
			throw new ApiErrors.Forbidden("로그인이 필요합니다");
		}
		AppUser user = users.findByLogin(auth.getName())
			.filter(u -> !u.disabled())
			.orElseThrow(() -> new ApiErrors.Forbidden("사용할 수 없는 계정"));
		if (!user.approved()) {
			// Google 로 직접 가입해 관리자 승인을 기다리는 중: 화면은 이 값을 보고 "승인 대기" 안내를 보여 준다
			throw new ApiErrors.Forbidden("approval_pending");
		}
		return user;
	}
}
