package com.haruhanpan.google;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.common.ApiErrors.NotFound;
import com.haruhanpan.invite.InviteService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google 로그인 시작점. 의도(연결/초대 가입)를 세션에 남기고 Google 인증 주소를 돌려준다.
 * <pre>
 * GET    /auth/google              그냥 Google 로 로그인 (남아 있던 의도는 지움) → Google 로 이동
 * POST   /api/me/google            내 계정에 Google 연결 시작 → {url}
 * DELETE /api/me/google            Google 연결 해제 (비밀번호가 없으면 거절)
 * POST   /api/invites/google {token}   초대 링크로 Google 가입 시작 → {url}
 * </pre>
 */
@RestController
public class GoogleController {

	private final GoogleSupport support;
	private final IdentityRepository identities;
	private final InviteService invites;

	public GoogleController(GoogleSupport support, IdentityRepository identities, InviteService invites) {
		this.support = support;
		this.identities = identities;
		this.invites = invites;
	}

	@GetMapping("/auth/google")
	public void login(HttpSession session, HttpServletResponse response) throws IOException {
		requireEnabled();
		GoogleLoginSuccessHandler.clearIntent(session);
		response.sendRedirect(GoogleSupport.AUTHORIZE_URL);
	}

	@PostMapping("/api/me/google")
	public Map<String, Object> startLink(AppUser me, HttpSession session) {
		requireEnabled();
		GoogleLoginSuccessHandler.setIntent(session, new GoogleLoginService.Link(me.id()));
		return Map.of("url", GoogleSupport.AUTHORIZE_URL);
	}

	@DeleteMapping("/api/me/google")
	public Map<String, Object> unlink(AppUser me) {
		if (!me.passwordSet()) {
			throw new IllegalArgumentException("Google 로 가입한 계정입니다. 먼저 내 계정에서 비밀번호를 정한 뒤 연결을 해제하세요");
		}
		identities.unlink(me.id());
		return Map.of("ok", true);
	}

	@PostMapping("/api/invites/google")
	public Map<String, Object> startInviteSignup(@RequestBody Map<String, String> body, HttpSession session) {
		requireEnabled();
		String token = body.get("token");
		if (!invites.isValidJoin(token)) {
			throw new IllegalArgumentException("만료되었거나 이미 사용한 링크입니다");
		}
		GoogleLoginSuccessHandler.setIntent(session, new GoogleLoginService.Invite(token));
		return Map.of("url", GoogleSupport.AUTHORIZE_URL);
	}

	private void requireEnabled() {
		if (!support.enabled()) {
			throw new NotFound("Google 로그인이 설정되지 않았습니다");
		}
	}
}
