package com.haruhanpan.wiki;

import com.haruhanpan.common.ApiErrors.NotFound;
import com.haruhanpan.common.Ids;
import com.haruhanpan.config.HaruProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 하루위키 이미지 파일. 내용은 데이터 폴더의 files/{id} 에, 정보는 wiki_files 표에 둔다.
 * 권한 검사는 하지 않는다 (부르는 쪽이 SpaceService.require 로 먼저 확인한다).
 * <ul>
 *   <li>이미지(PNG·JPEG·GIF·WebP)만 받는다. 파일 이름이 아니라 앞부분 바이트로 형식을 확인한다 (SVG 는 스크립트가 들어갈 수 있어 받지 않음)</li>
 *   <li>디스크에서 지우는 것은 DB 트랜잭션이 끝난 뒤에 한다 (되돌려졌는데 파일만 사라지는 일이 없도록)</li>
 *   <li>페이지에서 지운 이미지는 하루 뒤 정리한다 ({@link #purgeUnreferenced}, 일일 백업 때)</li>
 * </ul>
 */
@Service
public class WikiFiles {

	private static final Logger log = LoggerFactory.getLogger(WikiFiles.class);
	public static final long MAX_BYTES = 10L * 1024 * 1024;

	private final JdbcClient jdbc;
	private final Path dir;

	public WikiFiles(JdbcClient jdbc, HaruProperties props) {
		this.jdbc = jdbc;
		this.dir = props.dataDir().resolve("files");
	}

	public record StoredFile(String id, String spaceId, String mime, long bytes, Path path) {
	}

	/** 이미지 하나를 저장하고 id 를 돌려준다. 이미지가 아니거나 너무 크면 IllegalArgumentException */
	public String store(String spaceId, String pageId, String name, byte[] data, String userId) {
		if (data.length == 0 || data.length > MAX_BYTES) {
			throw new IllegalArgumentException("이미지는 10MB 까지");
		}
		String mime = imageType(data);
		if (mime == null) {
			throw new IllegalArgumentException("PNG·JPEG·GIF·WebP 이미지만 올릴 수 있습니다");
		}
		String id = Ids.newId("f");
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve(id + ".tmp");
			Files.write(tmp, data);
			Files.move(tmp, dir.resolve(id));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		jdbc.sql("""
				INSERT INTO wiki_files (id, space_id, page_id, name, mime, bytes, created_by, created_at)
				VALUES (:id, :sid, :page, :name, :mime, :bytes, :uid, :now)
				""")
			.param("id", id).param("sid", spaceId).param("page", pageId == null ? "" : pageId)
			.param("name", name == null ? "" : name.length() > 200 ? name.substring(0, 200) : name)
			.param("mime", mime).param("bytes", data.length).param("uid", userId).param("now", Instant.now().toString())
			.update();
		return id;
	}

	public Optional<StoredFile> find(String id) {
		return jdbc.sql("SELECT id, space_id, mime, bytes FROM wiki_files WHERE id = :id").param("id", id)
			.query((rs, n) -> new StoredFile(rs.getString("id"), rs.getString("space_id"), rs.getString("mime"),
					rs.getLong("bytes"), dir.resolve(rs.getString("id"))))
			.optional()
			.filter(f -> Files.isRegularFile(f.path()));
	}

	public StoredFile require(String id) {
		return find(id).orElseThrow(() -> new NotFound("not found"));
	}

	/** 페이지들(하위 포함)을 지울 때 그 페이지들의 이미지도 지운다 */
	public void deleteForPages(String spaceId, Collection<String> pageIds) {
		if (pageIds.isEmpty()) {
			return;
		}
		List<String> ids = jdbc.sql("SELECT id FROM wiki_files WHERE space_id = :sid AND page_id IN (:pages)")
			.param("sid", spaceId).param("pages", pageIds).query(String.class).list();
		deleteRows(ids);
	}

	/** 페이지들을 다른 공간으로 옮길 때 이미지도 같이 옮긴다 (권한 검사가 공간 기준이라서) */
	public void moveToSpace(String fromSpace, Collection<String> pageIds, String toSpace) {
		if (!pageIds.isEmpty()) {
			jdbc.sql("UPDATE wiki_files SET space_id = :to WHERE space_id = :from AND page_id IN (:pages)")
				.param("to", toSpace).param("from", fromSpace).param("pages", pageIds).update();
		}
	}

	public void deleteAllIn(String spaceId) {
		deleteRows(jdbc.sql("SELECT id FROM wiki_files WHERE space_id = :sid").param("sid", spaceId).query(String.class).list());
	}

	/** 하루 넘게 어느 페이지에도 쓰이지 않은 이미지를 지운다. @return 지운 수 */
	public int purgeUnreferenced() {
		String before = Instant.now().minus(Duration.ofDays(1)).toString();
		List<Map<String, Object>> old = jdbc.sql("SELECT id, space_id FROM wiki_files WHERE created_at < :before")
			.param("before", before)
			.query((rs, n) -> Map.<String, Object>of("id", rs.getString("id"), "sid", rs.getString("space_id")))
			.list();
		List<String> unused = old.stream().filter(f -> jdbc.sql(
				"SELECT COUNT(*) FROM notes WHERE space_id = :sid AND content LIKE :pat")
			.param("sid", f.get("sid")).param("pat", "%/files/" + f.get("id") + "%")
			.query(Integer.class).single() == 0)
			.map(f -> (String) f.get("id")).toList();
		deleteRows(unused);
		return unused.size();
	}

	private void deleteRows(List<String> ids) {
		if (ids.isEmpty()) {
			return;
		}
		jdbc.sql("DELETE FROM wiki_files WHERE id IN (:ids)").param("ids", ids).update();
		Runnable removeFiles = () -> ids.forEach(id -> {
			try {
				Files.deleteIfExists(dir.resolve(id));
			} catch (IOException e) {
				log.warn("위키 이미지 삭제 실패 {}: {}", id, e.getMessage());
			}
		});
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					removeFiles.run();
				}
			});
		} else {
			removeFiles.run();
		}
	}

	/** 앞부분 바이트로 이미지 형식을 판별한다. 모르면 null */
	static String imageType(byte[] b) {
		if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
			return "image/png";
		}
		if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
			return "image/jpeg";
		}
		if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
			return "image/gif";
		}
		if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
				&& b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
			return "image/webp";
		}
		return null;
	}
}
