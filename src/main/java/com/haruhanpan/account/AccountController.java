package com.haruhanpan.account;

import com.haruhanpan.invite.InviteService;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 계정.
 * <pre>
 * PUT  /api/me                    {name}            표시 이름 변경
 * PUT  /api/me/password           {current, next}   비밀번호 변경 (다른 기기의 로그인 유지는 풀림)
 * POST /api/users/{uid}/reset-link                  비밀번호 재설정 링크 (사이트 관리자만)
 * POST   /api/admin/users/{uid}/approve {spaceId?, role?}  가입 승인 (+ 팀 공간에 추가)
 * DELETE /api/admin/users/{uid}                     가입 거절 (승인 대기 계정 삭제)
 * </pre>
 */
@RestController
public class AccountController {

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final InviteService invites;
	private final AccountService accounts;

	public AccountController(UserRepository users, PasswordEncoder encoder, InviteService invites,
			AccountService accounts) {
		this.users = users;
		this.encoder = encoder;
		this.invites = invites;
		this.accounts = accounts;
	}

	@PutMapping("/api/me")
	public Map<String, Object> rename(AppUser me, @RequestBody Map<String, String> body) {
		String name = body.getOrDefault("name", "").strip();
		if (name.isEmpty() || name.length() > 50) {
			throw new IllegalArgumentException("이름은 1~50자");
		}
		users.updateName(me.id(), name);
		return Map.of("ok", true);
	}

	/** Google 로 가입해 비밀번호가 없는 사람(passwordSet=false)은 현재 비밀번호 없이 처음 정할 수 있다 */
	@PutMapping("/api/me/password")
	public Map<String, Object> changePassword(AppUser me, @RequestBody Map<String, String> body) {
		if (me.passwordSet() && !encoder.matches(body.getOrDefault("current", ""), me.passwordHash())) {
			throw new IllegalArgumentException("현재 비밀번호가 맞지 않습니다");
		}
		InviteService.requireStrong(body.get("next"));
		users.updatePassword(me.id(), encoder.encode(body.get("next")));
		return Map.of("ok", true);
	}

	@PostMapping("/api/users/{uid}/reset-link")
	public Map<String, Object> resetLink(AppUser me, @PathVariable String uid) {
		return Map.of("token", invites.createReset(me, uid));
	}

	/** 가입 승인 (사이트 관리자). 본문의 spaceId/role 로 팀 공간에 바로 넣을 수 있다 */
	@PostMapping("/api/admin/users/{uid}/approve")
	public Map<String, Object> approve(AppUser me, @PathVariable String uid,
			@RequestBody(required = false) Map<String, String> body) {
		Map<String, String> b = body == null ? Map.of() : body;
		accounts.approve(me, uid, b.get("spaceId"), b.get("role"));
		return Map.of("ok", true);
	}

	/** 가입 거절 (사이트 관리자): 승인 대기 중인 계정 삭제 */
	@DeleteMapping("/api/admin/users/{uid}")
	public Map<String, Object> reject(AppUser me, @PathVariable String uid) {
		accounts.reject(me, uid);
		return Map.of("ok", true);
	}
}
