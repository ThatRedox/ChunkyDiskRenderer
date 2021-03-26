package chunkydiskrenderer;

import se.llbit.chunky.Plugin;
import se.llbit.chunky.main.Chunky;
import se.llbit.chunky.main.ChunkyOptions;
import se.llbit.chunky.ui.ChunkyFx;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.IOException;

public class ChunkyDiskRenderer implements Plugin {
    @Override
    public void attach(Chunky chunky) {
        // Change to new renderer
        chunky.setRendererFactory(DiskRenderManager::new);
        Octree.addImplementationFactory("LargeDiskOctree", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                return new LargeDiskOctree(depth);
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                throw new IOException("Loading not supported!");
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                throw new IOException("Loading not supported!");
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation implementation) {
                return implementation instanceof LargeDiskOctree;
            }

            @Override
            public String getDescription() {
                return "A large disk octree. Use with custom renderer.";
            }
        });
    }

    public static void main(String[] args) {
        // Start Chunky normally with this plugin attached.
        Chunky.loadDefaultTextures();
        Chunky chunky = new Chunky(ChunkyOptions.getDefaults());
        new ChunkyDiskRenderer().attach(chunky);
        ChunkyFx.startChunkyUI(chunky);
    }
}
