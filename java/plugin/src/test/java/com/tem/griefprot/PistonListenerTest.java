package com.tem.griefprot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tem.griefprot.PistonListener.FaceDurability;
import com.tem.griefprot.PistonListener.Kind;
import com.tem.griefprot.PistonListener.Pos;
import com.tem.griefprot.PistonListener.Refusal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PistonListenerTest {

    @Nested
    class Faces {

        @Test
        @DisplayName("retractions guard the face matching the pulled block's travel direction")
        void retractGuardsTravelFacingSide() {
            assertEquals(BlockFace.WEST, PistonListener.sourceFaceToGuard(BlockFace.WEST, true));
        }

        @Test
        @DisplayName("extensions guard the face opposite the pushed block's travel direction")
        void extendGuardsOppositeTravelSide() {
            assertEquals(BlockFace.WEST, PistonListener.sourceFaceToGuard(BlockFace.EAST, false));
        }
    }

    @Nested
    class Dragging {

        @Test
        void slimeAndHoneyBothDrag() {
            assertTrue(PistonListener.dragsNeighbours(Material.SLIME_BLOCK));
            assertTrue(PistonListener.dragsNeighbours(Material.HONEY_BLOCK));
        }

        @Test
        @DisplayName("an ordinary block drags nothing")
        void ordinaryBlocksDoNotDrag() {
            assertFalse(PistonListener.dragsNeighbours(Material.STONE));
            assertFalse(PistonListener.dragsNeighbours(Material.PISTON));
            assertFalse(PistonListener.dragsNeighbours(Material.AIR));
        }

        @Test
        @DisplayName("a slime hauls the block behind it, the way the server reads it")
        void slimeHaulsTheBlockBehindIt() {
            Set<Pos> slime = new HashSet<>(Set.of(new Pos(1, 64, 0)));
            List<Pos> chain = PistonListener.dragChain(slime, BlockFace.EAST, at -> at.x() == 1, anywhere());

            assertEquals(List.of(new Pos(1, 64, 0), new Pos(0, 64, 0)), chain);
        }

        @Test
        @DisplayName("a row of slime hauls the whole row behind it")
        void aRowOfSlimeHaulsEverythingBehindIt() {
            Set<Pos> reported = new HashSet<>(List.of(new Pos(1, 64, 0), new Pos(2, 64, 0)));
            List<Pos> chain = PistonListener.dragChain(
                    reported, BlockFace.EAST, at -> at.x() == 1 || at.x() == 2, anywhere());

            assertEquals(
                    Set.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 64, 0)),
                    new HashSet<>(chain));
        }

        @Test
        @DisplayName("the event already listing the dragged block does not duplicate it")
        void anAlreadyListedDraggedBlockIsNotAddedTwice() {
            Set<Pos> reported = new HashSet<>(List.of(new Pos(1, 64, 0), new Pos(0, 64, 0)));
            List<Pos> chain = PistonListener.dragChain(reported, BlockFace.EAST, at -> at.x() == 1, anywhere());

            assertEquals(2, chain.size());
        }

        @Test
        @DisplayName("the chain stops at the build limits instead of walking off the world")
        void theChainStopsAtTheBuildLimits() {
            // A slime at the top of a 320 tall world, pulled down, would otherwise
            // haul whatever sits one block above it, which is nowhere.
            Pos sticky = new Pos(1, 319, 0);
            Set<Pos> reported = new HashSet<>(Set.of(sticky));

            List<Pos> chain = PistonListener.dragChain(
                    reported, BlockFace.DOWN, Set.of(sticky)::contains, pos -> pos.y() < 320);

            assertEquals(List.of(sticky), chain);
        }
    }

    @Nested
    class Ordering {

        @Test
        @DisplayName("the block in front moves first, so it vacates the cell behind it")
        void frontMovesFirst() {
            List<Pos> chain = List.of(new Pos(0, 64, 0), new Pos(2, 64, 0), new Pos(1, 64, 0));

            assertEquals(
                    List.of(new Pos(2, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 0)),
                    PistonListener.orderForVacating(chain, BlockFace.EAST));
        }

        @Test
        @DisplayName("front is the way the piston is pushing, whichever way that is")
        void frontFollowsTheTravelDirection() {
            List<Pos> chain = List.of(new Pos(2, 64, 0), new Pos(0, 64, 0), new Pos(1, 64, 0));

            assertEquals(
                    List.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(2, 64, 0)),
                    PistonListener.orderForVacating(chain, BlockFace.WEST));
        }

        @Test
        void frontFollowsTheTravelDirectionVertically() {
            List<Pos> chain = List.of(new Pos(0, 63, 0), new Pos(0, 65, 0), new Pos(0, 64, 0));

            assertEquals(
                    List.of(new Pos(0, 65, 0), new Pos(0, 64, 0), new Pos(0, 63, 0)),
                    PistonListener.orderForVacating(chain, BlockFace.UP));
        }
    }

    @Nested
    class Refusals {

        private final Map<String, Integer> reinforced = new HashMap<>();
        private final Set<Pos> slime = new HashSet<>();
        private final Set<Pos> exempt = new HashSet<>();

        private FaceDurability durability() {
            return (pos, face) -> reinforced.getOrDefault(key(pos, face), 0);
        }

        private Predicate<Pos> drags() {
            return slime::contains;
        }

        private Predicate<Pos> pistonFree() {
            return exempt::contains;
        }

        private void exempt(Pos pos) {
            exempt.add(pos);
        }

        private String key(Pos pos, BlockFace face) {
            return pos.x() + "/" + pos.y() + "/" + pos.z() + "/" + face;
        }

        private void reinforce(Pos pos, BlockFace face, int durability) {
            reinforced.put(key(pos, face), durability);
        }

        @Test
        @DisplayName("a wall block cannot be hauled out of the wall by a slime")
        void slimeCannotHaulAReinforcedWallBlockOut() {
            // Wall at x=0 reinforced on its east face, slime at x=1, sticky piston at
            // x=2 retracting east. The event only knows about the slime.
            Pos wall = new Pos(0, 64, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(wall, BlockFace.EAST, 96);

            Refusal refusal = refuse(run(BlockFace.EAST, true, sticky));

            assertEquals(Kind.STICKY_TOUCH, refusal.kind());
            assertEquals(wall, refusal.pos());
            assertEquals(BlockFace.EAST, refusal.face());
        }

        @Test
        @DisplayName("the same haul is refused on a push, where the pressed face differs")
        void slimeCannotHaulAReinforcedWallOutOnAPush() {
            // Piston at x=-2 pushing east, slime at x=0, wall at x=-1. An extension is
            // pressed on its trailing face, which is not the face the slime touches.
            Pos wall = new Pos(-1, 64, 0);
            Pos sticky = new Pos(0, 64, 0);
            slime.add(sticky);
            reinforce(wall, BlockFace.EAST, 96);

            Refusal refusal = refuse(run(BlockFace.EAST, false, sticky));

            assertEquals(Kind.STICKY_TOUCH, refusal.kind());
            assertEquals(wall, refusal.pos());
        }

        @Test
        @DisplayName("a slime beside a protected block it does not haul is refused too")
        void slimeBesideAReinforcedFaceIsRefused() {
            // Piston pushes the slime east. The block under the slime has its west
            // face open but the face the slime is touching reinforced, and neither is
            // the face the piston presses.
            //
            //     [P][S]  --> pushes east
            //     [B][B]  - left side open, the one under S reinforced
            Pos protectedSide = new Pos(1, 63, 0);
            Pos sticky = new Pos(1, 64, 0);
            reinforce(new Pos(0, 63, 0), BlockFace.WEST, 96);
            reinforce(protectedSide, BlockFace.UP, 96);
            slime.add(sticky);

            Refusal refusal = refuse(run(BlockFace.EAST, false, sticky));

            assertEquals(Kind.STICKY_TOUCH, refusal.kind());
            assertEquals(protectedSide, refusal.pos());
            assertEquals(BlockFace.UP, refusal.face());
        }

        @Test
        @DisplayName("so is the same arrangement on a pull, where the piston presses the other side")
        void slimeBesideAReinforcedFaceIsRefusedOnAPull() {
            Pos protectedSide = new Pos(1, 65, 0);
            Pos sticky = new Pos(1, 64, 0);
            reinforce(protectedSide, BlockFace.DOWN, 48);
            slime.add(sticky);

            Refusal refusal = refuse(run(BlockFace.WEST, true, sticky));

            assertEquals(Kind.STICKY_TOUCH, refusal.kind());
            assertEquals(protectedSide, refusal.pos());
            assertEquals(BlockFace.DOWN, refusal.face());
        }

        @Test
        @DisplayName("a slime touching nothing protected is left alone")
        void slimeHaulingNothingProtectedIsAllowed() {
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);

            assertNull(refuse(run(BlockFace.EAST, true, sticky)));
        }

        @Test
        @DisplayName("reinforcement on the side the slime never touches does not refuse the move")
        void onlyAFaceTheSlimeActuallyTouchesCounts() {
            // The slime at x=1 touches the wall at x=0 on its east side. The wall is
            // reinforced on the far side, which no part of this move can reach.
            Pos wall = new Pos(0, 64, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(wall, BlockFace.WEST, 96);

            assertNull(refuse(run(BlockFace.EAST, true, sticky)));
        }

        @Test
        @DisplayName("an ordinary block beside a protected block is still allowed")
        void anOrdinaryBlockBesideAReinforcedBlockIsAllowed() {
            // Only slime and honey get the adjacency rule. Everything else is already
            // covered by the pressed face, and widening it further would refuse
            // ordinary pushes next to any reinforced wall.
            Pos wall = new Pos(1, 63, 0);
            Pos ordinary = new Pos(1, 64, 0);
            reinforce(wall, BlockFace.UP, 96);

            assertNull(PistonListener.refusal(
                    List.of(ordinary), BlockFace.EAST, false, durability(), pos -> false, pistonFree()));
        }

        @Test
        void movingOffAReinforcedFaceIsRefused() {
            Pos moved = new Pos(0, 64, 0);
            reinforce(moved, BlockFace.EAST, 12);

            Refusal refusal = PistonListener.refusal(
                    List.of(moved), BlockFace.EAST, true, durability(), drags(), pistonFree());

            assertEquals(Kind.SOURCE_FACE, refusal.kind());
        }

        @Test
        void movingIntoAReinforcedFaceIsRefused() {
            Pos moved = new Pos(0, 64, 0);
            Pos destination = new Pos(1, 64, 0);
            reinforce(destination, BlockFace.WEST, 12);

            Refusal refusal = PistonListener.refusal(
                    List.of(moved), BlockFace.EAST, true, durability(), drags(), pistonFree());

            assertEquals(Kind.ENTERED_FACE, refusal.kind());
            assertEquals(destination, refusal.pos());
        }

        @Test
        @DisplayName("a plain block with no reinforcement is never refused")
        void anUnprotectedBlockIsNeverRefused() {
            assertNull(PistonListener.refusal(
                    List.of(new Pos(0, 64, 0)), BlockFace.EAST, true, durability(), drags(), pistonFree()));
        }

        @Test
        @DisplayName("a piston free block ignores the rules about itself")
        void aPistonFreeBlockIsNeverRefused() {
            // Marked pushable from an unprotected side, so its own faces no longer
            // veto the move in either direction.
            Pos moved = new Pos(0, 64, 0);
            reinforce(moved, BlockFace.EAST, 12);
            reinforce(new Pos(1, 64, 0), BlockFace.WEST, 12);
            exempt(moved);

            assertNull(PistonListener.refusal(
                    List.of(moved), BlockFace.EAST, true, durability(), drags(), pistonFree()));
        }

        @Test
        @DisplayName("a piston free slime does not refuse the move by what it touches")
        void aPistonFreeSlimeIgnoresItsNeighbours() {
            Pos wall = new Pos(0, 64, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(wall, BlockFace.EAST, 96);
            exempt(sticky);

            assertNull(PistonListener.refusal(
                    List.of(sticky), BlockFace.EAST, true, durability(), drags(), pistonFree()));
        }

        @Test
        @DisplayName("and a piston free neighbour does not refuse the slime against it")
        void aPistonFreeNeighbourDoesNotRefuseTheSlime() {
            // The exemption is not a standing waiver on the block either. What it
            // removes is that block's own veto: a piston free block is one a piston
            // is allowed to be near whatever is on its faces, so an ordinary slime
            // sitting against one is not stopped by it.
            Pos slick = new Pos(0, 64, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(slick, BlockFace.EAST, 96);
            exempt(slick);

            assertNull(PistonListener.refusal(
                    List.of(sticky), BlockFace.EAST, true, durability(), drags(), pistonFree()));
        }

        @Test
        @DisplayName("a piston free block does not make its neighbours exempt as well")
        void aPistonFreeBlockDoesNotExemptItsNeighbours() {
            // The exemption is per block, not per cell. The sticky block is touching
            // two reinforced blocks and only one of them has been slicked, so slicking
            // one brick must not unlock the next one along.
            //
            //     [A]        slicked and piston free, east face reinforced
            //     [S]  -->   the sticky block being hauled east
            //     [B]        not slicked, top face reinforced
            Pos slick = new Pos(0, 64, 0);
            Pos stillArmed = new Pos(1, 63, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(slick, BlockFace.EAST, 96);
            reinforce(stillArmed, BlockFace.UP, 96);
            exempt(slick);

            Refusal refusal = PistonListener.refusal(
                    List.of(sticky), BlockFace.EAST, true, durability(), drags(), pistonFree());

            assertEquals(Kind.STICKY_TOUCH, refusal.kind());
            assertEquals(stillArmed, refusal.pos());
            assertEquals(BlockFace.UP, refusal.face());
        }

        @Test
        void theRefusalNamesTheBlockForTheLog() {
            Pos wall = new Pos(0, 64, 0);
            Pos sticky = new Pos(1, 64, 0);
            slime.add(sticky);
            reinforce(wall, BlockFace.EAST, 96);

            String message = refuse(run(BlockFace.EAST, true, sticky)).toString();

            assertEquals(
                    "a slime or honey block is pressed against reinforced east face at 0,64,0 (durability=96)",
                    message);
        }

        /** The chain the listener would build for one piston moving {@code sticky}. */
        private Run run(BlockFace direction, boolean retract, Pos sticky) {
            return new Run(
                    PistonListener.orderForVacating(
                            PistonListener.dragChain(List.of(sticky), direction, drags(), anywhere()),
                            direction),
                    direction,
                    retract);
        }

        private Refusal refuse(Run run) {
            return PistonListener.refusal(
                    run.chain(), run.direction(), run.retract(), durability(), drags(), pistonFree());
        }
    }

    /** One piston move: the chain it builds, and the event that set it off. */
    private record Run(List<PistonListener.Pos> chain, BlockFace direction, boolean retract) {}

    private static Predicate<Pos> anywhere() {
        return pos -> true;
    }
}