package com.devforge.ai.authservice.service;

import com.devforge.ai.authservice.repository.AuditChainHeadRepository;
import com.devforge.ai.authservice.repository.AuditLogRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-hashes the account audit chain, and writes its head to the log stream.
 *
 * <p>Like analytics-service's project chains: verification finds an edited, reordered, deleted or
 * truncated entry, and the logged head is the copy outside the database that shows a wholesale
 * rewrite - once the logs are shipped somewhere the database's operators cannot change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainVerifier {

  private final AuditLogRepository entries;
  private final AuditChainHeadRepository heads;
  private final Map<String, Long> lastAnchored = new HashMap<>();

  public record Verification(
      boolean intact, long entries, long unchainedEntries, Long brokenAtSequence, String problem,
      String headHash) {}

  @Transactional(readOnly = true)
  public Verification verify() {
    var unchained = entries.countByChainSequenceIsNull();
    long checked = 0;
    var expectedPrevious = AuditChain.GENESIS;
    while (true) {
      var page = entries.findByChainSequenceGreaterThanOrderByChainSequenceAsc(checked, PageRequest.of(0, 500));
      for (var row : page) {
        var position = checked + 1;
        String problem = null;
        if (row.getChainSequence() != position) {
          problem = "the entry is missing";
        } else if (!expectedPrevious.equals(row.getPreviousHash())) {
          problem = "it does not follow the entry before it";
        } else if (!AuditChain.hash(row.getPreviousHash(), position, row).equals(row.getEntryHash())) {
          problem = "its content does not match its hash";
        }
        if (problem != null) {
          return new Verification(false, checked, unchained, position, problem, null);
        }
        expectedPrevious = row.getEntryHash();
        checked = position;
      }
      if (!page.hasNext()) {
        break;
      }
    }
    var head = heads.findById(AuditChain.KEY);
    var recorded = head.map(h -> h.getLastSequence()).orElse(0L);
    if (recorded != checked || (head.isPresent() && !head.get().getLastHash().equals(expectedPrevious))) {
      return new Verification(false, checked, unchained, checked + 1,
          "the chain stops short of the " + recorded + " entries it recorded", null);
    }
    return new Verification(true, checked, unchained, null, null, expectedPrevious);
  }

  @Scheduled(
      initialDelayString = "${devforge.audit.anchor-interval-ms:3600000}",
      fixedDelayString = "${devforge.audit.anchor-interval-ms:3600000}")
  public void anchor() {
    try {
      anchorIfMoved();
    } catch (RuntimeException ex) {
      log.error("Could not anchor the account audit chain head; will retry on the next run", ex);
    }
  }

  /** @return whether a line was written. */
  public synchronized boolean anchorIfMoved() {
    var head = heads.findById(AuditChain.KEY);
    if (head.isEmpty() || Long.valueOf(head.get().getLastSequence()).equals(lastAnchored.get(AuditChain.KEY))) {
      return false;
    }
    log.info("audit-chain-anchor chain={} sequence={} head={}",
        AuditChain.KEY, head.get().getLastSequence(), head.get().getLastHash());
    lastAnchored.put(AuditChain.KEY, head.get().getLastSequence());
    return true;
  }
}
