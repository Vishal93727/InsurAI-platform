package com.insurai.platform.fraudengine.domain.model;

/**
 * Terminal outcome of the fraud pipeline for a single transaction.
 */
public enum Decision {
    PASS,
    BLOCK
}
