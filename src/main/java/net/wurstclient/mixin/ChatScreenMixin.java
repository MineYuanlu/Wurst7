/*
 * Copyright (c) 2014-2022 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ChatPreviewer;
import net.minecraft.text.Text;
import net.wurstclient.WurstClient;

@Mixin(ChatScreen.class)
public class ChatScreenMixin extends Screen
{
	@Shadow
	protected TextFieldWidget chatField;
	
	@Shadow
	private ChatPreviewer chatPreviewer;
	
	private ChatScreenMixin(WurstClient wurst, Text text_1)
	{
		super(text_1);
	}
	
	@Inject(at = {@At("TAIL")}, method = {"init()V"})
	protected void onInit(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().infiniChatHack.isEnabled())
			chatField.setMaxLength(Integer.MAX_VALUE);
	}
	
	@Inject(at = @At("HEAD"),
		method = "updatePreviewer(Ljava/lang/String;)V",
		cancellable = true)
	private void onUpdatePreviewer(String chatText, CallbackInfo ci)
	{
		String normalized = normalize(chatText);
		
		if(normalized.startsWith(".say "))
		{
			// send preview, but only for the part after ".say "
			chatPreviewer.tryRequest(normalized.substring(5));
			ci.cancel();
			
		}else if(normalized.startsWith("."))
		{
			// disable & delete preview
			chatPreviewer.disablePreview();
			ci.cancel();
		}
	}
	
	@Shadow
	public String normalize(String chatText)
	{
		return null;
	}
}
