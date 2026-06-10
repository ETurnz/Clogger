package com.clogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("clogger")
public interface CloggerConfig extends Config
{
	@ConfigItem(
			keyName = "hideDrops",
			name = "Hide My Drops from Homepage",
			description = "Hide your drops from the recent drops feed on the clogger.ca homepage"
	)
	default boolean hideDrops()
	{
		return false;
	}
}
