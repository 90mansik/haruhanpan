package com.haruhanpan.data;

import com.haruhanpan.account.AccountService;
import com.haruhanpan.account.AppUser;
import com.haruhanpan.common.ApiErrors.NotFound;
import com.haruhanpan.google.GoogleSupport;
import com.haruhanpan.google.IdentityRepository;
import com.haruhanpan.ops.BackupService;
import com.haruhanpan.space.Role;
import com.haruhanpan.space.SpaceService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면용 REST API. 변경은 모두 공간 안에서 1건 단위.
 * <pre>
 * GET        /api/all                                     내 모든 공간의 데이터 (30초마다 다시 불러 동기화)
 * PUT|POST   /api/spaces/{sid}/{todos|events|notes|snippets|routines}/{id}   1건 저장 → {version}
 *            본문의 version 이 서버와 다르면 409 (다른 사람이 먼저 수정함)
 * DELETE     /api/spaces/{sid}/{컬렉션}/{id}                1건 삭제
 * POST       /api/spaces/{sid}/{컬렉션}/{id}/move {to}      다른 공간으로 옮기기 (루틴 제외)
 * PUT        /api/spaces/{sid}/routines/order   [id,...]  루틴 순서
 * PUT|DELETE /api/spaces/{sid}/routineChecks/{date}/{rid} 내 루틴 체크/해제
 * PUT|DELETE /api/spaces/{sid}/dayOffs/{date}  {name}     공간 휴무일 지정/해제
 * POST       /api/spaces/{sid}/import                     공간 전체 교체 (관리자, 교체 전 백업)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class DataController {

	private final DashboardService service;
	private final BackupService backup;
	private final SpaceService spaces;
	private final GoogleSupport google;
	private final IdentityRepository identities;

	private final AccountService accounts;

	public DataController(DashboardService service, BackupService backup, SpaceService spaces, GoogleSupport google,
			IdentityRepository identities, AccountService accounts) {
		this.service = service;
		this.backup = backup;
		this.spaces = spaces;
		this.google = google;
		this.identities = identities;
		this.accounts = accounts;
	}

	@GetMapping("/all")
	public Map<String, Object> all(AppUser me) {
		Map<String, Object> data = service.all(me);
		data.put("google", Map.of("enabled", google.enabled(), "email", identities.emailOf(me.id()).orElse("")));
		if (me.admin()) {
			data.put("pendingUsers", accounts.pending(me)); // 가입 승인 대기 (사이트 관리자에게만)
		}
		return data;
	}

	@PutMapping("/spaces/{sid}/routines/order")
	public Map<String, Object> routineOrder(AppUser me, @PathVariable String sid, @RequestBody List<String> ids) {
		service.setRoutineOrder(me, sid, ids);
		return ok();
	}

	@RequestMapping(path = "/spaces/{sid}/{collection}/{id}", method = {RequestMethod.PUT, RequestMethod.POST})
	public Map<String, Object> upsert(AppUser me, @PathVariable String sid, @PathVariable String collection,
			@PathVariable String id, @RequestBody Map<String, Object> item) {
		long version = service.upsert(me, sid, collectionOf(collection), id, item);
		return Map.of("ok", true, "version", version);
	}

	/** 다른 공간으로 옮기기 {to} */
	@PostMapping("/spaces/{sid}/{collection}/{id}/move")
	public Map<String, Object> move(AppUser me, @PathVariable String sid, @PathVariable String collection,
			@PathVariable String id, @RequestBody Map<String, String> body) {
		service.move(me, sid, collectionOf(collection), id, body.get("to"));
		return ok();
	}

	@DeleteMapping("/spaces/{sid}/{collection}/{id}")
	public Map<String, Object> delete(AppUser me, @PathVariable String sid, @PathVariable String collection,
			@PathVariable String id) {
		service.delete(me, sid, collectionOf(collection), id);
		return ok();
	}

	@PutMapping("/spaces/{sid}/routineChecks/{date}/{routineId}")
	public Map<String, Object> check(AppUser me, @PathVariable String sid, @PathVariable String date,
			@PathVariable String routineId) {
		service.setRoutineCheck(me, sid, date, routineId, true);
		return ok();
	}

	@DeleteMapping("/spaces/{sid}/routineChecks/{date}/{routineId}")
	public Map<String, Object> uncheck(AppUser me, @PathVariable String sid, @PathVariable String date,
			@PathVariable String routineId) {
		service.setRoutineCheck(me, sid, date, routineId, false);
		return ok();
	}

	@PutMapping("/spaces/{sid}/dayOffs/{date}")
	public Map<String, Object> setDayOff(AppUser me, @PathVariable String sid, @PathVariable String date,
			@RequestBody Map<String, Object> body) {
		Object name = body.get("name");
		service.setDayOff(me, sid, date, name == null ? "" : name.toString());
		return ok();
	}

	@DeleteMapping("/spaces/{sid}/dayOffs/{date}")
	public Map<String, Object> deleteDayOff(AppUser me, @PathVariable String sid, @PathVariable String date) {
		service.setDayOff(me, sid, date, "");
		return ok();
	}

	@PostMapping("/spaces/{sid}/import")
	public Map<String, Object> importSpace(AppUser me, @PathVariable String sid, @RequestBody Map<String, Object> data) {
		spaces.require(me, sid, Role.OWNER); // 권한 없는 요청으로 백업 파일이 쌓이지 않도록 먼저 확인
		backup.snapshot("before-import");
		service.replaceSpace(me, sid, data);
		return ok();
	}

	private static Map<String, Object> ok() {
		return Map.of("ok", true);
	}

	private static Collection collectionOf(String name) {
		return Collection.fromJs(name).orElseThrow(() -> new NotFound("not found"));
	}
}
