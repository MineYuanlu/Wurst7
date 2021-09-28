/*
 * Copyright (c) 2014-2021 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.IsPlayerInWaterListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"FlyHack", "fly hack", "flying"})
public final class FlightHack extends Hack
	implements UpdateListener, IsPlayerInWaterListener
{
	public final SliderSetting speed =
		new SliderSetting("Speed", 1, 0.05, 5, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting antikick =
		new CheckboxSetting("Anti kick", "Makes you fall a little bit every second.", false);
	private final SliderSetting antikickintv =
		new SliderSetting("Anti kick interval", 30, 5, 100, 1.0, ValueDisplay.INTEGER);

	public FlightHack()
	{
		super("Flight");
		setCategory(Category.MOVEMENT);
		addSetting(speed);
		addSetting(antikick);
		addSetting(antikickintv);
	}
	
	@Override
	public void onEnable()
	{
		WURST.getHax().creativeFlightHack.setEnabled(false);
		WURST.getHax().jetpackHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(IsPlayerInWaterListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(IsPlayerInWaterListener.class, this);
	}
	
	private enum ssdvAction {
		UP,
		DOWN,
		NULL;
	}
	private int ssdvcounter = 0;
	private ssdvAction shouldSetDownwardsVelocity()
	{
		int ssdvinterval = antikickintv.getValueI();
		if (!antikick.isChecked()) return ssdvAction.NULL;
		if (ssdvcounter >= ssdvinterval) {
			ssdvcounter = 0;
		}
		else {
			ssdvcounter++;
		}
		return ssdvcounter == 0 ? ssdvAction.DOWN : (ssdvcounter == ssdvinterval ? ssdvAction.UP : ssdvAction.NULL);
	}

	@Override
	public void onUpdate()
	{
		ClientPlayerEntity player = MC.player;
		
		player.getAbilities().flying = false;
		player.airStrafingSpeed = speed.getValueF();
		
		player.setVelocity(0, 0, 0);
		Vec3d velcity = player.getVelocity();
		ssdvAction ssdv = shouldSetDownwardsVelocity();
		if (ssdv.equals(ssdvAction.DOWN)) {
			player.setVelocity(velcity.subtract(0, speed.getValue(), 0));
		}
		else if (ssdv.equals(ssdvAction.UP)) {
			player.setVelocity(velcity.add(0, speed.getValue(), 0));
		}
		else {
			if(MC.options.keyJump.isPressed())
				player.setVelocity(velcity.add(0, speed.getValue(), 0));
			
			if(MC.options.keySneak.isPressed())
				player.setVelocity(velcity.subtract(0, speed.getValue(), 0));
		}
	}
	
	@Override
	public void onIsPlayerInWater(IsPlayerInWaterEvent event)
	{
		event.setInWater(false);
	}
}
