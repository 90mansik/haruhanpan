package com.haruhanpan.ledger;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 00:05(한국 시간)와 서버 시작 시, 결제일이 된 정기결제의 내역을 만든다.
 * 서버가 꺼져 있던 동안 지난 결제일도 이때 채운다. 정기결제마다 따로 처리해서 하나가 실패해도 나머지는 기록한다.
 */
@Component
public class LedgerRecurringJob {

	private static final Logger log = LoggerFactory.getLogger(LedgerRecurringJob.class);

	private final LedgerService ledger;

	public LedgerRecurringJob(LedgerService ledger) {
		this.ledger = ledger;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
	public void run() {
		LocalDate today = LedgerService.today();
		int created = 0;
		for (String id : ledger.activeRecurringIds(today)) {
			try {
				created += ledger.runRecurring(id, today);
			} catch (RuntimeException e) {
				log.warn("정기결제 기록 실패 {}: {}", id, e.getMessage());
			}
		}
		if (created > 0) {
			log.info("정기결제 내역 {}건 기록", created);
		}
	}
}
