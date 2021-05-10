/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.plugin.commands;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import com.flowpowered.math.vector.Vector3i;
import com.google.common.collect.Lists;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.marker.MarkerAPI;
import de.bluecolored.bluemap.api.marker.MarkerSet;
import de.bluecolored.bluemap.api.marker.POIMarker;
import de.bluecolored.bluemap.common.plugin.Plugin;
import de.bluecolored.bluemap.common.plugin.serverinterface.CommandSource;
import de.bluecolored.bluemap.common.plugin.text.Text;
import de.bluecolored.bluemap.common.plugin.text.TextColor;
import de.bluecolored.bluemap.common.plugin.text.TextFormat;
import de.bluecolored.bluemap.common.rendermanager.MapPurgeTask;
import de.bluecolored.bluemap.common.rendermanager.RenderTask;
import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.map.MapRenderState;
import de.bluecolored.bluemap.core.mca.ChunkAnvil112;
import de.bluecolored.bluemap.core.mca.MCAChunk;
import de.bluecolored.bluemap.core.mca.MCAWorld;
import de.bluecolored.bluemap.core.resourcepack.ParseResourceException;
import de.bluecolored.bluemap.core.world.Block;
import de.bluecolored.bluemap.core.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public class Commands<S> {
	
	public static final String DEFAULT_MARKER_SET_ID = "markers"; 

	private final Plugin plugin;
	private final CommandDispatcher<S> dispatcher;
	private final Function<S, CommandSource> commandSourceInterface;
	
	private final CommandHelper helper;
	
	public Commands(Plugin plugin, CommandDispatcher<S> dispatcher, Function<S, CommandSource> commandSourceInterface) {
		this.plugin = plugin;
		this.dispatcher = dispatcher;
		this.commandSourceInterface = commandSourceInterface;
		
		this.helper = new CommandHelper(plugin);
		
		init();
	}
	
	public void init() {
		// commands
		LiteralCommandNode<S> baseCommand = 
				literal("bluemap")
				.requires(requirementsUnloaded("bluemap.status"))
				.executes(this::statusCommand)
				.build();

		LiteralCommandNode<S> versionCommand =
				literal("version")
						.requires(requirementsUnloaded("bluemap.version"))
						.executes(this::versionCommand)
						.build();
		
		LiteralCommandNode<S> helpCommand = 
				literal("help")
				.requires(requirementsUnloaded("bluemap.help"))
				.executes(this::helpCommand)
				.build();
		
		LiteralCommandNode<S> reloadCommand = 
				literal("reload")
				.requires(requirementsUnloaded("bluemap.reload"))
				.executes(this::reloadCommand)
				.build();
		
		LiteralCommandNode<S> debugCommand = 
				literal("debug")
				.requires(requirements("bluemap.debug"))
				
				.then(literal("block")
						.executes(this::debugBlockCommand)
		
						.then(argument("world", StringArgumentType.string()).suggests(new WorldSuggestionProvider<>(plugin))
								.then(argument("x", DoubleArgumentType.doubleArg())
										.then(argument("y", DoubleArgumentType.doubleArg())
												.then(argument("z", DoubleArgumentType.doubleArg())
														.executes(this::debugBlockCommand))))))

				.then(literal("flush")
						.executes(this::debugFlushCommand)
						
						.then(argument("world", StringArgumentType.string()).suggests(new WorldSuggestionProvider<>(plugin))
								.executes(this::debugFlushCommand)))
				
				.then(literal("cache")
						.executes(this::debugClearCacheCommand))
						
				.build();
		
		LiteralCommandNode<S> pauseCommand = 
				literal("stop")
				.requires(requirements("bluemap.stop"))
				.executes(this::stopCommand)
				.build();
		
		LiteralCommandNode<S> resumeCommand = 
				literal("start")
				.requires(requirements("bluemap.start"))
				.executes(this::startCommand)
				.build();

		LiteralCommandNode<S> forceUpdateCommand =
				addRenderArguments(
						literal("force-update")
						.requires(requirements("bluemap.update.force")),
						this::forceUpdateCommand
				).build();

		LiteralCommandNode<S> updateCommand =
				addRenderArguments(
						literal("update")
						.requires(requirements("bluemap.update")),
						this::updateCommand
				).build();

		LiteralCommandNode<S> purgeCommand =
				literal("purge")
						.requires(requirements("bluemap.purge"))
						.then(argument("map", StringArgumentType.string()).suggests(new MapSuggestionProvider<>(plugin))
								.executes(this::purgeCommand))
						.build();

		LiteralCommandNode<S> cancelCommand =
				literal("cancel")
				.requires(requirements("bluemap.cancel"))
				.executes(this::cancelCommand)
				.then(argument("task-ref", StringArgumentType.string()).suggests(new TaskRefSuggestionProvider<>(helper))
						.executes(this::cancelCommand))
				.build();
		
		LiteralCommandNode<S> worldsCommand = 
				literal("worlds")
				.requires(requirements("bluemap.status"))
				.executes(this::worldsCommand)
				.build();
		
		LiteralCommandNode<S> mapsCommand = 
				literal("maps")
				.requires(requirements("bluemap.status"))
				.executes(this::mapsCommand)
				.build();
		
		LiteralCommandNode<S> markerCommand = 
				literal("marker")
				.requires(requirements("bluemap.marker"))
				.build();
		
		LiteralCommandNode<S> createMarkerCommand = 
				literal("create")
				.requires(requirements("bluemap.marker"))
				.then(argument("id", StringArgumentType.word())
						.then(argument("map", StringArgumentType.string()).suggests(new MapSuggestionProvider<>(plugin))
						
							.then(argument("label", StringArgumentType.string())
									.executes(this::createMarkerCommand))
							
							.then(argument("x", DoubleArgumentType.doubleArg())
									.then(argument("y", DoubleArgumentType.doubleArg())
											.then(argument("z", DoubleArgumentType.doubleArg())
													.then(argument("label", StringArgumentType.string())
															.executes(this::createMarkerCommand)))))))
				.build();
		
		LiteralCommandNode<S> removeMarkerCommand = 
				literal("remove")
				.requires(requirements("bluemap.marker"))
				.then(argument("id", StringArgumentType.word()).suggests(MarkerIdSuggestionProvider.getInstance())
						.executes(this::removeMarkerCommand))
				.build();
		
		// command tree
		dispatcher.getRoot().addChild(baseCommand);
		baseCommand.addChild(versionCommand);
		baseCommand.addChild(helpCommand);
		baseCommand.addChild(reloadCommand);
		baseCommand.addChild(debugCommand);
		baseCommand.addChild(pauseCommand);
		baseCommand.addChild(resumeCommand);
		baseCommand.addChild(forceUpdateCommand);
		baseCommand.addChild(updateCommand);
		baseCommand.addChild(cancelCommand);
		baseCommand.addChild(purgeCommand);
		baseCommand.addChild(worldsCommand);
		baseCommand.addChild(mapsCommand);
		baseCommand.addChild(markerCommand);
		markerCommand.addChild(createMarkerCommand);
		markerCommand.addChild(removeMarkerCommand);
	}

	private <B extends ArgumentBuilder<S, B>> B addRenderArguments(B builder, Command<S> command) {
		return builder
			.executes(command) // /bluemap render

			.then(argument("radius", IntegerArgumentType.integer())
					.executes(command)) // /bluemap render <radius>

			.then(argument("x", DoubleArgumentType.doubleArg())
					.then(argument("z", DoubleArgumentType.doubleArg())
							.then(argument("radius", IntegerArgumentType.integer())
									.executes(command)))) // /bluemap render <x> <z> <radius>

			.then(argument("world|map", StringArgumentType.string()).suggests(new WorldOrMapSuggestionProvider<>(plugin))
					.executes(command) // /bluemap render <world|map>

					.then(argument("x", DoubleArgumentType.doubleArg())
							.then(argument("z", DoubleArgumentType.doubleArg())
									.then(argument("radius", IntegerArgumentType.integer())
											.executes(command))))); // /bluemap render <world|map> <x> <z> <radius>
	}
	
	private Predicate<S> requirements(String permission){
		return s -> {
			CommandSource source = commandSourceInterface.apply(s);
			return plugin.isLoaded() && source.hasPermission(permission);
		};
	}
	
	private Predicate<S> requirementsUnloaded(String permission){
		return s -> {
			CommandSource source = commandSourceInterface.apply(s);
			return source.hasPermission(permission);
		};
	}
	
	private LiteralArgumentBuilder<S> literal(String name){
		return LiteralArgumentBuilder.literal(name);
	}
	
	private <T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type){
		return RequiredArgumentBuilder.argument(name, type);
	}
	
	private <T> Optional<T> getOptionalArgument(CommandContext<S> context, String argumentName, Class<T> type) {
		try {
			return Optional.of(context.getArgument(argumentName, type));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
	
	private Optional<World> parseWorld(String worldName) {
		for (World world : plugin.getWorlds()) {
			if (world.getName().equalsIgnoreCase(worldName)) {
				return Optional.of(world);
			}
		}
		
		return Optional.empty();
	}
	
	private Optional<BmMap> parseMap(String mapId) {
		for (BmMap map : plugin.getMapTypes()) {
			if (map.getId().equalsIgnoreCase(mapId)) {
				return Optional.of(map);
			}
		}
		
		return Optional.empty();
	}
	
	private Optional<UUID> parseUUID(String uuidString) {
		try {
			return Optional.of(UUID.fromString(uuidString));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();			
		}
	}
	
	
	// --- COMMANDS ---
	
	public int statusCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		if (!plugin.isLoaded()) {
			source.sendMessage(Text.of(TextColor.RED, "BlueMap is not loaded! Try /bluemap reload"));
			return 0;
		}
		
		source.sendMessages(helper.createStatusMessage());
		return 1;
	}

	public int versionCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());

		int renderThreadCount = 0;
		if (plugin.isLoaded()) {
			renderThreadCount = plugin.getRenderManager().getWorkerThreadCount();
		}

		source.sendMessage(Text.of(TextFormat.BOLD, TextColor.BLUE, "Version: ", TextColor.WHITE, BlueMap.VERSION));
		source.sendMessage(Text.of(TextColor.GRAY, "Implementation: ", TextColor.WHITE, plugin.getImplementationType()));
		source.sendMessage(Text.of(TextColor.GRAY, "Minecraft compatibility: ", TextColor.WHITE, plugin.getMinecraftVersion().getVersionString()));
		source.sendMessage(Text.of(TextColor.GRAY, "Render-threads: ", TextColor.WHITE, renderThreadCount));
		source.sendMessage(Text.of(TextColor.GRAY, "Available processors: ", TextColor.WHITE, Runtime.getRuntime().availableProcessors()));
		source.sendMessage(Text.of(TextColor.GRAY, "Available memory: ", TextColor.WHITE, (Runtime.getRuntime().maxMemory() / 1024L / 1024L) + " MiB"));

		if (plugin.getMinecraftVersion().isAtLeast(MinecraftVersion.MC_1_15)) {
			String clipboardValue =
					"Version: " + BlueMap.VERSION + "\n" +
					"Implementation: " + plugin.getImplementationType() + "\n" +
					"Minecraft compatibility: " + plugin.getMinecraftVersion().getVersionString() + "\n" +
					"Render-threads: " + renderThreadCount + "\n" +
					"Available processors: " + Runtime.getRuntime().availableProcessors() + "\n" +
					"Available memory: " + Runtime.getRuntime().maxMemory() / 1024L / 1024L + " MiB";
			source.sendMessage(Text.of(TextColor.DARK_GRAY, "[copy to clipboard]")
					.setClickAction(Text.ClickAction.COPY_TO_CLIPBOARD, clipboardValue)
					.setHoverText(Text.of(TextColor.GRAY, "click to copy the above text .. ", TextFormat.ITALIC, TextColor.GRAY, "duh!")));
		}

		return 1;
	}
	
	public int helpCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		source.sendMessage(Text.of(TextColor.BLUE, "BlueMap Commands:"));
		for (String usage : dispatcher.getAllUsage(dispatcher.getRoot().getChild("bluemap"), context.getSource(), true)) {
			Text usageText = Text.of(TextColor.GREEN, "/bluemap");
			
			String[] arguments = usage.split(" ");
			for (String arg : arguments) {
				if (arg.isEmpty()) continue;
				if (arg.charAt(0) == '<' && arg.charAt(arg.length() - 1) == '>') {
					usageText.addChild(Text.of(TextColor.GRAY, " " + arg));
				} else {
					usageText.addChild(Text.of(TextColor.WHITE, " " + arg));
				}
			}
			
			source.sendMessage(usageText);
		}
		
		source.sendMessage(
				Text.of(TextColor.BLUE, "\nOpen this link to get a description for each command:\n")
				.addChild(Text.of(TextColor.GRAY, "https://bluecolo.red/bluemap-commands").setClickAction(Text.ClickAction.OPEN_URL, "https://bluecolo.red/bluemap-commands"))
				);
		
		return 1;
	}
	
	public int reloadCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		source.sendMessage(Text.of(TextColor.GOLD, "Reloading BlueMap..."));
		
		new Thread(() -> {
			try {
				plugin.reload();
				
				if (plugin.isLoaded()) {
					source.sendMessage(Text.of(TextColor.GREEN, "BlueMap reloaded!"));
				} else {
					source.sendMessage(Text.of(TextColor.RED, "Could not load BlueMap! See the console for details!"));
				}

			} catch (IOException | ParseResourceException | RuntimeException ex) {
				Logger.global.logError("Failed to reload BlueMap!", ex);
				
				source.sendMessage(Text.of(TextColor.RED, "There was an error reloading BlueMap! See the console for details!"));
			}
		}).start();
		return 1;
	}

	public int debugClearCacheCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		for (World world : plugin.getWorlds()) {
			world.invalidateChunkCache();
		}
		
		source.sendMessage(Text.of(TextColor.GREEN, "All caches cleared!"));
		return 1;
	}
	

	public int debugFlushCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		// parse arguments
		Optional<String> worldName = getOptionalArgument(context, "world", String.class);
		
		final World world;
		if (worldName.isPresent()) {
			world = parseWorld(worldName.get()).orElse(null);
			
			if (world == null) {
				source.sendMessage(Text.of(TextColor.RED, "There is no ", helper.worldHelperHover(), " with this name: ", TextColor.WHITE, worldName.get()));
				return 0;
			}
		} else {
			world = source.getWorld().orElse(null);
			
			if (world == null) {
				source.sendMessage(Text.of(TextColor.RED, "Can't detect a location from this command-source, you'll have to define a world!"));
				return 0;
			}
		}
		
		new Thread(() -> {
			source.sendMessage(Text.of(TextColor.GOLD, "Saving world and flushing changes..."));
			try {
				if (plugin.flushWorldUpdates(world.getUUID())) {
					source.sendMessage(Text.of(TextColor.GREEN, "Successfully saved and flushed all changes."));
				} else {
					source.sendMessage(Text.of(TextColor.RED, "This operation is not supported by this implementation (" + plugin.getImplementationType() + ")"));
				}
			} catch (IOException ex) {
				source.sendMessage(Text.of(TextColor.RED, "There was an unexpected exception trying to save the world. Please check the console for more details..."));
				Logger.global.logError("Unexpected exception trying to save the world!", ex);
			}
		}).start();
		
		return 1;
	}
	
	public int debugBlockCommand(CommandContext<S> context) {
		final CommandSource source = commandSourceInterface.apply(context.getSource());
		
		// parse arguments
		Optional<String> worldName = getOptionalArgument(context, "world", String.class);
		Optional<Double> x = getOptionalArgument(context, "x", Double.class);
		Optional<Double> y = getOptionalArgument(context, "y", Double.class);
		Optional<Double> z = getOptionalArgument(context, "z", Double.class);
		
		final World world;
		final Vector3d position;
		
		if (worldName.isPresent() && x.isPresent() && y.isPresent() && z.isPresent()) {
			world = parseWorld(worldName.get()).orElse(null);
			position = new Vector3d(x.get(), y.get(), z.get());
			
			if (world == null) {
				source.sendMessage(Text.of(TextColor.RED, "There is no ", helper.worldHelperHover(), " with this name: ", TextColor.WHITE, worldName.get()));
				return 0;
			}
		} else {
			world = source.getWorld().orElse(null);
			position = source.getPosition().orElse(null);
			
			if (world == null || position == null) {
				source.sendMessage(Text.of(TextColor.RED, "Can't detect a location from this command-source, you'll have to define a world and position!"));
				return 0;
			}
		}
		
		new Thread(() -> {
			// collect and output debug info
			Vector3i blockPos = position.floor().toInt();
			Block block = world.getBlock(blockPos);
			Block blockBelow = world.getBlock(blockPos.add(0, -1, 0));
			
			String blockIdMeta = "";
			String blockBelowIdMeta = "";
			
			if (world instanceof MCAWorld) {
				MCAChunk chunk = ((MCAWorld) world).getChunk(MCAWorld.blockToChunk(blockPos));
				if (chunk instanceof ChunkAnvil112) {
					blockIdMeta = " (" + ((ChunkAnvil112) chunk).getBlockIdMeta(blockPos) + ")";
					blockBelowIdMeta = " (" + ((ChunkAnvil112) chunk).getBlockIdMeta(blockPos.add(0, -1, 0)) + ")";
				}
			}
			
			source.sendMessages(Lists.newArrayList(
					Text.of(TextColor.GOLD, "Block at you: ", TextColor.WHITE, block, TextColor.GRAY, blockIdMeta),
					Text.of(TextColor.GOLD, "Block below you: ", TextColor.WHITE, blockBelow, TextColor.GRAY, blockBelowIdMeta)
				));
		}).start();
		
		return 1;
	}
	
	public int stopCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		if (plugin.getRenderManager().isRunning()) {
			plugin.getRenderManager().stop();
			source.sendMessage(Text.of(TextColor.GREEN, "Render-Threads stopped!"));
			return 1;
		} else {
			source.sendMessage(Text.of(TextColor.RED, "Render-Threads are already stopped!"));
			return 0;
		}
	}
	
	public int startCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		if (!plugin.getRenderManager().isRunning()) {
			plugin.getRenderManager().start(plugin.getCoreConfig().getRenderThreadCount());
			source.sendMessage(Text.of(TextColor.GREEN, "Render-Threads started!"));
			return 1;
		} else {
			source.sendMessage(Text.of(TextColor.RED, "Render-Threads are already running!"));
			return 0;
		}
	}

	public int forceUpdateCommand(CommandContext<S> context) {
		return updateCommand(context, true);
	}

	public int updateCommand(CommandContext<S> context) {
		return updateCommand(context, false);
	}

	public int updateCommand(CommandContext<S> context, boolean force) {
		final CommandSource source = commandSourceInterface.apply(context.getSource());
		
		// parse world/map argument
		Optional<String> worldOrMap = getOptionalArgument(context, "world|map", String.class);
		
		final World worldToRender;
		final BmMap mapToRender;
		if (worldOrMap.isPresent()) {
			worldToRender = parseWorld(worldOrMap.get()).orElse(null);
			
			if (worldToRender == null) {
				mapToRender = parseMap(worldOrMap.get()).orElse(null);
				
				if (mapToRender == null) {
					source.sendMessage(Text.of(TextColor.RED, "There is no ", helper.worldHelperHover(), " or ", helper.mapHelperHover(), " with this name: ", TextColor.WHITE, worldOrMap.get()));
					return 0;
				}
			} else {
				mapToRender = null;
			}
		} else {
			worldToRender = source.getWorld().orElse(null);
			mapToRender = null;
			
			if (worldToRender == null) {
				source.sendMessage(Text.of(TextColor.RED, "Can't detect a world from this command-source, you'll have to define a world or a map to update!").setHoverText(Text.of(TextColor.GRAY, "/bluemap " + (force ? "force-update" : "update") + " <world|map>")));
				return 0;
			}
		}

		// parse radius and center arguments
		final int radius = getOptionalArgument(context, "radius", Integer.class).orElse(-1);
		final Vector2i center;
		if (radius >= 0) {
			Optional<Double> x = getOptionalArgument(context, "x", Double.class);
			Optional<Double> z = getOptionalArgument(context, "z", Double.class);
			
			if (x.isPresent() && z.isPresent()) {
				center = new Vector2i(x.get(), z.get());
			} else {
				Vector3d position = source.getPosition().orElse(null);
				if (position == null) {
					source.sendMessage(Text.of(TextColor.RED, "Can't detect a position from this command-source, you'll have to define x,z coordinates to update with a radius!").setHoverText(Text.of(TextColor.GRAY, "/bluemap " + (force ? "force-update" : "update") + " <x> <z> " + radius)));
					return 0;
				}
				
				center = position.toVector2(true).floor().toInt();
			}
		} else {
			center = null;
		}
		
		// execute render
		new Thread(() -> {
			try {
				List<BmMap> maps = new ArrayList<>();
				World world = worldToRender;
				if (worldToRender != null) {
					plugin.getServerInterface().persistWorldChanges(worldToRender.getUUID());
					for (BmMap map : plugin.getMapTypes()) {
						if (map.getWorld().equals(worldToRender)) maps.add(map);
					}
				} else {
					plugin.getServerInterface().persistWorldChanges(mapToRender.getWorld().getUUID());
					maps.add(mapToRender);
					world = mapToRender.getWorld();
				}

				List<Vector2i> regions = helper.getRegions(world, center, radius);

				if (force) {
					for (BmMap map : maps) {
						MapRenderState state = map.getRenderState();
						regions.forEach(region -> state.setRenderTime(region, -1));
					}
				}

				for (BmMap map : maps) {
					plugin.getRenderManager().scheduleRenderTask(helper.createMapUpdateTask(map, regions));
					source.sendMessage(Text.of(TextColor.GREEN, "Created new Update-Task for map '" + map.getId() + "' ", TextColor.GRAY, "(" + regions.size() + " regions, ~" + regions.size() * 1024L + " chunks)"));
				}
				source.sendMessage(Text.of(TextColor.GREEN, "Use ", TextColor.GRAY, "/bluemap", TextColor.GREEN, " to see the progress."));

			} catch (IOException ex) {
				source.sendMessage(Text.of(TextColor.RED, "There was an unexpected exception trying to save the world. Please check the console for more details..."));
				Logger.global.logError("Unexpected exception trying to save the world!", ex);
			}
		}).start();
		
		return 1;
	}

	public int cancelCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());

		Optional<String> ref = getOptionalArgument(context,"task-ref", String.class);
		if (!ref.isPresent()) {
			plugin.getRenderManager().removeAllRenderTasks();
			source.sendMessage(Text.of(TextColor.GREEN, "All tasks cancelled!"));
			return 1;
		}

		Optional<RenderTask> task = helper.getTaskForRef(ref.get());

		if (!task.isPresent()) {
			source.sendMessage(Text.of(TextColor.RED, "There is no task with this reference '" + ref.get() + "'!"));
			return 0;
		}

		if (plugin.getRenderManager().removeRenderTask(task.get())) {
			source.sendMessage(Text.of(TextColor.GREEN, "Task cancelled!"));
			return 1;
		} else {
			source.sendMessage(Text.of(TextColor.RED, "This task is either completed or got cancelled already!"));
			return 0;
		}
	}
	
	public int purgeCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		// parse map argument
		String mapId = context.getArgument("map", String.class);
		
		new Thread(() -> {
			try {
				Path mapFolder = plugin.getRenderConfig().getWebRoot().toPath().resolve("data").resolve(mapId);
				if (!Files.isDirectory(mapFolder)) {
					source.sendMessage(Text.of(TextColor.RED, "There is no map-data to purge for the map-id '" + mapId + "'!"));
					return;
				}

				Optional<BmMap> optMap = parseMap(mapId);

				// delete map
				MapPurgeTask purgeTask;
				if (optMap.isPresent()){
					purgeTask = new MapPurgeTask(optMap.get());
				} else {
					purgeTask = new MapPurgeTask(mapFolder);
				}

				plugin.getRenderManager().scheduleRenderTaskNext(purgeTask);
				source.sendMessage(Text.of(TextColor.GREEN, "Created new Task to purge map '" + mapId + "'"));

				// if map is loaded, reset it and start updating it after the purge
				if (optMap.isPresent()) {
					RenderTask updateTask = helper.createMapUpdateTask(optMap.get());
					plugin.getRenderManager().scheduleRenderTask(updateTask);
					source.sendMessage(Text.of(TextColor.GREEN, "Created new Update-Task for map '" + mapId + "'"));
					source.sendMessage(Text.of(TextColor.GRAY, "If you don't want to render this map again, you need to remove it from your configuration first!"));
				}

				source.sendMessage(Text.of(TextColor.GREEN, "Use ", TextColor.GRAY, "/bluemap", TextColor.GREEN, " to see the progress."));
			} catch (IOException | IllegalArgumentException e) {
				source.sendMessage(Text.of(TextColor.RED, "There was an error trying to purge '" + mapId + "', see console for details."));
				Logger.global.logError("Failed to purge map '" + mapId + "'!", e);
			}
		}).start();

		return 1;
	}
	
	public int worldsCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		source.sendMessage(Text.of(TextColor.BLUE, "Worlds loaded by BlueMap:"));
		for (World world : plugin.getWorlds()) {
			source.sendMessage(Text.of(TextColor.GRAY, " - ", TextColor.WHITE, world.getName()).setHoverText(Text.of(world.getSaveFolder(), TextColor.GRAY, " (" + world.getUUID() + ")")));
		}
		
		return 1;
	}
	
	public int mapsCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		source.sendMessage(Text.of(TextColor.BLUE, "Maps loaded by BlueMap:"));
		for (BmMap map : plugin.getMapTypes()) {
			source.sendMessage(Text.of(TextColor.GRAY, " - ", TextColor.WHITE, map.getId(), TextColor.GRAY, " (" + map.getName() + ")").setHoverText(Text.of(TextColor.WHITE, "World: ", TextColor.GRAY, map.getWorld().getName())));
		}
		
		return 1;
	}
	
	public int createMarkerCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());
		
		String markerId = context.getArgument("id", String.class);
		String markerLabel = context.getArgument("label", String.class)
				.replace("<", "&lt;")
				.replace(">", "&gt;");  //no html via commands
		
		// parse world/map argument
		String mapString = context.getArgument("map", String.class);
		BmMap map = parseMap(mapString).orElse(null);
		
		if (map == null) {
			source.sendMessage(Text.of(TextColor.RED, "There is no ", helper.mapHelperHover(), " with this name: ", TextColor.WHITE, mapString));
			return 0;
		}
		
		// parse position
		Optional<Double> x = getOptionalArgument(context, "x", Double.class);
		Optional<Double> y = getOptionalArgument(context, "y", Double.class);
		Optional<Double> z = getOptionalArgument(context, "z", Double.class);
		
		Vector3d position;
		
		if (x.isPresent() && y.isPresent() && z.isPresent()) {
			position = new Vector3d(x.get(), y.get(), z.get());
		} else {
			position = source.getPosition().orElse(null);
			
			if (position == null) {
				source.sendMessage(Text.of(TextColor.RED, "Can't detect a position from this command-source, you'll have to define the x,y,z coordinates for the marker!").setHoverText(Text.of(TextColor.GRAY, "/bluemap marker create " + markerId + " " + "[world|map] <x> <y> <z> <label>")));
				return 0;
			}
		}
		
		// get api
		BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
		if (api == null) {
			source.sendMessage(Text.of(TextColor.RED, "MarkerAPI is not available, try ", TextColor.GRAY, "/bluemap reload"));
			return 0;
		}
		
		// resolve api-map
		Optional<BlueMapMap> apiMap = api.getMap(map.getId());
		if (!apiMap.isPresent()) {
			source.sendMessage(Text.of(TextColor.RED, "Failed to get map from API, try ", TextColor.GRAY, "/bluemap reload"));
			return 0;
		}
		
		// add marker
		try {
			MarkerAPI markerApi = api.getMarkerAPI();
			
			MarkerSet set = markerApi.getMarkerSet(DEFAULT_MARKER_SET_ID).orElse(null);
			if (set == null) {
				set = markerApi.createMarkerSet(DEFAULT_MARKER_SET_ID);
				set.setLabel("Markers");
			}
			
			if (set.getMarker(markerId).isPresent()) {
				source.sendMessage(Text.of(TextColor.RED, "There already is a marker with this id: ", TextColor.WHITE, markerId));
				return 0;
			}
			
			POIMarker marker = set.createPOIMarker(markerId, apiMap.get(), position);
			marker.setLabel(markerLabel);
			
			markerApi.save();
			MarkerIdSuggestionProvider.getInstance().forceUpdate();
		} catch (IOException e) {
			source.sendMessage(Text.of(TextColor.RED, "There was an error trying to add the marker, please check the console for details!"));
			Logger.global.logError("Exception trying to add a marker!", e);
		}

		source.sendMessage(Text.of(TextColor.GREEN, "Marker added!"));
		return 1;
	}
	
	public int removeMarkerCommand(CommandContext<S> context) {
		CommandSource source = commandSourceInterface.apply(context.getSource());

		String markerId = context.getArgument("id", String.class);
		
		BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
		if (api == null) {
			source.sendMessage(Text.of(TextColor.RED, "MarkerAPI is not available, try ", TextColor.GRAY, "/bluemap reload"));
			return 0;
		}
		
		try {
			MarkerAPI markerApi = api.getMarkerAPI();
			
			MarkerSet set = markerApi.createMarkerSet("markers");
			if (!set.removeMarker(markerId)) {
				source.sendMessage(Text.of(TextColor.RED, "There is no marker with this id: ", TextColor.WHITE, markerId));
			}
			
			markerApi.save();
			MarkerIdSuggestionProvider.getInstance().forceUpdate();
		} catch (IOException e) {
			source.sendMessage(Text.of(TextColor.RED, "There was an error trying to remove the marker, please check the console for details!"));
			Logger.global.logError("Exception trying to remove a marker!", e);
		}

		source.sendMessage(Text.of(TextColor.GREEN, "Marker removed!"));
		return 1;
	}
	
}
