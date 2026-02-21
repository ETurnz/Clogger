package com.clogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("clogger")
public interface CloggerConfig extends Config
{
	@ConfigItem(
			keyName = "apiKey",
			name = "API Key",
			description = "The API Key for clogger.ca",
			secret = true
	)
	default String apiKey()
	{
		return "";
	}
}