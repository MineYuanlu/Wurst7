/*
 * Copyright (c) 2014-2022 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.commands.WarehouseCmd;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.hacks.WarehouseHack;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin
	implements ClientPlayPacketListener
{

	private WarehouseCmd warehouse =
			WurstClient.INSTANCE.getCmds().warehouseCmd;
	private WarehouseHack warehouseHack =
			WurstClient.INSTANCE.getHax().warehouseHack;
	
	@Inject(at = {@At("HEAD")},
		method = {"sendPacket(Lnet/minecraft/network/Packet;)V"},
		cancellable = true)
	private void onSendPacket(Packet<?> packet, CallbackInfo ci)
	{
		PacketOutputEvent event = new PacketOutputEvent(packet);
		EventManager.fire(event);
		
		if(event.isCancelled())
			ci.cancel();
	}
	
	@Inject(at={@At("RETURN")},
			method = {"onInventory(Lnet/minecraft/network/packet/s2c/play/InventoryS2CPacket;)V"},
			cancellable = true)
	private void onInventory(InventoryS2CPacket packet, CallbackInfo ci) {

		if (warehouse==null)warehouse =
				WurstClient.INSTANCE.getCmds().warehouseCmd;
		if (warehouseHack==null)warehouseHack =
				WurstClient.INSTANCE.getHax().warehouseHack;
		warehouse.callbackInventory(packet.getContents(), packet.getSyncId());
		warehouseHack.callbackInventory(packet.getContents(), packet.getSyncId());
	}
}
