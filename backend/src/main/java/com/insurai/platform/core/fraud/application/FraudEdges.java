package com.insurai.platform.core.fraud.application;

import com.insurai.platform.core.fraud.domain.FraudGraph;

import java.util.List;

/** Port: every (holder, party) pair ever seen on a claim, for rebuilding the graph from the source of truth. */
public interface FraudEdges {

    List<FraudGraph.Edge> all();
}
