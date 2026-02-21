package com.clogger;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogItem
{
    private final int itemId;
    private final int quantity;
    private final String name;
    private final String source; // "active_session" (Total) or "npc_loot" (Increment)
    private final long timestamp;
}