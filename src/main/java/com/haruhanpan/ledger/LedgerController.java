package com.haruhanpan.ledger;

import com.haruhanpan.account.AppUser;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 가계부 화면과 API. 대시보드와 달리 전체를 한 번에 받지 않고 한 달씩 받는다.
 * <pre>
 * GET    /ledger                                          화면 (ledger.html)
 * GET    /api/ledger/meta                                 나, 내 공간과 멤버
 * GET    /api/ledger/spaces/{sid}?month=YYYY-MM           한 달 치 {settings, categories, from, to, entries, summary,
 *                                                         recurring, recurringRuns, savingTotals}
 * GET    /api/ledger/spaces/{sid}/year?year=YYYY          한 해 {months: [달마다 합계], summary, savingByMajor, savingTotals}
 * PUT    /api/ledger/spaces/{sid}/entries/{id}            내역 1건 저장 → {version}. version 이 다르면 409
 * DELETE /api/ledger/spaces/{sid}/entries/{id}            내역 1건 삭제
 * PUT    /api/ledger/spaces/{sid}/settings                분류·결제수단·월 시작일 저장
 * PUT    /api/ledger/spaces/{sid}/recurring/{id}          정기결제 저장 → {version} (지난 결제일이 있으면 바로 기록)
 * DELETE /api/ledger/spaces/{sid}/recurring/{id}          정기결제 삭제 (이미 만든 내역은 남김)
 * </pre>
 */
@Controller
public class LedgerController {

	private final LedgerService service;

	public LedgerController(LedgerService service) {
		this.service = service;
	}

	@GetMapping("/ledger")
	public String page() {
		return "forward:/ledger.html";
	}

	@GetMapping("/api/ledger/meta")
	@ResponseBody
	public Map<String, Object> meta(AppUser me) {
		return service.meta(me);
	}

	@GetMapping("/api/ledger/spaces/{sid}")
	@ResponseBody
	public Map<String, Object> month(AppUser me, @PathVariable String sid, @RequestParam String month) {
		return service.month(me, sid, month);
	}

	@GetMapping("/api/ledger/spaces/{sid}/year")
	@ResponseBody
	public Map<String, Object> year(AppUser me, @PathVariable String sid, @RequestParam int year) {
		return service.year(me, sid, year);
	}

	@PutMapping("/api/ledger/spaces/{sid}/entries/{id}")
	@ResponseBody
	public Map<String, Object> saveEntry(AppUser me, @PathVariable String sid, @PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return Map.of("ok", true, "version", service.saveEntry(me, sid, id, body));
	}

	@DeleteMapping("/api/ledger/spaces/{sid}/entries/{id}")
	@ResponseBody
	public Map<String, Object> deleteEntry(AppUser me, @PathVariable String sid, @PathVariable String id) {
		service.deleteEntry(me, sid, id);
		return Map.of("ok", true);
	}

	@PutMapping("/api/ledger/spaces/{sid}/recurring/{id}")
	@ResponseBody
	public Map<String, Object> saveRecurring(AppUser me, @PathVariable String sid, @PathVariable String id,
			@RequestBody Map<String, Object> body) {
		return Map.of("ok", true, "version", service.saveRecurring(me, sid, id, body));
	}

	@DeleteMapping("/api/ledger/spaces/{sid}/recurring/{id}")
	@ResponseBody
	public Map<String, Object> deleteRecurring(AppUser me, @PathVariable String sid, @PathVariable String id) {
		service.deleteRecurring(me, sid, id);
		return Map.of("ok", true);
	}

	@PutMapping("/api/ledger/spaces/{sid}/settings")
	@ResponseBody
	public Map<String, Object> saveSettings(AppUser me, @PathVariable String sid, @RequestBody Map<String, Object> body) {
		service.saveSettings(me, sid, body);
		return Map.of("ok", true);
	}
}
