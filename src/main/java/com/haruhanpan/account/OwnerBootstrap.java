package com.haruhanpan.account;

import com.haruhanpan.config.HaruProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 최초 사용자(관리자, 아이디 haru)의 비밀번호를 정한다. 서버 시작 시 한 번 실행.
 * <ol>
 *   <li>data-dir/password.hash 가 있으면 (hash-password 명령으로 만든 것) 관리자 비밀번호로 적용하고 파일을 지운다.
 *       → 관리자 비밀번호를 잊었을 때 서버에서 재설정하는 방법이기도 하다.</li>
 *   <li>관리자 비밀번호가 아직 없고 dev-password 가 있으면 그것으로 설정 (로컬 개발용)</li>
 *   <li>둘 다 없으면 서버 시작을 거부한다 (fail closed)</li>
 * </ol>
 */
@Component
public class OwnerBootstrap implements ApplicationRunner {

	public static final String OWNER_ID = "u-owner";
	public static final String HASH_FILE = "password.hash";
	private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final HaruProperties props;

	public OwnerBootstrap(UserRepository users, PasswordEncoder encoder, HaruProperties props) {
		this.users = users;
		this.encoder = encoder;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		Path hashFile = props.dataDir().resolve(HASH_FILE);
		if (Files.exists(hashFile)) {
			users.updatePassword(OWNER_ID, Files.readString(hashFile).strip());
			Files.delete(hashFile);
			log.info("관리자 비밀번호를 {} 에서 적용했습니다 (파일은 삭제)", hashFile);
			return;
		}
		AppUser owner = users.findById(OWNER_ID).orElseThrow();
		if (owner.passwordHash() != null) {
			return;
		}
		if (StringUtils.hasText(props.devPassword())) {
			users.updatePassword(OWNER_ID, encoder.encode(props.devPassword()));
			return;
		}
		throw new IllegalStateException("관리자 비밀번호가 설정되지 않았습니다. 먼저 실행하세요: "
				+ "HARU_DATA_DIR=" + props.dataDir() + " java -jar haruhanpan.jar hash-password");
	}
}
