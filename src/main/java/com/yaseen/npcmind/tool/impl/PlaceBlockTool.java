package com.yaseen.npcmind.tool.impl;

import com.yaseen.npcmind.entity.CompanionEntity;
import com.yaseen.npcmind.security.WorldActionSecurity;
import com.yaseen.npcmind.state.ActionPermissions;
import com.yaseen.npcmind.state.SkillProgression;
import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.JsonArgs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coordinate-free block placing. A weak local model almost never produces correct x/y/z, so this
 * tool just needs a block KEYWORD: it finds a matching real {@link BlockItem} in the companion
 * inventory and places up to {@code count} copies on valid ground positions in front of the
 * companion. This is what makes "text/text <status update>" work for any block the player owns.
 */
public final class PlaceBlockTool implements AITool {
    public String name(){ return "place_block"; }
    public String description(){ return "Place blocks from the companion's own inventory WITHOUT coordinates. Give a block keyword (e.g. stone, dirt, cobblestone, planks, torch, chest) and an optional count; the companion places that many on valid ground spots in front of it, consuming the real items."; }
    public String schema(){ return "{\"block\":\"keyword\",\"count\":number(optional)}"; }

    public String execute(CompanionEntity c, ServerPlayer p, String a){
        if(!c.getPermissions().allowed(ActionPermissions.Action.BUILD)) return "DENIED permission=build";
        if (!(c.getOwnerPlayer() instanceof ServerPlayer owner)) return "DENIED owner is not online; world mutation is blocked";
        String keyword = JsonArgs.string(a, "block");
        if(keyword == null || keyword.isBlank()) return "FAILED block keyword is required";
        keyword = keyword.toLowerCase(Locale.ROOT).trim();
        Integer req = JsonArgs.integer(a, "count");
        int count = req == null ? 1 : Math.max(1, Math.min(64, req));

        int slot = findBlockSlot(c, keyword);
        if(slot < 0) return "FAILED no_block_in_inventory=" + keyword;
        Block block = Block.byItem(c.getCompanionInventory().getItem(slot).getItem());
        if(block == null) return "FAILED not_a_placeable_block=" + keyword;

        List<BlockPos> spots = candidateSpots(c);
        int placed = 0;
        for(BlockPos pos : spots){
            if(placed >= count) break;
            int s = findBlockSlot(c, keyword);
            if(s < 0) break;
            BlockState state = block.defaultBlockState();
            if(!state.canSurvive(c.level(), pos)) continue;
            if(!WorldActionSecurity.canPlaceBlock(c, owner, pos)) continue;
            if(c.level().setBlockAndUpdate(pos, state)){
                c.playAnimationAction((byte) 2, 4);
                c.getCompanionInventory().removeItem(s, 1);
                c.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                        30.0F, 30.0F);
                c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
                placed++;
            }
        }
        if(placed > 0){
            c.getSkills().add(SkillProgression.Skill.BUILDING, placed * 3);
            c.getCompanionState().positiveEvent();
            return "SUCCESS placed=" + placed + " block=" + keyword;
        }
        return "FAILED no_valid_spot block=" + keyword;
    }

    /** Find an inventory slot holding a BlockItem that best matches the keyword. */
    static int findBlockSlot(CompanionEntity c, String keyword){
        int best = -1, bestScore = 0;
        for(int i=0;i<c.getCompanionInventory().getContainerSize();i++){
            ItemStack st = c.getCompanionInventory().getItem(i);
            if(st.isEmpty() || !(st.getItem() instanceof BlockItem)) continue;
            String path = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem()).getPath().toLowerCase(Locale.ROOT);
            int score;
            if(path.equals(keyword)) score = 4;
            else if(path.startsWith(keyword + "_") || path.endsWith("_" + keyword)) score = 3;
            else if(path.contains("_" + keyword + "_")) score = 2;
            else if(path.contains(keyword)) score = 1;
            else score = 0;
            if(score > bestScore){ bestScore = score; best = i; }
        }
        return best;
    }

    /** Replaceable ground positions in front of and around the companion, nearest first. */
    static List<BlockPos> candidateSpots(CompanionEntity c){
        Level lvl = c.level();
        BlockPos base = c.blockPosition();
        Direction facing = c.getDirection();
        List<BlockPos> out = new ArrayList<>();
        for(int step=1; step<=6; step++){
            BlockPos ahead = base.relative(facing, step);
            BlockPos spot = groundSpot(lvl, ahead);
            if(spot != null) out.add(spot);
        }
        for(int dx=-2; dx<=2; dx++) for(int dz=-2; dz<=2; dz++){
            if(dx==0 && dz==0) continue;
            BlockPos spot = groundSpot(lvl, base.offset(dx,0,dz));
            if(spot != null && !out.contains(spot)) out.add(spot);
        }
        return out;
    }

    private static BlockPos groundSpot(Level lvl, BlockPos at){
        for(int dy=1; dy>=-2; dy--){
            BlockPos pos = at.above(dy);
            if(!lvl.isLoaded(pos)) continue;
            BlockState here = lvl.getBlockState(pos);
            BlockState below = lvl.getBlockState(pos.below());
            boolean replaceable = here.canBeReplaced() || here.isAir();
            boolean floored = !below.isAir() && below.isFaceSturdy(lvl, pos.below(), Direction.UP);
            if(replaceable && floored) return pos.immutable();
        }
        return null;
    }
}