package com.haruhanpan.space;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.common.ApiErrors.Forbidden;
import com.haruhanpan.common.Ids;
import com.haruhanpan.wiki.WikiFiles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공간(개인/팀)과 멤버 관리, 그리고 모든 데이터 API 의 권한 검사({@link #require}).
 * 데이터를 읽고 쓰는 API 는 반드시 require 를 먼저 거친다.
 */
@Service
public class SpaceService {

	private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private final SpaceRepository spaces;
	private final WikiFiles wikiFiles;

	public SpaceService(SpaceRepository spaces, WikiFiles wikiFiles) {
		this.spaces = spaces;
		this.wikiFiles = wikiFiles;
	}

	/** me 가 sid 공간에서 min 이상 역할인지 확인. 아니면 403 */
	public Access require(AppUser me, String spaceId, Role min) {
		Access access = spaces.access(spaceId, me.id())
			.orElseThrow(() -> new Forbidden("이 공간의 멤버가 아닙니다"));
		if (!access.role().atLeast(min)) {
			throw new Forbidden("권한이 없습니다");
		}
		return access;
	}

	public List<Map<String, Object>> spacesOf(AppUser me) {
		return spaces.spacesOf(me.id());
	}

	public Map<String, List<Map<String, Object>>> membersOf(java.util.Collection<String> spaceIds) {
		return spaces.membersOf(spaceIds);
	}

	/** 새 사용자의 개인 공간 */
	@Transactional
	public String createPersonal(String userId) {
		String id = Ids.newId("sp");
		String now = Instant.now().toString();
		spaces.insertSpace(id, "내 공간", "personal", "#2563eb", now);
		spaces.addMember(id, userId, Role.OWNER, now);
		return id;
	}

	@Transactional
	public String createTeam(AppUser me, String name, String color) {
		String id = Ids.newId("sp");
		String now = Instant.now().toString();
		spaces.insertSpace(id, cleanName(name), "team", cleanColor(color), now);
		spaces.addMember(id, me.id(), Role.OWNER, now);
		return id;
	}

	@Transactional
	public void update(AppUser me, String spaceId, String name, String color) {
		require(me, spaceId, Role.OWNER);
		spaces.updateSpace(spaceId, cleanName(name), cleanColor(color));
	}

	@Transactional
	public void delete(AppUser me, String spaceId) {
		Access access = require(me, spaceId, Role.OWNER);
		if (!access.isTeam()) {
			throw new IllegalArgumentException("개인 공간은 지울 수 없습니다");
		}
		wikiFiles.deleteAllIn(spaceId); // 위키 이미지 (디스크 파일은 트랜잭션이 끝난 뒤 지운다)
		spaces.deleteCascade(spaceId);
	}

	@Transactional
	public void setRole(AppUser me, String spaceId, String userId, Role role) {
		require(me, spaceId, Role.OWNER);
		Access target = spaces.access(spaceId, userId)
			.orElseThrow(() -> new IllegalArgumentException("멤버가 아닙니다"));
		if (target.role() == Role.OWNER && role != Role.OWNER && spaces.countOwners(spaceId) <= 1) {
			throw new IllegalArgumentException("관리자가 최소 1명은 있어야 합니다");
		}
		spaces.setRole(spaceId, userId, role);
	}

	/** 관리자가 멤버를 내보내거나, 멤버가 스스로 나간다 */
	@Transactional
	public void removeMember(AppUser me, String spaceId, String userId) {
		Access mine = require(me, spaceId, Role.VIEWER);
		if (!mine.isTeam()) {
			throw new IllegalArgumentException("개인 공간에서는 나갈 수 없습니다");
		}
		if (!userId.equals(me.id()) && mine.role() != Role.OWNER) {
			throw new Forbidden("관리자만 멤버를 내보낼 수 있습니다");
		}
		Access target = spaces.access(spaceId, userId)
			.orElseThrow(() -> new IllegalArgumentException("멤버가 아닙니다"));
		if (target.role() == Role.OWNER && spaces.countOwners(spaceId) <= 1) {
			throw new IllegalArgumentException("마지막 관리자는 나갈 수 없습니다. 다른 멤버를 관리자로 지정하거나 공간을 삭제하세요");
		}
		spaces.removeMember(spaceId, userId);
	}

	/** 초대 수락 시. 이미 멤버면 아무것도 하지 않는다 */
	@Transactional
	public void join(String spaceId, String userId, Role role) {
		if (spaces.access(spaceId, userId).isEmpty()) {
			spaces.addMember(spaceId, userId, role, Instant.now().toString());
		}
	}

	private static String cleanName(String name) {
		String n = name == null ? "" : name.strip();
		if (n.isEmpty() || n.length() > 50) {
			throw new IllegalArgumentException("공간 이름은 1~50자");
		}
		return n;
	}

	private static String cleanColor(String color) {
		return color != null && COLOR.matcher(color).matches() ? color : "#16a34a";
	}
}
