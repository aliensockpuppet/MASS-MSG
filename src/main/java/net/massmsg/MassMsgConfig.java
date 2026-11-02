package net.massmsg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MassMsgConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("massmsg.json");

	public static final int MIN_DELAY_TICKS = 1;
	public static final int MAX_DELAY_TICKS = 1200;
	public static final int MAX_MESSAGE_LENGTH = 256;

	public static final Set<String> ALLOWED_COMMANDS = Set.of(
			"msg", "tell", "w", "whisper", "message", "m", "pm");

	public String command = "msg";
	public static final String DEFAULT_MESSAGE = readDefaultMessage();

	public String message = DEFAULT_MESSAGE;
	public int delayTicks = 20;
	public boolean ignoreErrors = false;
	public boolean shuffle = true;
	public boolean listedOnly = false;
	public List<String> exempt = new ArrayList<>();

	private static String readDefaultMessage() {
		try (InputStream in = MassMsgConfig.class.getResourceAsStream("/assets/massmsg/default_message.txt")) {
			if (in != null) {
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				if (!text.isEmpty()) {
					return text;
				}
			}
		} catch (Throwable ignored) {
		}
		return "hi";
	}

	public static MassMsgConfig load() {
		if (Files.exists(PATH)) {
			try {
				MassMsgConfig cfg = GSON.fromJson(Files.readString(PATH), MassMsgConfig.class);
				if (cfg != null) {
					cfg.normalize();
					return cfg;
				}
			} catch (Exception e) {
				MassMsgClient.LOGGER.error("Could not read config, keeping file and using defaults", e);
				MassMsgConfig fallback = new MassMsgConfig();
				fallback.normalize();
				return fallback;
			}
		}
		MassMsgConfig fresh = new MassMsgConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		normalize();
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			MassMsgClient.LOGGER.error("Could not write config", e);
		}
	}

	public boolean isExempt(String name) {
		if (name == null) {
			return false;
		}
		for (String entry : exempt) {
			if (entry != null && entry.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	public boolean addExempt(String name) {
		name = sanitizeName(name);
		if (name.isEmpty() || isExempt(name)) {
			return false;
		}
		exempt.add(name);
		save();
		return true;
	}

	public boolean removeExempt(String name) {
		boolean removed = exempt.removeIf(s -> s != null && s.equalsIgnoreCase(name));
		if (removed) {
			save();
		}
		return removed;
	}

	public static String sanitizeName(String name) {
		if (name == null) {
			return "";
		}
		return name.replace("§", "").replace("&", "").trim();
	}

	public void normalize() {
		if (command == null || command.isBlank()) {
			command = "msg";
		}
		command = command.replace("/", "").trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_COMMANDS.contains(command)) {
			command = "msg";
		}
		if (message == null) {
			message = DEFAULT_MESSAGE;
		}
		message = message.replace('\n', ' ').replace('\r', ' ').replace("§", "").strip();
		if (message.isBlank()) {
			message = DEFAULT_MESSAGE;
		}
		if (message.length() > MAX_MESSAGE_LENGTH) {
			message = message.substring(0, MAX_MESSAGE_LENGTH);
		}
		if (delayTicks < MIN_DELAY_TICKS) {
			delayTicks = MIN_DELAY_TICKS;
		}
		if (delayTicks > MAX_DELAY_TICKS) {
			delayTicks = MAX_DELAY_TICKS;
		}
		if (exempt == null) {
			exempt = new ArrayList<>();
		} else {
			LinkedHashMap<String, String> unique = new LinkedHashMap<>();
			for (String entry : exempt) {
				String clean = sanitizeName(entry);
				if (!clean.isEmpty()) {
					unique.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
				}
			}
			exempt = new ArrayList<>(unique.values());
		}
	}
}
