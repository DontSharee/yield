package me.dontshare.yieldpacks.display;

/** Parsed pet-display.yml - the equip-strip formation, hover, and packet-cadence tuning. */
public record PetDisplayConfig(
        int gridColumns, double columnSpacing, double rowSpacing, double startDistance, double heightOffset,
        double hoverAmplitude, int hoverPeriodTicks, double movementThreshold,
        int updateIntervalTicks, double viewDistance, float scale, float pitchDegrees, float yawDegrees) {
}
