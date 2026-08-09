package encrypted.iconexporter.client.commands;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import encrypted.iconexporter.client.IconExporterClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class ExportCommand {
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("iconExport")
            .then(ClientCommands.argument("displayContext", StringArgumentType.word())
                .suggests(ExportCommand::GetDisplayContextSuggestions)
                .then(ClientCommands.argument("size", IntegerArgumentType.integer(1))
                    .then(ClientCommands.argument("overwrite", BoolArgumentType.bool())
                        .then(ClientCommands.argument("item", ItemArgument.item(registryAccess))
                            .executes(context -> {
                                var size = IntegerArgumentType.getInteger(context, "size");
                                var item = ItemArgument.getItem(context, "item");
                                var overwrite = BoolArgumentType.getBool(context, "overwrite");
                                var displayContext = StringArgumentType.getString(context, "displayContext");

                                try {
                                    renderIcon(context.getSource().getClient(), item.createItemStack(1), size, overwrite, ItemDisplayContext.valueOf(displayContext));
                                } catch (IOException e) {
                                    IconExporterClient.LOGGER.error("Error while rendering icon", e);
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                        .then(ClientCommands.literal("all")
                            .executes(context -> {
                                var size = IntegerArgumentType.getInteger(context, "size");
                                var overwrite = BoolArgumentType.getBool(context, "overwrite");
                                var displayContext = StringArgumentType.getString(context, "displayContext");

                                for (var item : BuiltInRegistries.ITEM) {
                                    try {
                                        renderIcon(context.getSource().getClient(), item.getDefaultInstance(), size, overwrite, ItemDisplayContext.valueOf(displayContext));
                                    } catch (IOException e) {
                                        IconExporterClient.LOGGER.error("Error while rendering icon", e);
                                    }
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                )
            )
        );
    }

    private static void renderIcon(Minecraft instance, ItemStack stack, int size, boolean overwrite, ItemDisplayContext context) throws IOException {
        var featureRenderDispatcher = instance.gameRenderer.featureRenderDispatcher();
        var renderState = new TrackingItemStackRenderState();

        instance.getItemModelResolver().updateForTopItem(renderState, stack, context, instance.level, instance.player, 0);
        var directory = instance.gameDirectory.toPath().resolve("export");
        var outputPath = directory.resolve(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + ".png");
        if (!Files.exists(directory))
            Files.createDirectories(directory);

        if (!overwrite && Files.exists(outputPath)) return;

        var atlas = new GuiItemAtlas(featureRenderDispatcher, size, size);
        var slot = atlas.getOrUpdate(renderState);

        if (slot == null) {
            atlas.close();
            throw new IllegalStateException( "Minecraft could not allocate an item atlas slot.");
        }

        readTextureAndSave(size, size, outputPath, atlas);
    }

    private static void readTextureAndSave(int width, int height, Path outputPath, GuiItemAtlas atlas) {
        int bytesPerPixel = atlas.texture.getFormat().blockSize();
        var buffer = RenderSystem.getDevice().createBuffer(
                () -> "Icon Export Readback",
                9,
                (long) width * height * bytesPerPixel
        );

        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(atlas.texture, buffer, 0L, () -> {
                    try (
                            GpuBufferSlice.MappedView read = buffer.map(true, false);
                            NativeImage image = new NativeImage(width, height, false)
                    ) {
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int pixel = read.data().getInt((x + y * width) * bytesPerPixel);
                                image.setPixelABGR(x, height - y - 1, pixel);
                            }
                        }
                        image.writeToFile(outputPath.toFile());
                        IconExporterClient.LOGGER.info("Rendered item icon to {}", outputPath.toAbsolutePath());
                    } catch (IOException exception) {
                        IconExporterClient.LOGGER.error("Could not save rendered icon to {}", outputPath.toAbsolutePath(), exception);
                    } finally {
                        buffer.close();
                        atlas.close();
                    }
                },
                0
        );
    }

    private static CompletableFuture<Suggestions> GetDisplayContextSuggestions(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        for (var item : ItemDisplayContext.values())
            builder.suggest(item.name());
        return builder.buildFuture();
    }
}
