package com.devforge.ai.reviewservice.analysis;

import java.util.List;

/**
 * One analysis rule.
 *
 * <p>Rules are Spring beans, so adding one is adding a class — the engine collects every
 * implementation rather than naming them, and nothing has to be registered in two places.
 *
 * <p>A rule receives already-decoded text and must treat it as hostile input: repository content is
 * supplied by whoever can push. A rule must therefore never execute it, never interpolate it into
 * anything, and must stay linear in the size of its input. A regex that backtracks catastrophically
 * on a crafted line turns analysis into a denial of service, which is why the patterns in this
 * package avoid nested quantifiers.
 */
public interface AnalysisRule {

  List<Finding> analyse(AnalysedFile file);
}
