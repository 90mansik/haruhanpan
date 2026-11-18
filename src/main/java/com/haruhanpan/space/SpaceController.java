package com.haruhanpan.space;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.invite.InviteService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 팀 공간 관리.
 * <pre>
 * POST   /api/spaces                      {name, color}  팀 공간 만들기 (만든 사람이 관리자)
 * PUT    /api/spaces/{sid}                {name, color}  이름·색 변경 (관리자)
 * DELETE /api/spaces/{sid}                               팀 공간 삭제 (관리자, 데이터 전부 삭제)
 * PUT    /api/spaces/{sid}/members/{uid}  {role}         역할 변경 (관리자)
 * DELETE /api/spaces/{sid}/members/{uid}                 내보내기 (관리자) / 나가기 (본인)
 * POST   /api/spaces/{sid}/invites        {role}         초대 링크 만들기 (관리자, 7일·1회용)
 * </pre>
 */
@RestController
@RequestMapping("/api/spaces")
public class SpaceController {

	private final SpaceService spaces;
	private final InviteService invites;

	public SpaceController(SpaceService spaces, InviteService invites) {
		this.spaces = spaces;
		this.invites = invites;
	}

	@PostMapping
	public Map<String, Object> create(AppUser me, @RequestBody Map<String, String> body) {
		return Map.of("id", spaces.createTeam(me, body.get("name"), body.get("color")));
	}

	@PutMapping("/{sid}")
	public Map<String, Object> update(AppUser me, @PathVariable String sid, @RequestBody Map<String, String> body) {
		spaces.update(me, sid, body.get("name"), body.get("color"));
		return Map.of("ok", true);
	}

	@DeleteMapping("/{sid}")
	public Map<String, Object> delete(AppUser me, @PathVariable String sid) {
		spaces.delete(me, sid);
		return Map.of("ok", true);
	}

	@PutMapping("/{sid}/members/{uid}")
	public Map<String, Object> setRole(AppUser me, @PathVariable String sid, @PathVariable String uid,
			@RequestBody Map<String, String> body) {
		spaces.setRole(me, sid, uid, Role.of(body.get("role")));
		return Map.of("ok", true);
	}

	@DeleteMapping("/{sid}/members/{uid}")
	public Map<String, Object> removeMember(AppUser me, @PathVariable String sid, @PathVariable String uid) {
		spaces.removeMember(me, sid, uid);
		return Map.of("ok", true);
	}

	@PostMapping("/{sid}/invites")
	public Map<String, Object> invite(AppUser me, @PathVariable String sid, @RequestBody Map<String, String> body) {
		String token = invites.createJoin(me, sid, Role.of(body.getOrDefault("role", "editor")));
		return Map.of("token", token, "days", InviteService.JOIN_DAYS);
	}
}
