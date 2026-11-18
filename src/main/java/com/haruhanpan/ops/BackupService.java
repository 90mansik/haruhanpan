package com.haruhanpan.ops;

import com.haruhanpan.account.AccountService;
import com.haruhanpan.config.HaruProperties;
import com.haruhanpan.data.ActivityRepository;
import com.haruhanpan.data.DashboardService;
import com.haruhanpan.ledger.LedgerService;
import com.haruhanpan.space.SpaceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전체 데이터를 JSON 파일로 백업한다 (data-dir/backup/).
 * <ul>
 *   <li>매일: haruhanpan_YYYY-MM-DD.json (하루 1개, 서버 시작 시에도 오늘 것이 없으면 생성)</li>
 *   <li>가져오기 직전: haruhanpan_YYYY-MM-DD_HHmmss_before-import.json</li>
 *   <li>보관 기간(backup-keep-days)이 지난 파일은 삭제</li>
 * </ul>
 * 파일 형식: {exportedAt, spaces: [{id, name, kind, data, ledger}]}. 공간 하나를 복원할 때는
 * 그 공간의 data 부분을 화면의 "가져오기"로 올리면 된다 (화면이 공간을 골라 준다).
 */
@Service
public class BackupService {

	private static final Logger log = LoggerFactory.getLogger(BackupService.class);
	private static final String PREFIX = "haruhanpan_";

	private final DashboardService dashboard;
	private final SpaceRepository spaces;
	private final ActivityRepository activity;
	private final JsonMapper json;
	private final Path backupDir;
	private final int keepDays;

	private final AccountService accounts;
	private final LedgerService ledger;

	public BackupService(DashboardService dashboard, SpaceRepository spaces, ActivityRepository activity,
			AccountService accounts, LedgerService ledger, JsonMapper json, HaruProperties props) {
		this.accounts = accounts;
		this.ledger = ledger;
		this.dashboard = dashboard;
		this.spaces = spaces;
		this.activity = activity;
		this.json = json;
		this.backupDir = props.dataDir().resolve("backup");
		this.keepDays = props.backupKeepDays();
	}

	/** 모든 공간의 데이터 */
	Map<String, Object> dumpAll() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Map<String, Object> s : spaces.allSpaces()) {
			Map<String, Object> entry = new LinkedHashMap<>(s);
			entry.put("data", dashboard.exportSpace((String) s.get("id")));
			entry.put("ledger", ledger.exportSpace((String) s.get("id"))); // 가계부 (가져오기 대상은 아님)
			list.add(entry);
		}
		Map<String, Object> dump = new LinkedHashMap<>();
		dump.put("exportedAt", Instant.now().toString());
		dump.put("spaces", list);
		return dump;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(cron = "0 10 3 * * *")
	public void dailyBackup() {
		try {
			Path file = backupDir.resolve(PREFIX + LocalDate.now() + ".json");
			if (!Files.exists(file)) {
				write(file);
				log.info("일일 백업 생성: {}", file);
			}
			deleteOld();
			activity.deleteOlderThan(Instant.now().minus(java.time.Duration.ofDays(90)));
			accounts.purgeStalePending();
		} catch (RuntimeException e) {
			log.error("일일 백업 실패: {}", e.getMessage());
		}
	}

	/** 지금 데이터를 별도 이름으로 백업한다. 실패하면 예외 (가져오기를 진행하지 않도록). */
	public Path snapshot(String reason) {
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
		Path file = backupDir.resolve(PREFIX + stamp + "_" + reason + ".json");
		write(file);
		log.info("백업 생성: {}", file);
		return file;
	}

	private void write(Path file) {
		try {
			Files.createDirectories(backupDir);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), dumpAll());
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void deleteOld() {
		LocalDate cutoff = LocalDate.now().minusDays(keepDays);
		try (Stream<Path> files = Files.list(backupDir)) {
			files.filter(f -> f.getFileName().toString().startsWith(PREFIX))
				.filter(f -> fileDate(f).isBefore(cutoff))
				.forEach(f -> {
					try {
						Files.delete(f);
					} catch (IOException e) {
						log.warn("오래된 백업 삭제 실패: {}", f);
					}
				});
		} catch (IOException e) {
			log.warn("백업 폴더 정리 실패: {}", e.getMessage());
		}
	}

	private static LocalDate fileDate(Path file) {
		String name = file.getFileName().toString();
		try {
			return LocalDate.parse(name.substring(PREFIX.length(), PREFIX.length() + 10));
		} catch (RuntimeException e) {
			return LocalDate.MAX; // 이름 형식이 다르면 지우지 않음
		}
	}
}
