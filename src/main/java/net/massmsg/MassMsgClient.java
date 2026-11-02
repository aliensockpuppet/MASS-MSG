package net.massmsg;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Consumer;

public final class MassMsgClient implements ClientModInitializer {
	public static final String MOD_ID = "massmsg";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static KeyBinding.Category category;
	private static MassMsgConfig config;
	private static MassMsgRunner runner;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		try {
			initClient();
		} catch (Throwable t) {
			LOGGER.error("MassMsg failed to initialize", t);
		}
	}

	private void initClient() {
		config = MassMsgConfig.load();
		runner = new MassMsgRunner(config);

		try {
			category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		} catch (Throwable alreadyRegistered) {
			category = KeyBinding.Category.MISC;
		}
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.massmsg.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				if (client.player == null || client.getNetworkHandler() == null) {
					continue;
				}
				if (runner.isRunning()) {
					runner.stop("keybind");
				} else {
					runner.start();
				}
			}
			runner.tick();
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) ->
				runner.onIncomingChat(message.getString(), overlay));
		// Player chat is ignored (sender != null). Plugin/system lines that arrive
		// as unsigned chat still get scanned for fatal command errors.
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
			if (sender != null) {
				return;
			}
			runner.onIncomingChat(message.getString(), false);
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("massmsg")
						.executes(ctx -> {
							status(ctx.getSource()::sendFeedback);
							return 1;
						})
						.then(ClientCommandManager.literal("help").executes(ctx -> {
							help(ctx.getSource()::sendFeedback);
							return 1;
						}))
						.then(ClientCommandManager.literal("start").executes(ctx -> {
							runner.start();
							return 1;
						}))
						.then(ClientCommandManager.literal("stop").executes(ctx -> {
							if (!runner.stop("command")) {
								ctx.getSource().sendFeedback(Text.literal("[MassMsg] Not running.")
										.formatted(Formatting.YELLOW));
							}
							return 1;
						}))
						.then(ClientCommandManager.literal("reload").executes(ctx -> {
							config = MassMsgConfig.load();
							runner.setConfig(config);
							ctx.getSource().sendFeedback(Text.literal("[MassMsg] Config reloaded.")
									.formatted(Formatting.GREEN));
							return 1;
						}))
						.then(ClientCommandManager.literal("status").executes(ctx -> {
							status(ctx.getSource()::sendFeedback);
							return 1;
						}))
						.then(ClientCommandManager.literal("message")
								.then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
										.executes(ctx -> {
											config.message = StringArgumentType.getString(ctx, "text");
											config.save();
											ctx.getSource().sendFeedback(Text.literal("[MassMsg] Message set to: "
													+ config.message).formatted(Formatting.GREEN));
											return 1;
										})))
						.then(ClientCommandManager.literal("delay")
								.then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(
												MassMsgConfig.MIN_DELAY_TICKS, MassMsgConfig.MAX_DELAY_TICKS))
										.executes(ctx -> {
											config.delayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
											config.save();
											ctx.getSource().sendFeedback(Text.literal("[MassMsg] Delay set to "
													+ config.delayTicks + " ticks ("
													+ String.format(Locale.ROOT, "%.1f", config.delayTicks / 20.0)
													+ "s).").formatted(Formatting.GREEN));
											return 1;
										})))
						.then(ClientCommandManager.literal("command")
								.then(ClientCommandManager.argument("name", StringArgumentType.word())
										.executes(ctx -> {
											String name = StringArgumentType.getString(ctx, "name")
													.replace("/", "").toLowerCase(Locale.ROOT);
											if (!MassMsgConfig.ALLOWED_COMMANDS.contains(name)) {
												ctx.getSource().sendFeedback(Text.literal(
																"[MassMsg] Allowed: " + String.join(", ",
																		MassMsgConfig.ALLOWED_COMMANDS))
														.formatted(Formatting.RED));
												return 0;
											}
											config.command = name;
											config.save();
											ctx.getSource().sendFeedback(Text.literal("[MassMsg] Command set to /"
													+ config.command).formatted(Formatting.GREEN));
											return 1;
										})))
						.then(ClientCommandManager.literal("shuffle")
								.then(ClientCommandManager.literal("on").executes(ctx -> {
									config.shuffle = true;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Shuffle on.")
											.formatted(Formatting.GREEN));
									return 1;
								}))
								.then(ClientCommandManager.literal("off").executes(ctx -> {
									config.shuffle = false;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Shuffle off.")
											.formatted(Formatting.GREEN));
									return 1;
								})))
						.then(ClientCommandManager.literal("ignore")
								.then(ClientCommandManager.literal("on").executes(ctx -> {
									config.ignoreErrors = true;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Ignore errors on.")
											.formatted(Formatting.GREEN));
									return 1;
								}))
								.then(ClientCommandManager.literal("off").executes(ctx -> {
									config.ignoreErrors = false;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Ignore errors off.")
											.formatted(Formatting.GREEN));
									return 1;
								})))
						.then(ClientCommandManager.literal("listed")
								.then(ClientCommandManager.literal("on").executes(ctx -> {
									config.listedOnly = true;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Listed tab only.")
											.formatted(Formatting.GREEN));
									return 1;
								}))
								.then(ClientCommandManager.literal("off").executes(ctx -> {
									config.listedOnly = false;
									config.save();
									ctx.getSource().sendFeedback(Text.literal("[MassMsg] Full player list.")
											.formatted(Formatting.GREEN));
									return 1;
								})))
						.then(ClientCommandManager.literal("exempt")
								.then(ClientCommandManager.literal("add")
										.then(ClientCommandManager.argument("name", StringArgumentType.word())
												.executes(ctx -> {
													String name = StringArgumentType.getString(ctx, "name");
													if (config.addExempt(name)) {
														ctx.getSource().sendFeedback(Text.literal("[MassMsg] Exempted "
																+ name).formatted(Formatting.GREEN));
													} else {
														ctx.getSource().sendFeedback(Text.literal("[MassMsg] "
																		+ name + " is already exempt or invalid.")
																.formatted(Formatting.YELLOW));
													}
													return 1;
												})))
								.then(ClientCommandManager.literal("remove")
										.then(ClientCommandManager.argument("name", StringArgumentType.word())
												.executes(ctx -> {
													String name = StringArgumentType.getString(ctx, "name");
													if (config.removeExempt(name)) {
														ctx.getSource().sendFeedback(Text.literal("[MassMsg] Removed "
																+ name).formatted(Formatting.GREEN));
													} else {
														ctx.getSource().sendFeedback(Text.literal("[MassMsg] "
																		+ name + " was not on the list.")
																.formatted(Formatting.YELLOW));
													}
													return 1;
												})))
								.then(ClientCommandManager.literal("list").executes(ctx -> {
									if (config.exempt.isEmpty()) {
										ctx.getSource().sendFeedback(Text.literal("[MassMsg] Exempt list is empty.")
												.formatted(Formatting.GRAY));
									} else {
										ctx.getSource().sendFeedback(Text.literal("[MassMsg] Exempt: "
												+ String.join(", ", config.exempt)).formatted(Formatting.GRAY));
									}
									return 1;
								})))));
	}

	private static void status(Consumer<Text> out) {
		if (runner.isRunning()) {
			out.accept(Text.literal("[MassMsg] Running, " + runner.remaining()
					+ " left of " + runner.total() + " via /" + runner.currentCommand())
					.formatted(Formatting.GREEN));
		} else {
			out.accept(Text.literal("[MassMsg] Idle. /" + config.command + " \"" + config.message
					+ "\" delay " + config.delayTicks + " ticks, shuffle " + (config.shuffle ? "on" : "off")
					+ ", " + config.exempt.size() + " exempt.").formatted(Formatting.GRAY));
		}
	}

	private static void help(Consumer<Text> out) {
		out.accept(Text.literal("[MassMsg] /massmsg start | stop | status | reload").formatted(Formatting.GOLD));
		out.accept(Text.literal("  message <text>   {player} is replaced with each name").formatted(Formatting.GRAY));
		out.accept(Text.literal("  delay <ticks>    20 ticks = 1 second, max 1200").formatted(Formatting.GRAY));
		out.accept(Text.literal("  command <name>   msg, tell, w, whisper, message, m, pm").formatted(Formatting.GRAY));
		out.accept(Text.literal("  shuffle on|off   randomize tab-list order").formatted(Formatting.GRAY));
		out.accept(Text.literal("  ignore on|off    keep going after unknown-command errors").formatted(Formatting.GRAY));
		out.accept(Text.literal("  listed on|off    on = tab only, off = everyone (default)").formatted(Formatting.GRAY));
		out.accept(Text.literal("  exempt add|remove|list").formatted(Formatting.GRAY));
		out.accept(Text.literal("  Keybind M toggles. Config: .minecraft/config/massmsg.json").formatted(Formatting.GRAY));
	}
}
