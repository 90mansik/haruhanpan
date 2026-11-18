package com.haruhanpan.invite;

import com.haruhanpan.account.AccountService;
import com.haruhanpan.account.AppUser;
import com.haruhanpan.account.UserRepository;
import com.haruhanpan.auth.PasswordSetup;
import com.haruhanpan.common.ApiErrors.Forbidden;
import com.haruhanpan.common.Ids;
import com.haruhanpan.invite.InviteRepository.Invite;
import com.haruhanpan.space.Access;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceRepository;
import com.haruhanpan.space.SpaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 링크 하나로 처리하는 두 가지:
 * <ul>
 *   <li>join: 팀 공간 초대. 관리자가 만들고 7일·1회용. 계정이 없으면 이 링크로 가입한다 (공개 가입은 없음)</li>
 *   <li>reset: 비밀번호 재설정. 사이트 관리자(admin)만 만들 수 있고 1일·1회용</li>
 * </ul>
 */
@Service
public class InviteService {

	public static final int JOIN_DAYS = 7;
	static final int RESET_DAYS = 1;

	private final InviteRepository invites;
	private final SpaceService spaceService;
	private final SpaceRepository spaceRepository;
	private final UserRepository users;
	private final AccountService accounts;
	private final PasswordEncoder encoder;

	public InviteService(InviteRepository invites, SpaceService spaceService, SpaceRepository spaceRepository,
			UserRepository users, AccountService accounts, PasswordEncoder encoder) {
		this.invites = invites;
		this.spaceService = spaceService;
		this.spaceRepository = spaceRepository;
		this.users = users;
		this.accounts = accounts;
		this.encoder = encoder;
	}

	@Transactional
	public String createJoin(AppUser me, String spaceId, Role role) {
		Access access = spaceService.require(me, spaceId, Role.OWNER);
		if (!access.isTeam()) {
			throw new IllegalArgumentException("개인 공간에는 초대할 수 없습니다");
		}
		return create("join", spaceId, role.db(), null, me.id(), JOIN_DAYS);
	}

	@Transactional
	public String createReset(AppUser me, String userId) {
		if (!me.admin()) {
			throw new Forbidden("사이트 관리자만 재설정 링크를 만들 수 있습니다");
		}
		users.findById(userId).orElseThrow(() -> new IllegalArgumentException("없는 사용자"));
		return create("reset", null, null, userId, me.id(), RESET_DAYS);
	}

	/** 링크를 열었을 때 보여줄 정보. 잘못된 토큰이면 valid=false */
	@Transactional(readOnly = true)
	public Map<String, Object> peek(String token) {
		Map<String, Object> info = new LinkedHashMap<>();
		Invite inv = valid(token);
		info.put("valid", inv != null);
		if (inv == null) {
			return info;
		}
		info.put("kind", inv.kind());
		if ("join".equals(inv.kind())) {
			info.put("spaceName", spaceRepository.nameOf(inv.spaceId()).orElse(""));
			info.put("role", inv.role());
		} else {
			users.findById(inv.userId()).ifPresent(u -> info.put("login", u.login()));
		}
		return info;
	}

	/** 로그인한 사용자가 초대를 수락 */
	@Transactional
	public String acceptAsMember(String token, AppUser me) {
		Invite inv = use(token, "join");
		if (!me.approved()) {
			accounts.approveByInvite(me.id()); // 팀 관리자가 초대했으니 가입 승인으로 본다
		}
		spaceService.join(inv.spaceId(), me.id(), Role.of(inv.role()));
		return inv.spaceId();
	}

	/** 계정이 없는 사람이 초대 링크로 가입하면서 수락 */
	@Transactional
	public String acceptWithSignup(String token, String login, String name, String password) {
		String cleanLogin = login == null ? "" : login.strip().toLowerCase();
		if (!AccountService.LOGIN.matcher(cleanLogin).matches()) {
			throw new IllegalArgumentException("아이디는 영문 소문자·숫자·. _ - 로 3~30자");
		}
		String cleanName = name == null ? "" : name.strip();
		if (cleanName.isEmpty() || cleanName.length() > 50) {
			throw new IllegalArgumentException("이름은 1~50자");
		}
		requireStrong(password);
		if (users.findByLogin(cleanLogin).isPresent()) {
			throw new IllegalArgumentException("이미 쓰고 있는 아이디입니다");
		}
		Invite inv = use(token, "join");
		String userId = accounts.createWithPassword(cleanLogin, cleanName, password);
		spaceService.join(inv.spaceId(), userId, Role.of(inv.role()));
		return cleanLogin;
	}

	/** 아직 쓸 수 있는 참여 링크인가 (Google 가입을 시작하기 전에 확인) */
	@Transactional(readOnly = true)
	public boolean isValidJoin(String token) {
		Invite inv = valid(token);
		return inv != null && "join".equals(inv.kind());
	}

	/** Google 로 가입한 새 사용자를 초대받은 공간에 넣고 링크를 사용 처리한다 */
	@Transactional
	public void joinNewUser(String token, String userId) {
		Invite inv = use(token, "join");
		spaceService.join(inv.spaceId(), userId, Role.of(inv.role()));
	}

	/** 재설정 링크로 새 비밀번호 설정. 바뀐 비밀번호 때문에 그 사람의 로그인 유지 쿠키는 모두 무효가 된다 */
	@Transactional
	public String resetPassword(String token, String password) {
		requireStrong(password);
		Invite inv = use(token, "reset");
		users.updatePassword(inv.userId(), encoder.encode(password));
		return users.findById(inv.userId()).map(AppUser::login).orElse("");
	}

	public static void requireStrong(String password) {
		if (password == null || password.length() < PasswordSetup.MIN_LENGTH) {
			throw new IllegalArgumentException("비밀번호는 " + PasswordSetup.MIN_LENGTH + "자 이상");
		}
	}

	private String create(String kind, String spaceId, String role, String userId, String by, int days) {
		String token = Ids.newToken();
		Instant now = Instant.now();
		invites.deleteExpired(now.toString());
		invites.insert(hash(token), kind, spaceId, role, userId, by, now.toString(),
				now.plus(Duration.ofDays(days)).toString());
		return token;
	}

	private Invite valid(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		return invites.find(hash(token))
			.filter(i -> i.usedAt() == null)
			.filter(i -> i.expiresAt().compareTo(Instant.now().toString()) > 0)
			.orElse(null);
	}

	private Invite use(String token, String kind) {
		Invite inv = valid(token);
		if (inv == null || !kind.equals(inv.kind()) || !invites.markUsed(inv.tokenHash(), Instant.now().toString())) {
			throw new IllegalArgumentException("만료되었거나 이미 사용한 링크입니다");
		}
		return inv;
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
