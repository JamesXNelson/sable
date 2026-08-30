package dev.ryanhcode.sable.neoforge.gametest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/**
 * Focused regression coverage for the removed-sublevel split crash reported in Sable #1376.
 */
@GameTestHolder(Sable.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RemovedSubLevelSplitTest {

    private static final String REMOVED_PLOT_EXCEPTION =
            "Sub-level assembly attempted inside plot of already removed sub-level";

    private RemovedSubLevelSplitTest() {
    }

    /**
     * A removed sublevel remains registered in its plot until the container processes removals. If it still has
     * pending disconnected heat-map regions, ticking it must not create another sublevel inside its removed plot.
     */
    @GameTest(template = "assemblytest.brittlebreak", timeoutTicks = 20)
    public static void removedSubLevelDoesNotAttemptSplitAssembly(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final ServerSubLevelContainer plotContainer = SubLevelContainer.getContainer(level);
        if (plotContainer == null) {
            throw new IllegalStateException("Plot container not found in level");
        }

        final ServerSubLevel subLevel = SableTestHelper.spawnSubLevel(
                plotContainer,
                SableTestHelper.absolutePosition(helper, new Vector3d(1.5, 5.0, 1.5)),
                accessor -> {
                    accessor.setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
                    accessor.setBlock(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState(), 3);
                });

        final int initialSubLevelCount = plotContainer.getAllSubLevels().size();

        // Leave a disconnected region pending, but mark the owner removed before its heat-map can finish.
        // Sable 2.0.5 reaches assembleBlocks(), finds this still-registered removed plot, and throws the exact
        // exception from #1376. Tick directly so the container cannot process the removal before the regression.
        subLevel.markRemoved();

        try {
            for (int i = 0; i < 64; i++) {
                subLevel.tick();
            }
        } catch (final RuntimeException exception) {
            if (REMOVED_PLOT_EXCEPTION.equals(exception.getMessage())) {
                helper.fail("Regression #1376: removed sublevel attempted heat-map split assembly");
                return;
            }
            throw exception;
        }

        final int finalSubLevelCount = plotContainer.getAllSubLevels().size();
        if (finalSubLevelCount != initialSubLevelCount) {
            helper.fail("Removed sublevel split created "
                    + (finalSubLevelCount - initialSubLevelCount)
                    + " unexpected sublevel(s)");
            return;
        }

        helper.succeed();
    }
}
