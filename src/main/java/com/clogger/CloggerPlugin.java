package com.clogger;

import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.ComponentID;
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

	private final Map<Integer, Integer> sessionClogCache = new HashMap<>();
	private final Map<Skill, Integer> lastXpMap = new HashMap<>();
	private final Map<Skill, Integer> lastLevelMap = new HashMap<>();
	private final Map<String, Integer> realIdMap = new HashMap<>();
	private final Set<Integer> collectionLogAllowList = new HashSet<>();

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
	private int lastLootTick = -1;
	private String lastLootSignature = "";

	private boolean isLogOpen = false;
	private final Map<Integer, LogItem> sessionData = new LinkedHashMap<>();

	private static class StatEntry {
		int level; long xp; String type;
		public StatEntry(int level, long xp, String type) { this.level=level; this.xp=xp; this.type=type; }
	}

	private static class Payload {
		String username; long accountHash; String source;
		List<LogItem> items; Map<String, StatEntry> stats;
		public Payload(String u, long a, String s, List<LogItem> i, Map<String, StatEntry> st) {
			this.username=u; this.accountHash=a; this.source=s; this.items=i; this.stats=st;
		}
	}

	// Initializes the plugin and builds the allowed collection log item list
	@Override
	protected void startUp() throws Exception {
		clientThread.invokeLater(this::buildAllowList);
	}

	// Shuts down the plugin, flushes any pending stats, and clears local caches
	@Override
	protected void shutDown() throws Exception {
		if (baselineCaptured) checkAndSendStats("shutdown");
		sessionData.clear();
		sessionClogCache.clear();
		collectionLogAllowList.clear();
		realIdMap.clear();
		lastXpMap.clear();
		lastLevelMap.clear();
	}

	// Traverses the game client's internal structures to map valid collection log item IDs
	private void buildAllowList() {
		collectionLogAllowList.clear();
		realIdMap.clear();

		EnumComposition topLevelEnum = client.getEnum(2102);
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
					collectionLogAllowList.add(realId);
					String name = itemManager.getItemComposition(realId).getName();
					realIdMap.put(name, realId);
				}
			}
		}
	}

	// Monitors game state changes to capture initial stats upon login or push stats upon logout
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

	// Captures the local player's current username, account hash, and skill levels/experience
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

	// Checks if the player's experience has changed since the last capture, and pushes an update if so
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
			uploadStats(source);
			if (client.getGameState() == GameState.LOGGED_IN) {
				captureCurrentStats();
			}
		}
	}

	// Compiles the player's current total level and skill experience into a payload map for upload
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

	// Encapsulates collection log items and skill stats into a JSON payload and transmits it asynchronously
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
			@Override public void onFailure(Call call, IOException e) {}
			@Override public void onResponse(Call call, Response response) { response.close(); }
		});
	}

	// Helper method to upload drop data when stat updates are not required
	private void uploadData(String source, List<LogItem> items) {
		uploadData(source, items, null);
	}

	// Intercepts loot drops from NPCs and forwards them for processing
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event) {
		handleLoot(event.getNpc().getName(), "npc_loot", event.getItems());
	}

	// Intercepts loot drops from containers or external events and forwards them for processing
	@Subscribe
	public void onLootReceived(LootReceived event) {
		handleLoot(event.getName(), "container_loot", event.getItems());
	}

	// Filters incoming loot against the allow-list, caches it, and stages the valid items for HTTP transmission
	private void handleLoot(String sourceName, String type, Collection<ItemStack> items) {
		List<LogItem> dropsToSend = new ArrayList<>();
		long now = System.currentTimeMillis();

		for (ItemStack item : items) {
			if (collectionLogAllowList.contains(item.getId())) {
				String itemName = itemManager.getItemComposition(item.getId()).getName();
				updateDedup(item.getId());
				sessionClogCache.put(item.getId(), sessionClogCache.getOrDefault(item.getId(), 0) + item.getQuantity());

				dropsToSend.add(LogItem.builder().itemId(item.getId()).quantity(item.getQuantity())
						.name(itemName).source(type).timestamp(now).build());
			}
		}
		uploadData(type, dropsToSend);
	}

	// Listens for in-game chat messages and processes them on the client thread to detect log completions
	@Subscribe
	public void onChatMessage(ChatMessage event) {
		clientThread.invokeLater(() -> processChatMessage(event));
	}

	// Evaluates a chat message against regex patterns to identify standard or clan-broadcasted collection log unlocks
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

	// Resolves a plaintext item name from chat into a valid game ID and queues it for upload if it isn't a duplicate
	private void processChatUnlock(String rawName) {
		String cleanName = Text.removeTags(rawName);
		int itemId = findIdByName(cleanName);
		if (itemId == -1 || isDuplicate(itemId)) return;
		updateDedup(itemId);
		sessionClogCache.put(itemId, sessionClogCache.getOrDefault(itemId, 0) + 1);

		List<LogItem> dropsToSend = Collections.singletonList(LogItem.builder()
				.itemId(itemId).quantity(1).name(cleanName).source("chat_unlock")
				.timestamp(System.currentTimeMillis()).build());
		uploadData("chat_unlock", dropsToSend);
	}

	// Verifies if a specific item ID was already processed within the last 3000 milliseconds to prevent duplicate uploads
	private boolean isDuplicate(int itemId) {
		long now = System.currentTimeMillis();
		return (itemId == lastUnlockId && (now - lastUnlockTime < 3000));
	}

	// Updates the local deduplication cache with the most recently unlocked item ID and current timestamp
	private void updateDedup(int itemId) {
		lastUnlockId = itemId;
		lastUnlockTime = System.currentTimeMillis();
	}

	// Executes a fuzzy search using the RuneLite ItemManager to map a plaintext item name to its actual game ID
	private int findIdByName(String name) {
		List<ItemPrice> results = itemManager.search(name);
		for (ItemPrice item : results) {
			if (item.getName().equalsIgnoreCase(name) && collectionLogAllowList.contains(item.getId())) {
				return item.getId();
			}
		}
		for (ItemPrice item : results) {
			if (collectionLogAllowList.contains(item.getId())) {
				return item.getId();
			}
		}
		return -1;
	}

	// Executes interval-based heartbeat checks and captures baseline stats after the login grace period
	@Subscribe
	public void onGameTick(GameTick event) {
		if (loginTick != -1 && !baselineCaptured) {
			if (client.getTickCount() - loginTick > LOGIN_GRACE_TICKS) {
				captureCurrentStats();
				loginTick = -1;
			}
		}

		heartbeatTicks++;
		if (heartbeatTicks >= HEARTBEAT_INTERVAL) {
			checkAndSendStats("heartbeat");
			heartbeatTicks = 0;
		}
	}

	// Sets isLogOpen to true when the collection log widget is loaded
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.COLLECTION_LOG) {
			isLogOpen = true;
			sessionData.clear();
		}
	}

	// Sets isLogOpen to false when the collection log widget is closed
	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.COLLECTION_LOG) {
			isLogOpen = false;
			finishSession();
		}
	}

	// Safely scrapes data from the interface after the script finishes executing
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST) {
			clientThread.invokeLater(this::scrapeCurrentPage);
		}
	}

	//Gather collection log info from the current page we have open
	private void scrapeCurrentPage() {
		long now = System.currentTimeMillis();

		Widget w = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
		if (w == null) {
			return;
		}

		Widget[] children = w.getDynamicChildren();
		if (children == null) {
			return;
		}

		int itemsFound = 0;

		for (Widget child : children) {
			int displayId = child.getItemId();
			if (displayId != -1 && displayId != 6512) {
				if (child.getOpacity() == 0 && !child.isHidden()) {
					int currentQty = child.getItemQuantity();
					if (currentQty <= 0) currentQty = 1;

					String name = itemManager.getItemComposition(displayId).getName();
					itemsFound++;

					Integer realId;
					if (collectionLogAllowList.contains(displayId)) {
						realId = displayId;
					} else {
						realId = realIdMap.get(name);
					}

					if (realId != null) {
						int cachedQty = sessionClogCache.getOrDefault(realId, -1);
						if (currentQty != cachedQty) {
							sessionClogCache.put(realId, currentQty);

							boolean alreadyQueued = sessionData.containsKey(realId) && sessionData.get(realId).getQuantity() == currentQty;

							if (!alreadyQueued) {
								sessionData.put(realId, LogItem.builder()
										.itemId(realId)
										.quantity(currentQty)
										.name(name)
										.source("active_session")
										.timestamp(now).build());
							}
						}
					}
				}
			}
		}
	}

	// Allows developers to trigger a simulated drop for local testing and debugging
	@Subscribe
	public void onCommandExecuted(CommandExecuted command) {
		if (command.getCommand().equalsIgnoreCase("testdrop")) {
			Collection<ItemStack> fakeLoot = new ArrayList<>();
			fakeLoot.add(new ItemStack(4151, 1, null));
			handleLoot("Fake Boss", "npc_loot", fakeLoot);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "New item added to your collection log: Abyssal whip", null);
		}
	}

	// Bundles any queued collection log entries from the active session and uploads them to the API
	private void finishSession() {
		if (sessionData.isEmpty()) {
			return;
		}
		uploadData("active_session", new ArrayList<>(sessionData.values()));
		sessionData.clear();
	}

	// Injects and exposes the specific configuration bindings for the Clogger plugin
	@Provides
	CloggerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(CloggerConfig.class);
	}
}