package com.haruhanpan.wiki;

import com.haruhanpan.account.AppUser;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceService;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 하루위키 이미지.
 * <pre>
 * POST /api/spaces/{sid}/files?page={페이지 id}   (multipart "file", 편집 권한) → Editor.js 이미지 도구 형식 {success, file: {url}}
 * GET  /files/{id}                                (그 공간 멤버만) 이미지 내용
 * </pre>
 */
@RestController
public class WikiFileController {

	private final WikiFiles files;
	private final SpaceService spaces;

	public WikiFileController(WikiFiles files, SpaceService spaces) {
		this.files = files;
		this.spaces = spaces;
	}

	@PostMapping("/api/spaces/{sid}/files")
	public Map<String, Object> upload(AppUser me, @PathVariable String sid, @RequestParam(required = false) String page,
			@RequestParam("file") MultipartFile file) throws IOException {
		spaces.require(me, sid, Role.EDITOR);
		String id = files.store(sid, page, file.getOriginalFilename(), file.getBytes(), me.id());
		return Map.of("success", 1, "file", Map.of("url", "/files/" + id, "id", id));
	}

	@GetMapping("/files/{id}")
	public ResponseEntity<Resource> download(AppUser me, @PathVariable String id) {
		WikiFiles.StoredFile f = files.require(id);
		spaces.require(me, f.spaceId(), Role.VIEWER);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(f.mime()))
			.contentLength(f.bytes())
			// 같은 id 의 내용은 바뀌지 않는다. 공유 캐시에는 남기지 않는다
			.cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'")
			.body(new FileSystemResource(f.path()));
	}
}
