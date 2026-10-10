package com.devforge.ai.analyticsservice.service;

import com.devforge.ai.analyticsservice.repository.AuditChainHeadRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes each audit chain's head to the log stream, so a copy exists outside the database.
 *
 * <p>A hash chain shows an edit only to someone holding an earlier head: whoever can write the
 * database can otherwise recompute the whole chain. The log stream is that holder - in a real
 * deployment it is shipped to a store the database's operators do not control - so a later
 * verification that ends on a different hash for the same length shows history was rewritten.
 *
 * <p>Only chains that moved since the last run are logged, so a quiet system logs nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainAnchor {

  private final AuditChainHeadRepository heads;
  private final Map<String, Long> lastAnchored = new HashMap<>();

  @Scheduled(
      initialDelayString = "${devforge.audit.anchor-interval-ms:3600000}",
      fixedDelayString = "${devforge.audit.anchor-interval-ms:3600000}")
  public void anchor() {
    try {
      anchorChangedHeads();
    } catch (RuntimeException ex) {
      // A scheduled method that throws can stop being rescheduled; an anchor that silently stops
      // is the gap this exists to close.
      log.error("Could not anchor the audit chain heads; will retry on the next run", ex);
    }
  }

  /** @return how many chain heads were written. */
  public synchronized int anchorChangedHeads() {
    int written = 0;
    for (var head : heads.findAll()) {
      var previous = lastAnchored.get(head.getChainKey());
      if (previous != null && previous == head.getLastSequence()) {
        continue;
      }
      // One line per chain, in a fixed shape a log pipeline can index: the chain, its length and
      // the hash it ends on. Nothing else - no event content, no names.
      log.info("audit-chain-anchor chain={} sequence={} head={}",
          head.getChainKey(), head.getLastSequence(), head.getLastHash());
      lastAnchored.put(head.getChainKey(), head.getLastSequence());
      written++;
    }
    return written;
  }
}
