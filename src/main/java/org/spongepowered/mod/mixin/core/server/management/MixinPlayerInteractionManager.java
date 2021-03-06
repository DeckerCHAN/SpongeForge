/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.mod.mixin.core.server.management;

import com.flowpowered.math.vector.Vector3d;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockCommandBlock;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemDoor;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketCloseWindow;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.ILockableContainer;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.common.event.SpongeCommonEventFactory;
import org.spongepowered.common.registry.provider.DirectionFacingProvider;
import org.spongepowered.common.util.TristateUtil;

import java.util.Optional;

@Mixin(value = PlayerInteractionManager.class, priority = 1001)
public abstract class MixinPlayerInteractionManager {

    @Shadow public EntityPlayerMP thisPlayerMP;
    @Shadow public World theWorld;
    @Shadow private GameType gameType;

    @Shadow public abstract boolean isCreative();
    @Shadow public abstract EnumActionResult processRightClick(EntityPlayer player, net.minecraft.world.World worldIn, ItemStack stack, EnumHand hand);
    @Shadow(remap = false) public abstract double getBlockReachDistance();
    @Shadow(remap = false) public abstract void setBlockReachDistance(double distance);

    /**
     * @author gabizou - May 5th, 2016
     * @reason Rewrite the firing of interact block events with forge hooks
     * Note: This is a dirty merge of Aaron's SpongeCommon writeup of the interaction events and
     * Forge's additions. There's some overlay between the two events, specifically that there
     * is a SpongeEvent thrown before the ForgeEvent, and yet both are checked in various
     * if statements.
     */
    @Overwrite
    public EnumActionResult processRightClickBlock(EntityPlayer player, World worldIn, ItemStack stack, EnumHand hand, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (this.gameType == GameType.SPECTATOR) {
            TileEntity tileentity = worldIn.getTileEntity(pos);

            if (tileentity instanceof ILockableContainer) {
                Block block = worldIn.getBlockState(pos).getBlock();
                ILockableContainer ilockablecontainer = (ILockableContainer) tileentity;

                if (ilockablecontainer instanceof TileEntityChest && block instanceof BlockChest) {
                    ilockablecontainer = ((BlockChest) block).getLockableContainer(worldIn, pos);
                }

                if (ilockablecontainer != null) {
                    player.displayGUIChest(ilockablecontainer);
                    return EnumActionResult.SUCCESS;
                }
            } else if (tileentity instanceof IInventory) {
                player.displayGUIChest((IInventory) tileentity);
                return EnumActionResult.SUCCESS;
            }

            return EnumActionResult.PASS;
        } else {
            // Sponge Start - fire event, and revert the client if cancelled

            ItemStack oldStack = ItemStack.copyItemStack(stack);

            BlockSnapshot currentSnapshot = ((org.spongepowered.api.world.World) worldIn).createSnapshot(pos.getX(), pos.getY(), pos.getZ());
            InteractBlockEvent.Secondary event = SpongeCommonEventFactory.callInteractBlockEventSecondary(Cause.of(NamedCause.source(player)),
                    Optional.of(new Vector3d(hitX, hitY, hitZ)), currentSnapshot,
                    DirectionFacingProvider.getInstance().getKey(facing).get(), hand);

            if (event.isCancelled()) {
                final IBlockState state = worldIn.getBlockState(pos);

                if (state.getBlock() == Blocks.COMMAND_BLOCK) {
                    // CommandBlock GUI opens solely on the client, we need to force it close on cancellation
                    ((EntityPlayerMP) player).connection.sendPacket(new SPacketCloseWindow(0));

                } else if (state.getProperties().containsKey(BlockDoor.HALF)) {
                    // Stopping a door from opening while interacting the top part will allow the door to open, we need to update the
                    // client to resolve this
                    if (state.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.LOWER) {
                        ((EntityPlayerMP) player).connection.sendPacket(new SPacketBlockChange(worldIn, pos.up()));
                    } else {
                        ((EntityPlayerMP) player).connection.sendPacket(new SPacketBlockChange(worldIn, pos.down()));
                    }

                } else if (stack != null) {
                    // Stopping the placement of a door or double plant causes artifacts (ghosts) on the top-side of the block. We need to remove it
                    if (stack.getItem() instanceof ItemDoor || (stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock()
                            .equals(Blocks.DOUBLE_PLANT))) {
                        ((EntityPlayerMP) player).connection.sendPacket(new SPacketBlockChange(worldIn, pos.up(2)));
                    }
                }

                return EnumActionResult.FAIL;
            }
            // Forge Start
            PlayerInteractEvent.RightClickBlock forgeEvent = ForgeHooks
                    .onRightClickBlock(player, hand, stack, pos, facing,
                            ForgeHooks.rayTraceEyeHitVec(this.thisPlayerMP, this.getBlockReachDistance() + 1));
            if (forgeEvent.isCanceled()) {
                return EnumActionResult.PASS;
            }

            net.minecraft.item.Item item = stack == null ? null : stack.getItem();
            EnumActionResult ret = item == null
                    ? EnumActionResult.PASS
                    : item.onItemUseFirst(stack, player, worldIn, pos, facing, hitX, hitY, hitZ, hand);
            if (ret != EnumActionResult.PASS) {
                return ret;
            }

            boolean bypass = true;
            final ItemStack[] itemStacks = {player.getHeldItemMainhand(), player.getHeldItemOffhand()};
            for (ItemStack s : itemStacks) {
                bypass = bypass && (s == null || s.getItem().doesSneakBypassUse(s, worldIn, pos, player));
            }

            EnumActionResult result = EnumActionResult.FAIL; // Sponge - Forge deems the default to be PASS, but Sponge is deeming it to be FAIL

            // if (!player.isSneaking() || player.getHeldItemMainhand() == null && player.getHeldItemOffhand() == null) { // Forge - Adds bypass event checks
            if (!player.isSneaking() || bypass || forgeEvent.getUseBlock() == Event.Result.ALLOW) {
                // Sponge start - check event useBlockResult, and revert the client if it's FALSE.
                // Also, store the result instead of returning immediately
                if (event.getUseBlockResult() != Tristate.FALSE) {
                    IBlockState iblockstate = worldIn.getBlockState(pos);
                    result = iblockstate.getBlock().onBlockActivated(worldIn, pos, iblockstate, player, hand, stack, facing, hitX, hitY, hitZ)
                            ? EnumActionResult.SUCCESS
                            : EnumActionResult.FAIL;
                } else {
                    this.thisPlayerMP.connection.sendPacket(new SPacketBlockChange(this.theWorld, pos));
                    result = TristateUtil.toActionResult(event.getUseItemResult());
                }
            }
            // Sponge end


            // Sponge start - store result instead of returning
            if (stack == null) {
                // return EnumActionResult.PASS; // Sponge
                result = EnumActionResult.PASS;
            } else if (player.getCooldownTracker().hasCooldown(stack.getItem())) {
                // return EnumActionResult.PASS; // Sponge
                result = EnumActionResult.PASS;
            } else if (stack.getItem() instanceof ItemBlock && ((ItemBlock) stack.getItem()).getBlock() instanceof BlockCommandBlock && !player.canCommandSenderUseCommand(2, "")) {
                // return EnumActionResult.FAIL; // Sponge
                result = EnumActionResult.FAIL;
                // Sponge start - nest isCreative check instead of calling the method twice.
                // } else if (this.isCreative()) {
            } else {
                // Run if useItemResult is true, or if useItemResult is undefined and the block interaction failed
                if (stack != null
                        && (event.getUseItemResult() == Tristate.TRUE
                                    || (event.getUseItemResult() == Tristate.UNDEFINED && result == EnumActionResult.FAIL)
                )
                        && (result != EnumActionResult.SUCCESS && forgeEvent.getUseItem() != Event.Result.DENY
                                    || result == EnumActionResult.SUCCESS && forgeEvent.getUseItem() == Event.Result.ALLOW
                )) {
                    int meta = stack.getMetadata(); // meta == j
                    int size = stack.stackSize; // size == i
                    // EnumActionResult enumactionresult = stack.onItemUse(player, worldIn, pos, hand, facing, hitX, hitY, hitZ); // Sponge
                    result = stack.onItemUse(player, worldIn, pos, hand, facing, hitX, hitY, hitZ);
                    if (this.isCreative()) {
                        stack.setItemDamage(meta);
                        stack.stackSize = size;
                        // return enumactionresult; // Sponge
                    }
                }
            }


            // if cancelled, force client itemstack update
            if (!ItemStack.areItemStacksEqual(player.getHeldItem(hand), oldStack) || result != EnumActionResult.SUCCESS) {
                // TODO - maybe send just main/off hand?
                player.openContainer.detectAndSendChanges();
                /*((EntityPlayerMP) player).playerNetServerHandler.sendPacket(new SPacketSetSlot(player.openContainer.windowId, player.openContainer.getSlotFromInventory(player.inventory, player.inventory.currentItem),
                        player.inventory.getCurrentItem());*/
            }

            return result;
        }
    }
}
