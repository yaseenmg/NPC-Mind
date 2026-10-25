package com.yaseen.npcmind.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * World-persistent lifecycle data for companions. Unlike Player persistent NBT,
 * this survives the owner being offline or dead when the companion dies.
 */
public final class CompanionWorldData extends SavedData {
    private static final String DATA_NAME = "npcmind_companion_lifecycle";
    private static final String RECORDS = "records";
    private final CompoundTag records = new CompoundTag();

    public CompanionWorldData() {}

    public static CompanionWorldData load(CompoundTag tag) {
        CompanionWorldData data = new CompanionWorldData();
        if (tag.contains(RECORDS, 10)) data.records.merge(tag.getCompound(RECORDS));
        return data;
    }

    public static CompanionWorldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CompanionWorldData::load, CompanionWorldData::new, DATA_NAME);
    }

    private CompoundTag record(UUID owner) {
        String key = owner.toString();
        if (!records.contains(key, 10)) records.put(key, new CompoundTag());
        return records.getCompound(key);
    }

    public void saveRespawnPoint(UUID owner, ResourceLocation dimension, long x, long y, long z) {
        CompoundTag r = record(owner);
        r.putString("dimension", dimension.toString());
        r.putLong("x", x); r.putLong("y", y); r.putLong("z", z);
        setDirty();
    }

    public void markPending(UUID owner) {
        record(owner).putBoolean("pending", true);
        setDirty();
    }

    public boolean isPending(UUID owner) {
        return records.contains(owner.toString(), 10) && records.getCompound(owner.toString()).getBoolean("pending");
    }

    public CompoundTag getRecordCopy(UUID owner) {
        if (!records.contains(owner.toString(), 10)) return new CompoundTag();
        return records.getCompound(owner.toString()).copy();
    }

    public void saveSkills(UUID owner, ListTag skills) {
        record(owner).put("skills", skills.copy());
        setDirty();
    }

    public ListTag getSkills(UUID owner) {
        if (!records.contains(owner.toString(), 10)) return new ListTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("skills", 9) ? r.getList("skills", 10).copy() : new ListTag();
    }

    public void saveInventory(UUID owner, ListTag inventory) {
        record(owner).put("inventory", inventory.copy());
        setDirty();
    }

    public void clearInventory(UUID owner) {
        if (owner == null) return;
        CompoundTag r = record(owner);
        r.remove("inventory");
        setDirty();
    }

    public ListTag getInventory(UUID owner) {
        if (!records.contains(owner.toString(), 10)) return new ListTag();
        CompoundTag r = records.getCompound(owner.toString());
        return r.contains("inventory", 9) ? r.getList("inventory", 10).copy() : new ListTag();
    }

    public ResourceLocation dimension(UUID owner) {
        CompoundTag r = getRecordCopy(owner);
        String id = r.getString("dimension");
        return id.isBlank() ? null : ResourceLocation.tryParse(id);
    }

    public long x(UUID owner) { return getRecordCopy(owner).getLong("x"); }
    public long y(UUID owner) { return getRecordCopy(owner).getLong("y"); }
    public long z(UUID owner) { return getRecordCopy(owner).getLong("z"); }

    public void clearPending(UUID owner) {
        records.remove(owner.toString());
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put(RECORDS, records.copy());
        return tag;
    }
}
