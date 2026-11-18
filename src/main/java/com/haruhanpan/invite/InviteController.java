package com.haruhanpan.invite;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.account.UserRepository;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 초대·재설정 링크 처리 (로그인 없이 호출 가능).
 * 토큰은 URL 경로가 아니라 본문으로 받는다 (서버 접근 로그·Referer 에 남지 않도록 링크도 #토큰 형태).
 * <pre>
 * POST /api/invites/peek    {token}                         링크 정보 (공간 이름·역할 / 재설정 대상 아이디)
 * POST /api/invites/accept  {token} 또는 {token, login, name, password}   참여 (로그인 상태면 바로, 아니면 가입)
 * POST /api/invites/reset   {token, password}               비밀번호 재설정
 * </pre>
 */
@RestController
@RequestMapping("/api/invites")
public class InviteController {

	private final InviteService invites;
	private final UserRepository users;

	public InviteController(InviteService invites, UserRepository users) {
		this.invites = invites;
		this.users = users;
	}

	@PostMapping("/peek")
	public Map<String, Object> peek(@RequestBody Map<String, String> body, Authentication auth) {
		Map<String, Object> info = new java.util.LinkedHashMap<>(invites.peek(body.get("token")));
		current(auth).ifPresent(u -> info.put("loggedInAs", u.name()));
		return info;
	}

	@PostMapping("/accept")
	public Map<String, Object> accept(@RequestBody Map<String, String> body, Authentication auth) {
		var me = current(auth);
		if (me.isPresent()) {
			return Map.of("spaceId", invites.acceptAsMember(body.get("token"), me.get()), "joined", true);
		}
		String login = invites.acceptWithSignup(body.get("token"), body.get("login"), body.get("name"), body.get("password"));
		return Map.of("login", login, "joined", true);
	}

	@PostMapping("/reset")
	public Map<String, Object> reset(@RequestBody Map<String, String> body) {
		return Map.of("login", invites.resetPassword(body.get("token"), body.get("password")));
	}

	private java.util.Optional<AppUser> current(Authentication auth) {
		if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
			return java.util.Optional.empty();
		}
		return users.findByLogin(auth.getName()).filter(u -> !u.disabled());
	}
}
