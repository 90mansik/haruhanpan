package com.haruhanpan.account;

import com.haruhanpan.common.ApiErrors.Forbidden;
import com.haruhanpan.common.Ids;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceRepository;
import com.haruhanpan.space.SpaceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 사용자 만들기·승인·거절. 사용자마다 개인 공간이 하나 생긴다.
 * <ul>
 *   <li>초대 링크로 가입: 바로 사용 가능</li>
 *   <li>Google 로 직접 가입: 승인 대기 → 사이트 관리자가 승인하면 사용 가능 (30일 지나면 자동 정리)</li>
 * </ul>
 */
@Service
public class AccountService {

	public static final Pattern LOGIN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,29}$");
	/** 이 기간 동안 승인되지 않은 가입 신청은 지운다 */
	static final Duration PENDING_TTL = Duration.ofDays(30);
	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	private final UserRepository users;
	private final SpaceService spaces;
	private final SpaceRepository spaceRepository;
	private final PasswordEncoder encoder;

	public AccountService(UserRepository users, SpaceService spaces, SpaceRepository spaceRepository,
			PasswordEncoder encoder) {
		this.users = users;
		this.spaces = spaces;
		this.spaceRepository = spaceRepository;
		this.encoder = encoder;
	}

	/** 아이디·비밀번호로 가입 (초대 링크) */
	@Transactional
	public String createWithPassword(String login, String name, String password) {
		return create(login, name, encoder.encode(password), true, true);
	}

	/**
	 * Google 로 가입: 비밀번호는 아무도 모르는 임의 값 (비밀번호 로그인 불가, 나중에 내 계정에서 정할 수 있음).
	 * 아이디는 이메일 앞부분으로 만들고, 겹치면 숫자를 붙인다.
	 *
	 * @param approved false 면 관리자 승인 대기
	 */
	@Transactional
	public String createForExternal(String email, String name, boolean approved) {
		String login = uniqueLogin(email == null ? "" : email.split("@")[0]);
		String displayName = name == null || name.isBlank() ? login : name.strip();
		if (displayName.length() > 50) {
			displayName = displayName.substring(0, 50);
		}
		return create(login, displayName, encoder.encode(Ids.newToken()), false, approved);
	}

	/** 승인 대기 목록 (사이트 관리자만) */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> pending(AppUser me) {
		requireAdmin(me);
		return users.findPending();
	}

	/** 사용자 관리: 승인된 모든 사용자와 권한 */
	public List<Map<String, Object>> allUsers(AppUser me) {
		requireAdmin(me);
		return users.findAllForAdmin();
	}

	/**
	 * 권한 바꾸기 (사이트 관리자). 값을 보내지 않은 항목은 그대로 둔다.
	 * 자기 자신의 관리자 권한은 뺄 수 없다 (실수로 아무도 관리할 수 없게 되는 것을 막는다).
	 */
	@Transactional
	public void setPermissions(AppUser me, String userId, Boolean admin, Boolean ledger) {
		requireAdmin(me);
		AppUser target = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("없는 사용자"));
		if (!target.approved()) {
			throw new IllegalArgumentException("가입 승인 후에 권한을 줄 수 있습니다");
		}
		boolean nextAdmin = admin == null ? target.admin() : admin;
		boolean nextLedger = ledger == null ? target.ledger() : ledger;
		if (target.admin() && !nextAdmin) {
			if (target.id().equals(me.id())) {
				throw new IllegalArgumentException("내 관리자 권한은 뺄 수 없습니다. 다른 관리자에게 부탁하세요");
			}
			if (users.countAdmins() <= 1) {
				throw new IllegalArgumentException("관리자가 한 명은 있어야 합니다");
			}
		}
		users.updatePermissions(userId, nextAdmin, nextLedger);
		log.info("권한 변경 user={} admin={} ledger={} by={}", target.login(), nextAdmin, nextLedger, me.login());
	}

	/** 승인. spaceId 가 있으면 그 팀 공간에 바로 넣는다 (관리자가 그 공간의 관리자여야 함) */
	@Transactional
	public void approve(AppUser me, String userId, String spaceId, String role) {
		requireAdmin(me);
		AppUser target = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("없는 사용자"));
		if (target.approved()) {
			throw new IllegalArgumentException("이미 승인된 사용자입니다");
		}
		users.approve(userId);
		if (StringUtils.hasText(spaceId)) {
			if (!spaces.require(me, spaceId, Role.OWNER).isTeam()) {
				throw new IllegalArgumentException("팀 공간만 고를 수 있습니다");
			}
			spaces.join(spaceId, userId, Role.of(StringUtils.hasText(role) ? role : "editor"));
		}
		log.info("가입 승인 user={} by={}", target.login(), me.login());
	}

	/** 거절: 승인 대기 중인 계정과 그 개인 공간을 지운다 */
	@Transactional
	public void reject(AppUser me, String userId) {
		requireAdmin(me);
		AppUser target = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("없는 사용자"));
		if (target.approved()) {
			throw new IllegalArgumentException("승인 대기 중인 사용자만 거절할 수 있습니다");
		}
		deleteUser(userId);
		log.info("가입 거절 user={} by={}", target.login(), me.login());
	}

	/** 초대 링크로 들어온 승인 대기 사용자는 초대를 믿고 승인한다 */
	@Transactional
	public void approveByInvite(String userId) {
		users.approve(userId);
	}

	/** 오래된 가입 신청 정리 (매일 백업 때 호출) */
	@Transactional
	public int purgeStalePending() {
		List<String> stale = users.pendingBefore(Instant.now().minus(PENDING_TTL).toString());
		stale.forEach(this::deleteUser);
		if (!stale.isEmpty()) {
			log.info("{}일 넘게 승인되지 않은 가입 신청 {}건 삭제", PENDING_TTL.toDays(), stale.size());
		}
		return stale.size();
	}

	private void deleteUser(String userId) {
		for (Map<String, Object> s : spaceRepository.spacesOf(userId)) {
			if ("personal".equals(s.get("kind"))) {
				spaceRepository.deleteCascade((String) s.get("id"));
			}
		}
		users.delete(userId);
	}

	private String create(String login, String name, String passwordHash, boolean passwordSet, boolean approved) {
		String id = Ids.newId("u");
		users.insert(id, login, name, passwordHash, passwordSet, approved, Instant.now().toString());
		spaces.createPersonal(id);
		return id;
	}

	private static void requireAdmin(AppUser me) {
		if (!me.admin()) {
			throw new Forbidden("사이트 관리자만 할 수 있습니다");
		}
	}

	String uniqueLogin(String base) {
		String clean = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
		clean = clean.replaceAll("^[._-]+", "");
		if (clean.length() < 3) {
			clean = clean + "user";
		}
		if (clean.length() > 26) {
			clean = clean.substring(0, 26);
		}
		String candidate = clean;
		for (int n = 2; users.loginTaken(candidate) || !LOGIN.matcher(candidate).matches(); n++) {
			candidate = clean + n;
		}
		return candidate;
	}
}
