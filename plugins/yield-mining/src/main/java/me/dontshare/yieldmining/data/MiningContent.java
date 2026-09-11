package me.dontshare.yieldmining.data;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record MiningContent(Map<Material, OreDefinition> ores, List<MiningSpot> spots) {
}
