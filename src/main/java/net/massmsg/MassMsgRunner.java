package net.massmsg;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MassMsgRunner {
	private static final int MAX_COMMAND_LENGTH = 256;
	private static final Pattern LEGAL_NAME = Pattern.compile("^[A-Za-z0-9_.*\\-]{1,32}$");
	private static final Random RANDOM = new Random();

	private static Method nameMethod;
	private static Method idMethod;
	private static Field nameField;
	private static Field idField;
	private static boolean resolvedAccessors;

	private final MinecraftClient client = MinecraftClient.getInstance();
	private MassMsgConfig config;

	private boolean running;
	private final List<String> targets = new ArrayList<>();
	private int index;
	private int timer;
	private int delayTicks;
	private String command;
	private String template;
	private int sent;
	private int skipped;

	public MassMsgRunner(MassMsgConfig config) {
		this.config = config;
	}

	public void setConfig(MassMsgConfig config) {
		this.config = config;
	}

	public boolean isRunning() {
		return running;
	}

	public int remaining() {
		if (!running) {
			return 0;
		}
		return Math.max(0, targets.size() - index);
	}

	public int total() {
		return targets.size();
	}

	public String currentCommand() {
		return command == null ? config.command : command;
	}

	public void start() {
		if (running) {
			chat("Already running (" + remaining() + " left).", Formatting.RED);
			return;
		}

		ClientPlayNetworkHandler net = client.getNetworkHandler();
		if (client.player == null || net == null) {
			chat("Join a world or server first.", Formatting.RED);
			return;
		}

		config.normalize();
		if (!MassMsgConfig.ALLOWED_COMMANDS.contains(config.command)) {
			chat("Command is not a private-message command.", Formatting.RED);
			return;
		}

		command = config.command;
		template = config.message;
		delayTicks = config.delayTicks;
		targets.clear();
		index = 0;
		timer = 0;
		sent = 0;
		skipped = 0;

		UUID selfId = client.player.getUuid();
		String self = scoreboardName(client.player.getNameForScoreboard());
		if (self.isBlank()) {
			self = profileName(client.player.getGameProfile());
		}
		if (self.isBlank()) {
			self = profileName(client.getGameProfile());
		}

		Collection<PlayerListEntry> entries = readTabList(net);
		if (entries == null) {
			chat("Could not read the player list.", Formatting.RED);
			return;
		}

		LinkedHashMap<String, String> unique = new LinkedHashMap<>();
		for (PlayerListEntry entry : entries) {
			if (entry == null) {
				continue;
			}
			GameProfile profile = entry.getProfile();
			UUID id = profileId(profile);
			if (id != null && (id.equals(selfId) || id.version() == 2)) {
				continue;
			}
			String name = profileName(profile);
			String stripped = Formatting.strip(name);
			if (stripped != null) {
				name = stripped;
			}
			name = MassMsgConfig.sanitizeName(name);
			if (name.isBlank() || !LEGAL_NAME.matcher(name).matches()) {
				continue;
			}
			if (!self.isBlank() && name.equalsIgnoreCase(self)) {
				continue;
			}
			if (config.isExempt(name)) {
				continue;
			}
			unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
		}
		targets.addAll(unique.values());

		if (targets.isEmpty()) {
			chat("No one to message (everyone is you, invalid, vanished, or exempt).", Formatting.RED);
			return;
		}

		if (config.shuffle) {
			Collections.shuffle(targets, RANDOM);
		}

		running = true;
		String mode = (config.listedOnly ? "listed tab" : "full player list");
		chat("Sending /" + command + " to " + targets.size() + " player(s) (" + mode
				+ "), delay " + delayTicks + " ticks.", Formatting.GREEN);
		if (config.exempt.isEmpty()) {
			chat("Exempt list is empty. Add names with /massmsg exempt add <name>", Formatting.GRAY);
		} else {
			chat("Exempt: " + String.join(", ", config.exempt), Formatting.GRAY);
		}
	}

	public boolean stop(String reason) {
		if (!running) {
			return false;
		}
		running = false;
		String extra = sent > 0 || skipped > 0
				? " (sent " + sent + ", skipped " + skipped + ")"
				: "";
		if (reason == null || reason.isBlank()) {
			chat("Stopped." + extra, Formatting.YELLOW);
		} else {
			chat("Stopped — " + reason + extra, Formatting.YELLOW);
		}
		return true;
	}

	public void tick() {
		if (!running) {
			return;
		}
		if (client.player == null || client.getNetworkHandler() == null) {
			stop("left the server");
			return;
		}
		if (timer > 0) {
			timer--;
			return;
		}

		advancePastGone();
		if (!running) {
			return;
		}
		if (index >= targets.size()) {
			stop("finished");
			return;
		}

		String target = targets.get(index);
		String body = buildBody(target);
		if (body.isBlank()) {
			skipped++;
			index++;
			timer = 0;
			return;
		}

		String line = command + " " + target + " " + body;
		if (line.length() > MAX_COMMAND_LENGTH) {
			int keep = MAX_COMMAND_LENGTH - (command.length() + 1 + target.length() + 1);
			if (keep < 1) {
				chat("Skipping " + target + " (command too long).", Formatting.RED);
				skipped++;
				index++;
				timer = 0;
				return;
			}
			body = body.substring(0, keep);
			line = command + " " + target + " " + body;
		}

		ClientPlayNetworkHandler net = client.getNetworkHandler();
		if (net == null) {
			stop("left the server");
			return;
		}
		try {
			net.sendChatCommand(line);
		} catch (Exception e) {
			MassMsgClient.LOGGER.warn("Failed sending /{} to {}", command, target, e);
			chat("Failed to send to " + target + ".", Formatting.RED);
			skipped++;
			index++;
			timer = Math.max(1, liveDelay()) - 1;
			return;
		}

		sent++;
		overlay("→ /" + command + " " + target + "  (" + (index + 1) + "/" + targets.size() + ")");

		index++;
		if (index >= targets.size()) {
			stop("finished");
			return;
		}
		timer = Math.max(1, liveDelay()) - 1;
	}

	public void onIncomingChat(String raw, boolean overlay) {
		if (!running || config.ignoreErrors || overlay || raw == null) {
			return;
		}
		String msg = raw.toLowerCase(Locale.ROOT);
		if (msg.contains("[massmsg]")) {
			return;
		}
		if (looksLikePlayerChat(msg)) {
			return;
		}
		if (isFatalCommandError(msg)) {
			stop("server rejected /" + command);
		}
	}

	public void chat(String text, Formatting color) {
		if (client.player == null) {
			return;
		}
		client.player.sendMessage(
				Text.literal("[MassMsg] ").formatted(Formatting.GOLD)
						.append(Text.literal(text).formatted(color)),
				false);
	}

	private void overlay(String text) {
		if (client.player == null) {
			return;
		}
		client.player.sendMessage(
				Text.literal("[MassMsg] ").formatted(Formatting.GOLD)
						.append(Text.literal(text).formatted(Formatting.AQUA)),
				true);
	}

	private int liveDelay() {
		int delay = config != null ? config.delayTicks : delayTicks;
		if (delay < 1) {
			delay = delayTicks;
		}
		return delay;
	}

	private String buildBody(String target) {
		String source = template != null ? template : config.message;
		if (config != null && config.message != null) {
			source = config.message;
		}
		String body = source.replace("{player}", target);
		body = body.replace('\n', ' ').replace('\r', ' ').replace("§", "").strip();
		return body;
	}

	private void advancePastGone() {
		while (index < targets.size()) {
			String target = targets.get(index);
			if (config != null && config.isExempt(target)) {
				skipped++;
				index++;
				continue;
			}
			if (!stillOnline(target)) {
				skipped++;
				index++;
				continue;
			}
			return;
		}
		stop("finished");
	}

	private Collection<PlayerListEntry> readTabList(ClientPlayNetworkHandler net) {
		try {
			if (config != null && !config.listedOnly) {
				Collection<PlayerListEntry> all = net.getPlayerList();
				if (all != null && !all.isEmpty()) {
					return new ArrayList<>(all);
				}
			}
			Collection<PlayerListEntry> listed = net.getListedPlayerListEntries();
			if (listed != null && !listed.isEmpty()) {
				return new ArrayList<>(listed);
			}
			Collection<PlayerListEntry> all = net.getPlayerList();
			return all == null ? List.of() : new ArrayList<>(all);
		} catch (Exception e) {
			MassMsgClient.LOGGER.warn("Could not read player list", e);
			return null;
		}
	}

	private boolean stillOnline(String name) {
		ClientPlayNetworkHandler net = client.getNetworkHandler();
		if (net == null) {
			return false;
		}
		try {
			PlayerListEntry match = findEntry(net, name);
			if (match == null) {
				return false;
			}
			if (config != null && !config.listedOnly) {
				return true;
			}
			Collection<PlayerListEntry> listed = net.getListedPlayerListEntries();
			if (listed != null && !listed.isEmpty()) {
				return listed.contains(match);
			}
			return true;
		} catch (Exception ignored) {
			return true;
		}
	}

	private static PlayerListEntry findEntry(ClientPlayNetworkHandler net, String name) {
		PlayerListEntry match = net.getCaseInsensitivePlayerInfo(name);
		if (match != null) {
			return match;
		}
		match = net.getPlayerListEntry(name);
		if (match != null) {
			return match;
		}
		for (PlayerListEntry entry : net.getPlayerList()) {
			if (entry == null) {
				continue;
			}
			String other = MassMsgConfig.sanitizeName(profileName(entry.getProfile()));
			if (name.equalsIgnoreCase(other)) {
				return entry;
			}
		}
		return null;
	}

	private static String scoreboardName(String name) {
		if (name == null) {
			return "";
		}
		String stripped = Formatting.strip(name);
		return MassMsgConfig.sanitizeName(stripped != null ? stripped : name);
	}

	private static boolean looksLikePlayerChat(String msg) {
		String trimmed = msg.strip();
		return (trimmed.startsWith("<") && trimmed.contains(">"))
				|| trimmed.contains(" » ");
	}

	private static boolean isFatalCommandError(String msg) {
		return msg.contains("unknown command")
				|| msg.contains("unknown or incomplete command")
				|| msg.contains("not a valid command")
				|| msg.contains("no such command")
				|| msg.contains("no permission to")
				|| msg.contains("don't have permission")
				|| msg.contains("do not have permission")
				|| msg.contains("you do not have access")
				|| msg.contains("you don't have permission")
				|| msg.contains("i'm sorry, but you do not have");
	}

	private static String profileName(GameProfile profile) {
		if (profile == null) {
			return "";
		}
		try {
			String n = profile.name();
			if (n != null && !n.isBlank()) {
				return n;
			}
		} catch (Throwable ignored) {
		}
		Object value = invoke(profile, nameAccessor(), nameField());
		return value == null ? "" : value.toString();
	}

	private static UUID profileId(GameProfile profile) {
		if (profile == null) {
			return null;
		}
		try {
			return profile.id();
		} catch (Throwable ignored) {
		}
		Object value = invoke(profile, idAccessor(), idField());
		if (value instanceof UUID uuid) {
			return uuid;
		}
		if (value instanceof String s) {
			try {
				return UUID.fromString(s);
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return null;
	}

	private static Object invoke(GameProfile profile, Method method, Field field) {
		if (profile == null) {
			return null;
		}
		if (method != null) {
			try {
				return method.invoke(profile);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		if (field != null) {
			try {
				return field.get(profile);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return null;
	}

	private static Method nameAccessor() {
		resolveAccessors();
		return nameMethod;
	}

	private static Method idAccessor() {
		resolveAccessors();
		return idMethod;
	}

	private static Field nameField() {
		resolveAccessors();
		return nameField;
	}

	private static Field idField() {
		resolveAccessors();
		return idField;
	}

	private static void resolveAccessors() {
		if (resolvedAccessors) {
			return;
		}
		resolvedAccessors = true;
		nameMethod = findMethod(GameProfile.class, "name", "getName");
		idMethod = findMethod(GameProfile.class, "id", "getId");
		nameField = findField(GameProfile.class, "name");
		idField = findField(GameProfile.class, "id");
	}

	private static Method findMethod(Class<?> type, String... names) {
		for (String name : names) {
			try {
				Method method = type.getMethod(name);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static Field findField(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException ignored) {
			return null;
		}
	}
}
