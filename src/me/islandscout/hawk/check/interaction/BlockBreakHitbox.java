/*
 * This file is part of Hawk Anticheat.
 * Copyright (C) 2018 Hawk Development Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.islandscout.hawk.check.interaction;

import me.islandscout.hawk.HawkPlayer;
import me.islandscout.hawk.check.BlockDigCheck;
import me.islandscout.hawk.event.BlockDigEvent;
import me.islandscout.hawk.util.*;
import me.islandscout.hawk.util.block.BlockNMS;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

public class BlockBreakHitbox extends BlockDigCheck {

    private final boolean DEBUG_HITBOX;
    private final boolean DEBUG_RAY;
    private final boolean CHECK_DIG_START;
    private final boolean CHECK_DIG_CANCEL;
    private final boolean CHECK_DIG_COMPLETE;
    private final double MAX_REACH;
    private final boolean CHECK_OCCLUSION;
    private final boolean ALWAYS_CANCEL_OCCLUSION;

    public BlockBreakHitbox() {
        super("blockbreakhitbox", true, 5, 10, 0.9, 5000, "%player% failed block break hitbox. %type% VL: %vl%", null);
        DEBUG_HITBOX = (boolean) customSetting("hitbox", "debug", false);
        DEBUG_RAY = (boolean) customSetting("ray", "debug", false);
        CHECK_DIG_START = (boolean) customSetting("checkDigStart", "", false);
        CHECK_DIG_CANCEL = (boolean) customSetting("checkDigCancel", "", false);
        CHECK_DIG_COMPLETE = (boolean) customSetting("checkDigComplete", "", true);
        MAX_REACH = (double) customSetting("maxReach", "", 6.0);
        CHECK_OCCLUSION = (boolean) customSetting("enabled", "checkOccluding", true);
        ALWAYS_CANCEL_OCCLUSION = (boolean) customSetting("alwaysCancel", "checkOccluding", true);
    }

    @Override
    protected void check(BlockDigEvent e) {
        Player p = e.getPlayer();
        HawkPlayer pp = e.getHawkPlayer();
        Location eyeLoc = new Location(pp.getWorld(), pp.getPosition().getX(), pp.getPosition().getY(), pp.getPosition().getZ(), pp.getYaw(), pp.getPitch()).add(0, 1.62, 0);
        Location bLoc = e.getBlock().getLocation();

        switch (e.getDigAction()) {
            case START:
                if(CHECK_DIG_START || (p.getGameMode() == GameMode.CREATIVE && CHECK_DIG_COMPLETE))
                    break;
                return;
            case CANCEL:
                if(CHECK_DIG_CANCEL)
                    break;
                return;
            case COMPLETE:
                if(CHECK_DIG_COMPLETE)
                    break;
                return;
        }

        if (p.isSneaking())
            eyeLoc.add(0, -0.08, 0);

        //Extrapolate last position. (For 1.7 clients ONLY)
        //Unfortunately, there will be false positives from 1.7 users due to the nature of how the client interacts
        //with entities. There is no effective way to stop these false positives without creating bypasses.
        if (ServerUtils.getClientVersion(p) == 7) {
            Vector attackerVelocity = pp.getVelocity().clone();
            Vector attackerDeltaRotation = new Vector(pp.getDeltaYaw(), pp.getDeltaPitch(), 0);
            double moveDelay = System.currentTimeMillis() - pp.getLastMoveTime();
            if (moveDelay >= 100) {
                moveDelay = 0D;
            } else {
                moveDelay = moveDelay / 50;
            }
            attackerVelocity.multiply(moveDelay);
            attackerDeltaRotation.multiply(moveDelay);

            eyeLoc.add(attackerVelocity);

            eyeLoc.setYaw(eyeLoc.getYaw() + (float) attackerDeltaRotation.getX());
            eyeLoc.setPitch(eyeLoc.getPitch() + (float) attackerDeltaRotation.getY());
        }

        Vector min = bLoc.toVector();
        Vector max = bLoc.toVector().add(new Vector(1, 1, 1));
        AABB aabb = new AABB(min, max);
        Vector direction = eyeLoc.getDirection();
        Ray ray = new Ray(eyeLoc.toVector(), direction);

        if (DEBUG_HITBOX)
            aabb.highlight(hawk, p.getWorld(), 0.25);
        if (DEBUG_RAY)
            ray.highlight(hawk, p.getWorld(), MAX_REACH, 0.3);

        Vector intersection = aabb.intersectsRay(ray, 0, Float.MAX_VALUE);

        if (intersection == null) {
            punishAndTryCancelAndBlockRespawn(pp, 1, e, new Placeholder("type", "Did not hit hitbox."));
            return;
        }

        double distance = new Vector(intersection.getX() - eyeLoc.getX(), intersection.getY() - eyeLoc.getY(), intersection.getZ() - eyeLoc.getZ()).length();

        if (CHECK_OCCLUSION) {
            Vector eyePos = eyeLoc.toVector();
            BlockIterator iter = new BlockIterator(p.getWorld(), eyePos, direction, 0, (int) distance + 2);
            while (iter.hasNext()) {
                Block bukkitBlock = iter.next();

                if (bukkitBlock.getType() == Material.AIR || bukkitBlock.isLiquid())
                    continue;
                if (bukkitBlock.getLocation().equals(bLoc))
                    break;

                BlockNMS b = BlockNMS.getBlockNMS(bukkitBlock);
                AABB checkIntersection = new AABB(b.getHitBox().getMin(), b.getHitBox().getMax());
                Vector occludeIntersection = checkIntersection.intersectsRay(new Ray(eyePos, direction), 0, Float.MAX_VALUE);
                if (occludeIntersection != null) {
                    if (occludeIntersection.distance(eyePos) < distance) {
                        Placeholder ph = new Placeholder("type", "Interacted through " + b.getBukkitBlock().getType());
                        if(ALWAYS_CANCEL_OCCLUSION) {
                            punish(pp, 1, true, e, ph);
                            e.setCancelled(true);
                            blockRespawn(pp, e);
                        } else {
                            punishAndTryCancelAndBlockRespawn(pp, 1, e, ph);
                        }
                        return;
                    }
                }
            }

        }

        if (distance > MAX_REACH) {
            punishAndTryCancelAndBlockRespawn(pp, 1, e, new Placeholder("type", "Reached too far."));
            return;
        }

        reward(pp);
    }
}
