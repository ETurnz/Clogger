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

	private boolean isLogOpen = false;
	private final List<LogItem> sessionData = new ArrayList<>();

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

	@Override
	protected void startUp() throws Exception {
		clientThread.invokeLater(this::buildAllowList);
	}

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
			uploadStats(source);
			if (client.getGameState() == GameState.LOGGED_IN) {
				captureCurrentStats();
			}
		}
	}

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

	private void uploadData(String source, List<LogItem> items) {
		uploadData(source, items, null);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event) {
		handleLoot(event.getNpc().getName(), "npc_loot", event.getItems());
	}

	@Subscribe
	public void onLootReceived(LootReceived event) {
		handleLoot(event.getName(), "container_loot", event.getItems());
	}

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

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		clientThread.invokeLater(() -> processChatMessage(event));
	}

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

	private boolean isDuplicate(int itemId) {
		long now = System.currentTimeMillis();
		return (itemId == lastUnlockId && (now - lastUnlockTime < 3000));
	}

	private void updateDedup(int itemId) {
		lastUnlockId = itemId;
		lastUnlockTime = System.currentTimeMillis();
	}

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

		Widget collectionLog = client.getWidget(InterfaceID.COLLECTION_LOG, 0);
		boolean currentlyOpen = collectionLog != null && !collectionLog.isHidden();

		if (!isLogOpen && currentlyOpen) {
			isLogOpen = true;
			sessionData.clear();
		} else if (isLogOpen && !currentlyOpen) {
			isLogOpen = false;
			finishSession();
		}

		if (isLogOpen) {
			scrapeCurrentPage();
		}
	}

	//Gather collection log info from the current page we have open
	private void scrapeCurrentPage() {
		long now = System.currentTimeMillis();

		for (int i = 0; i < 100; i++) {
			Widget w = client.getWidget(InterfaceID.COLLECTION_LOG, i);
			if (w != null && w.getDynamicChildren() != null) {
				for (Widget child : w.getDynamicChildren()) {
					int displayId = child.getItemId();
					if (displayId != -1 && displayId != 6512) {
						if (child.getOpacity() == 0 && !child.isHidden()) {
							int currentQty = child.getItemQuantity();
							if (currentQty <= 0) currentQty = 1;

							String name = itemManager.getItemComposition(displayId).getName();

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

									boolean alreadyQueued = false;
									for (LogItem li : sessionData) {
										if (li.getItemId() == realId && li.getQuantity() == currentQty) {
											alreadyQueued = true;
											break;
										}
									}

									if (!alreadyQueued) {
										sessionData.add(LogItem.builder()
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
		}
	}

	private void finishSession() {
		if (sessionData.isEmpty()) {
			return;
		}
		uploadData("active_session", new ArrayList<>(sessionData));
		sessionData.clear();
	}

	@Provides
	CloggerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(CloggerConfig.class);
	}
}