package me.dontshare.yieldpackstations.data;

import java.util.List;

/** The black market's rotation settings - see {@code BlackMarketRotationService}. */
public record BlackMarketConfig(long resetIntervalMillis, List<String> eligiblePackIds) {
}
