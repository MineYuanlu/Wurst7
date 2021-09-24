package net.wurstclient.commands;

import net.wurstclient.command.CmdError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

public final class BaritoneCmd extends Command
{
	private boolean enabled = false;
    private boolean running = false;
    private boolean paused = false;
    private long pausereq = 0l;
	public int sleeptime = 1500;
	public BaritoneCmd()
	{
		super("ba", "Pause baritone on autoeat and killaura events.",
			".ba <sleeptime in ms>", "Turn off: .ba");
	}
	
	@Override
	public void call(String[] args) throws CmdError
	{

        if(enabled)
        {        
            disable();
        }
		else
		{
			enable(args);
		}
	}
	
	private void enable(String[] args) throws CmdError
	{
        if (args.length > 1) {
            throw new CmdError(".ba <sleeptime in ms>");
        }
        if (args.length > 0) {
            try {
                this.sleeptime = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                throw new CmdError("not an Integer");
            }
            this.sleeptime = this.sleeptime < 1000 ? 1000 : this.sleeptime;
        }
        if (running) {
            throw new CmdError(".baritone is still turning off.");
        }
        enabled = true;
        paused = false;
        pausereq = 0l;
        new Thread(() -> runLoop(), "baritone cmd runloop").start();
        ChatUtils.message(String.format(".baritone is turned on with sleeptime=%d", this.sleeptime));
	}
	
	private void disable()
	{
		enabled = false;
        ChatUtils.message(".baritone is turning off");
	}
	
    private void runLoop()
    {
        running = true;
        long now;
        while (enabled) {
            now = System.currentTimeMillis();
            if (this.paused && this.pausereq != 0l) {
                if (now - this.pausereq > this.sleeptime) {
                    this.pausereq = 0l;
                    this.doResume();
                }
            }
            try
            {
                Thread.sleep(1000);
            }
            catch(InterruptedException e)
            {
                break;
            }
        }
        running = false;
    }
	
    public void Pause() {
        if (enabled) {
            this.doPause();
            this.pausereq = System.currentTimeMillis();
        }
    }

	private void doPause()
	{
        if (!this.paused) {
    		MC.player.sendChatMessage("#pause");
            this.paused = true;
        }
	}
	private void doResume()
	{
        if (this.paused) {
    		MC.player.sendChatMessage("#resume");
            this.paused = false;
        }
    }
}
