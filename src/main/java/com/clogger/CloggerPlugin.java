package com.clogger;

import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "Clogger",
		description = "Syncs collection log data to clogger.ca",
		tags = {"collection", "log", "sync", "clogger"}
)
public class CloggerPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ItemManager itemManager;
	@Inject private CloggerConfig config;
	@Inject private ClientThread clientThread;
	@Inject private OkHttpClient okHttpClient;
	@Inject private Gson gson;

	//Cache
	private final Map<Integer, Integer> sessionClogCache = new HashMap<>();
	private final Map<Skill, Integer> lastXpMap = new HashMap<>();
	private final Map<Skill, Integer> lastLevelMap = new HashMap<>();

	// Stores item name and real ID
	private final Map<String, Integer> realIdMap = new HashMap<>();

	// Item allow list
	private final Set<Integer> collectionLogAllowList = new HashSet<>();

	// Cache and State Flags
	private String cachedUsername = null;
	private long cachedAccountHash = -1;
	private boolean baselineCaptured = false;
	private long loginTick = -1;
	private static final int LOGIN_GRACE_TICKS = 10;

	private int heartbeatTicks = 0;
	private static final int HEARTBEAT_INTERVAL = 500;

	private static final Pattern GAME_CLOG_REGEX = Pattern.compile("New item added to your collection log: (.*)");
	private static final Pattern CLAN_CLOG_REGEX = Pattern.compile("received a new collection log item: (.*) \\(\\d+/\\d+\\)");
	private int lastUnlockId = -1;
	private long lastUnlockTime = 0;

	// Collection Log Auto-Sync State
	private boolean isLogOpen = false;
	private final List<LogItem> sessionData = new ArrayList<>();
	private boolean triggerSyncAllowed = false;
	private Integer gameTickToSync = null;
	private static final int SYNC_DELAY_TICKS = 2; // ~1.2 seconds for batching

	// Data Structures
	private static class StatEntry {
		int level; long xp; String type;
		public StatEntry(int level, long xp, String type) { this.level=level; this.xp=xp; this.type=type; }
	}

	// Data to send to clogger server
	private static class Payload {
		String username; long accountHash; String source;
		List<LogItem> items; Map<String, StatEntry> stats;
		public Payload(String u, long a, String s, List<LogItem> i, Map<String, StatEntry> st) {
			this.username=u; this.accountHash=a; this.source=s; this.items=i; this.stats=st;
		}
	}

	@Override
	protected void startUp() throws Exception {
		//log.info("Clogger started!");
		clientThread.invokeLater(this::buildAllowList);
	}

	@Override
	protected void shutDown() throws Exception {
		//log.info("Clogger stopped!");
		if (baselineCaptured) checkAndSendStats("shutdown");
		sessionData.clear();
		sessionClogCache.clear();
		collectionLogAllowList.clear();
		lastXpMap.clear();
		lastLevelMap.clear();
	}

	// Builds the 'allow list' of items in the collection log
	private void buildAllowList() {
		collectionLogAllowList.clear();
		realIdMap.clear();

		EnumComposition topLevelEnum = client.getEnum(2102); // The Master Collection Log List
		if (topLevelEnum == null) return;

		for (int structId : topLevelEnum.getIntVals()) {
			StructComposition topStruct = client.getStructComposition(structId);
			if (topStruct == null) continue;

			EnumComposition subEnum = client.getEnum(topStruct.getIntValue(683));
			if (subEnum == null) continue;

			for (int subStructId : subEnum.getIntVals()) {
				StructComposition subStruct = client.getStructComposition(subStructId);
				if (subStruct == null) continue;

				EnumComposition itemListEnum = client.getEnum(subStruct.getIntValue(690));
				if (itemListEnum == null) continue;

				for (int realId : itemListEnum.getIntVals()) {
					// 1. Add to Allow List (for validation)
					collectionLogAllowList.add(realId);

					// 2. Map Name to Real ID. This creates the bridge - Sync Script finds name "Unsired", this map gives ID 13273
					String name = itemManager.getItemComposition(realId).getName();
					realIdMap.put(name, realId);
				}
			}
		}
		//log.info("Clogger: Indexed {} items from cache.", realIdMap.size());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			loginTick = client.getTickCount();
			baselineCaptured = false;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN) {
			if (baselineCaptured) checkAndSendStats("logout_stats");
		}
	}

	// Stat tracking
	private void captureCurrentStats() {
		if (client.getLocalPlayer() == null) return;
		cachedUsername = client.getLocalPlayer().getName();
		cachedAccountHash = client.getAccountHash();

		lastXpMap.clear();
		lastLevelMap.clear();
		for (Skill s : Skill.values()) {
			lastXpMap.put(s, client.getSkillExperience(s));
			lastLevelMap.put(s, client.getRealSkillLevel(s));
		}
		baselineCaptured = true;
	}

	private void checkAndSendStats(String source) {
		if (!baselineCaptured) return;
		boolean changed = false;

		for (Skill s : Skill.values()) {
			int current = client.getSkillExperience(s);
			int cached = lastXpMap.getOrDefault(s, -1);
			if (current != cached) { changed = true; break; }
		}

		boolean forceSend = source.equals("logout_stats") || source.equals("shutdown");

		if (changed || forceSend) {
			// log.info("Clogger: Uploading Stats (Source: {} | Force: {})", source, forceSend);
			uploadStats(source);
			if (client.getGameState() == GameState.LOGGED_IN) {
				captureCurrentStats();
			}
		}
	}

	//Gather stats and prepare to upload
	private void uploadStats(String source) {
        int checkTotal = 0;

		for (Skill s : Skill.values()) {
             if (s != Skill.OVERALL) checkTotal += client.getRealSkillLevel(s);
        }

        if (checkTotal < 10) return;

        Map<String, StatEntry> statsObj = new HashMap<>();
        long totalXp = 0;
        int totalLevel = 0;

        for (Skill s : Skill.values()) {
            if (s == Skill.OVERALL) continue;
            int xp = client.getSkillExperience(s);
            int lvl = client.getRealSkillLevel(s);
            String name = Text.titleCase(s);

            statsObj.put(name, new StatEntry(lvl, xp, "skill"));
            totalXp += xp;
            totalLevel += lvl;
        }
        statsObj.put("Overall", new StatEntry(totalLevel, totalXp, "skill"));

        uploadData(source, new ArrayList<>(), statsObj);
	}

	// Networking
	private void uploadData(String source, List<LogItem> items, Map<String, StatEntry> stats) {
		if (items.isEmpty() && stats == null) return;

		String username;
		long accountHash;

		if (client.getLocalPlayer() != null) {
			username = client.getLocalPlayer().getName();
			accountHash = client.getAccountHash();
			cachedUsername = username;
			cachedAccountHash = accountHash;
		} else {
			if (cachedUsername == null || cachedAccountHash == -1) return;
			username = cachedUsername;
			accountHash = cachedAccountHash;
		}

		Payload payload = new Payload(username, accountHash, source, items, stats);
		String json = gson.toJson(payload);

		Request request = new Request.Builder()
				.url("https://clogger.ca/api/sync")
				.post(RequestBody.create(MediaType.parse("application/json"), json))
				.build();

		okHttpClient.newCall(request).enqueue(new Callback() {
			@Override public void onFailure(Call call, IOException e) { log.debug("Upload failed"); }
			@Override public void onResponse(Call call, Response response) { response.close(); }
		});
	}

	private void uploadData(String source, List<LogItem> items) {
		uploadData(source, items, null);
	}

	// =========================================================================
	// Collection log logic (Loot & Chat)
	// =========================================================================

	//Subscribe to npc drops
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event) {
		handleLoot(event.getNpc().getName(), "npc_loot", event.getItems());
	}

	//Subscribe to other methods of receiving drops
	@Subscribe
	public void onLootReceived(LootReceived event) {
		handleLoot(event.getName(), "container_loot", event.getItems());
	}

	//Process loot
	private void handleLoot(String sourceName, String type, Collection<ItemStack> items) {
		List<LogItem> dropsToSend = new ArrayList<>();
		long now = System.currentTimeMillis();

		for (ItemStack item : items) {
			if (collectionLogAllowList.contains(item.getId())) {
				String itemName = itemManager.getItemComposition(item.getId()).getName();
				updateDedup(item.getId());
				sessionClogCache.put(item.getId(), sessionClogCache.getOrDefault(item.getId(), 0) + item.getQuantity());

				//log.info("--- DROP: {} x{} ({}) ---", itemName, item.getQuantity(), sourceName);
				dropsToSend.add(LogItem.builder().itemId(item.getId()).quantity(item.getQuantity())
						.name(itemName).source(type).timestamp(now).build());
			}
		}
		uploadData(type, dropsToSend);
	}

	//Subscribe to chat messages and send them to be processed for drops
	@Subscribe
	public void onChatMessage(ChatMessage event) {
		clientThread.invokeLater(() -> processChatMessage(event));
	}

	//Parse chat message for drops on the collection log
	private void processChatMessage(ChatMessage event) {
		ChatMessageType type = event.getType();
		String message = event.getMessage();
		String itemName = null;
		if (type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM) {
			Matcher matcher = GAME_CLOG_REGEX.matcher(message);
			if (matcher.matches()) itemName = matcher.group(1);
		} else if (type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.FRIENDSCHAT) {
			String sender = Text.removeTags(event.getName());
			if (client.getLocalPlayer() != null && sender.equals(client.getLocalPlayer().getName())) {
				Matcher matcher = CLAN_CLOG_REGEX.matcher(message);
				if (matcher.find()) itemName = matcher.group(1);
			}
		}
		if (itemName != null) processChatUnlock(itemName);
	}

	//Process chat unlocks
	private void processChatUnlock(String rawName) {
		String cleanName = Text.removeTags(rawName);
		int itemId = findIdByName(cleanName);
		if (itemId == -1 || isDuplicate(itemId)) return;
		updateDedup(itemId);
		sessionClogCache.put(itemId, sessionClogCache.getOrDefault(itemId, 0) + 1);

		//log.info("--- CHAT UNLOCK: {} (ID: {}) ---", cleanName, itemId);
		List<LogItem> dropsToSend = Collections.singletonList(LogItem.builder()
				.itemId(itemId).quantity(1).name(cleanName).source("chat_unlock")
				.timestamp(System.currentTimeMillis()).build());
		uploadData("chat_unlock", dropsToSend);
	}

	private boolean isDuplicate(int itemId) {
		long now = System.currentTimeMillis();
		return (itemId == lastUnlockId && (now - lastUnlockTime < 3000));
	}
	private void updateDedup(int itemId) {
		lastUnlockId = itemId;
		lastUnlockTime = System.currentTimeMillis();
	}
	private int findIdByName(String name) {
		// Search for all items with this name
		List<ItemPrice> results = itemManager.search(name);

		// We look for an item that matches the name exactly AND is in our allow list
		for (ItemPrice item : results) {
			if (item.getName().equalsIgnoreCase(name) && collectionLogAllowList.contains(item.getId())) {
				return item.getId();
			}
		}

		// If exact match fails, check if ANY result is in the allow list
		for (ItemPrice item : results) {
			if (collectionLogAllowList.contains(item.getId())) {
				return item.getId();
			}
		}

		return -1;
	}

	// =========================================================================
	// Auto-sync Engine
	// =========================================================================

	@Subscribe
	public void onGameTick(GameTick event) {
		if (loginTick != -1 && !baselineCaptured) {
			if (client.getTickCount() - loginTick > LOGIN_GRACE_TICKS) {
				captureCurrentStats();
				//log.info("Clogger: Stats settled. Baseline captured.");
				loginTick = -1;
			}
		}

		heartbeatTicks++;
		if (heartbeatTicks >= HEARTBEAT_INTERVAL) {
			checkAndSendStats("heartbeat");
			heartbeatTicks = 0;
		}

		Widget collectionLog = client.getWidget(InterfaceID.COLLECTION_LOG, 0);
		boolean currentlyOpen = collectionLog != null && !collectionLog.isHidden();

		if (!isLogOpen && currentlyOpen) {
			isLogOpen = true;
			sessionData.clear();
		} else if (isLogOpen && !currentlyOpen) {
			isLogOpen = false;
			finishBatchedSession();
		}

		// Handle batched session finish
		if (gameTickToSync != null && client.getTickCount() >= gameTickToSync) {
			//log.info("Batch delay complete. Uploading {} items.", sessionData.size());
			uploadData("active_session", new ArrayList<>(sessionData));
			sessionData.clear();
			gameTickToSync = null;
		}
	}

	private void finishBatchedSession() {
		if (sessionData.isEmpty()) {
			log.debug("Session closed, but no new data to send.");
			return;
		}
		// Start batching countdown when log closes
		//log.info("Session Complete. Starting batch delay for {} items.", sessionData.size());
		gameTickToSync = client.getTickCount() + SYNC_DELAY_TICKS;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		// Collection Log widget loaded - enable auto-search trigger
		if (event.getGroupId() == InterfaceID.COLLECTION_LOG) {
			triggerSyncAllowed = true;
			log.debug("Collection Log opened. Auto-search enabled.");
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if (event.getScriptId() == 4100) {
			Object[] args = event.getScriptEvent().getArguments();

			int displayId = (int) args[1]; // The "Fake" ID from the screen
			int quantity = (int) args[2];

			// 1. Get the name of the item on screen
			String name = itemManager.getItemComposition(displayId).getName();

			// 2. Determine "Real" ID: If the displayId is explicitly in our allow list, trust it.
			Integer realId;
			if (collectionLogAllowList.contains(displayId)) {
				realId = displayId;
			} else {
				// Fallback, look up by name (collapses variants, but handles weird display IDs)
				realId = realIdMap.get(name);
			}

			if (realId != null) {
				int cachedQty = sessionClogCache.getOrDefault(realId, -1);

				if (quantity != cachedQty) {
					sessionClogCache.put(realId, quantity);

					// Check if we already queued this exact update
					boolean alreadyQueued = false;
					for (LogItem li : sessionData) {
						if (li.getItemId() == realId && li.getQuantity() == quantity) {
							alreadyQueued = true;
							break;
						}
					}

					if (!alreadyQueued) {
						long now = System.currentTimeMillis();
						sessionData.add(LogItem.builder()
								.itemId(realId) // Sends 13273
								.quantity(quantity)
								.name(name)
								.source("active_session")
								.timestamp(now).build());
						// log.debug("Captured: {} (Display: {} -> Real: {})", name, displayId, realId);
					}
				}
			}
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		// Script 7797 is the collection log UI script
		// When it fires and auto-search is allowed, trigger search to populate all items
		if (event.getScriptId() == 7797 && triggerSyncAllowed) {
			clientThread.invokeLater(() -> {
				//log.info("Auto-triggering collection log search to populate items...");
				// Simulate clicking "Search" then "Back" to force population of all items
				client.menuAction(-1, 40697932, MenuAction.CC_OP, 1, -1, "Search", null);
				client.menuAction(-1, 40697932, MenuAction.CC_OP, 1, -1, "Back", null);

				triggerSyncAllowed = false;
			});
		}
	}

	/*@Subscribe
	public void onCommandExecuted(CommandExecuted command) {
		if (command.getCommand().equalsIgnoreCase("testdrop")) {
			Collection<ItemStack> fakeLoot = new ArrayList<>();
			fakeLoot.add(new ItemStack(4151, 1, null));
			handleLoot("Fake Boss", "npc_loot", fakeLoot);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "New item added to your collection log: Abyssal whip", null);
		}

		if (command.getCommand().equalsIgnoreCase("exportclog")) {
			clientThread.invokeLater(this::exportFullCollectionLog);
		}
	}*/

	//Exports the full collection log, useful when new items are added
	private void exportFullCollectionLog() {
		// Dump the full structure to JSON
		Map<String, Object> export = new LinkedHashMap<>();
		EnumComposition topLevelEnum = client.getEnum(2102); // COLLECTION_LOG_TABS
		if (topLevelEnum == null) return;

		int[] tabStructIds = topLevelEnum.getIntVals();
		Map<String, Object> tabsMap = new LinkedHashMap<>();

		// Map of known Tab Struct IDs to names if Param lookup fails
		Map<Integer, String> fallbackTabNames = new HashMap<>();
		fallbackTabNames.put(471, "Bosses");
		fallbackTabNames.put(472, "Raids");
		fallbackTabNames.put(473, "Clues");
		fallbackTabNames.put(474, "Minigames");
		fallbackTabNames.put(475, "Other");

		for (int tabStructId : tabStructIds) {
			StructComposition tabStruct = client.getStructComposition(tabStructId);
			if (tabStruct == null) continue;

			// Fallback to strict ID map or generic ID
			String tabName = fallbackTabNames.getOrDefault(tabStructId, "Tab_" + tabStructId);

			EnumComposition catEnum = client.getEnum(tabStruct.getIntValue(683)); // COLLECTION_LOG_TAB_CATEGORIES
			if (catEnum == null) continue;

			Map<String, Object> categoriesMap = new LinkedHashMap<>();
			for (int catStructId : catEnum.getIntVals()) {
				StructComposition catStruct = client.getStructComposition(catStructId);
				if (catStruct == null) continue;

				String catName = "Cat_" + catStructId;

				// Ensure unique keys if names collide (unlikely but safe)
				if (categoriesMap.containsKey(catName)) {
					catName = catName + "_" + catStructId;
				}

				// Items
				EnumComposition itemEnum = client.getEnum(catStruct.getIntValue(690)); // COLLECTION_LOG_CATEGORY_ITEMS
				if (itemEnum == null) continue;

				List<Map<String, Object>> itemsList = new ArrayList<>();
				for (int itemId : itemEnum.getIntVals()) {
					Map<String, Object> itemData = new LinkedHashMap<>();
					itemData.put("id", itemId);
					itemData.put("name", itemManager.getItemComposition(itemId).getName());
					itemsList.add(itemData);
				}

				categoriesMap.put(catName, itemsList);
			}
			tabsMap.put(tabName, categoriesMap);
		}

		export.put("collection_log", tabsMap);

		// Pretty print JSON
		Gson gsonPretty = gson.newBuilder().setPrettyPrinting().create();
		String json = gsonPretty.toJson(export);

		//log.info("Collection Log Exported ({} chars)", json.length());

		// java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(json), null);

		// Also print to console/log with prefix
		//System.out.println("CLOG_EXPORT: " + json);
		//client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Collection log structure exported to log/console!", null);
	}

	@Provides
	CloggerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(CloggerConfig.class);
	}
}